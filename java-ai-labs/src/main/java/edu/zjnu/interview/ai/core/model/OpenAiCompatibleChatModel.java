package edu.zjnu.interview.ai.core.model;

import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.core.LabEnv;
import edu.zjnu.interview.ai.core.StopReason;
import edu.zjnu.interview.ai.core.TokenUsage;
import edu.zjnu.interview.ai.core.ToolCall;
import edu.zjnu.interview.ai.core.ToolSpec;
import edu.zjnu.interview.ai.harness.FailureCategory;
import edu.zjnu.interview.ai.harness.FailureClassifier;
import edu.zjnu.interview.ai.harness.ModelCallException;
import edu.zjnu.interview.ai.harness.ModelErrors;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的真实模型适配器。
 *
 * <p><b>为什么只实现「OpenAI 兼容」这一种协议</b>：
 * Qwen（百炼 / DashScope compatible-mode）、DeepSeek、OpenAI、以及大多数内部网关，
 * 都暴露同一个 {@code /chat/completions} 契约（请求字段、响应字段、tool_calls 结构一致）。
 * 实现一次，就能接入四家厂商——这是「适配器模式」在 AI 工程里最直接的收益。
 * 真正需要单独适配的只有「非兼容协议」（比如 Anthropic 的 tool_use、智谱的特定扩展）。
 *
 * <p><b>配置全部来自 {@link LabEnv}（离线优先）</b>：
 * <ul>
 *   <li>{@code OPENAI_BASE_URL}：网关地址；</li>
 *   <li>{@code OPENAI_API_KEY}：密钥；</li>
 *   <li>{@code OPENAI_MODEL}：模型名；</li>
 *   <li>{@code OPENAI_TIMEOUT_MS}：超时（默认 30s）。</li>
 * </ul>
 * 没有这些配置时，<b>整个工程依然可以离线跑通</b>（走 {@code core.mock}）——
 * 真实模型只是「可选增强」，不是「运行前提」。
 *
 * <p><b>失败语义（两层）</b>：
 * <ul>
 *   <li>{@link #chat(ChatRequest)}：遵守 SPI 契约，<b>永不抛异常</b>，
 *       失败返回 {@code stopReason=ERROR} + 结构化错误载荷；</li>
 *   <li>{@link #chatOrThrow(ChatRequest)}：抛出带分类的 {@link ModelCallException}，
 *       供重试 / 降级 / 熔断策略消费。</li>
 * </ul>
 * 两层并存的原因：SPI 契约要求「上层永远拿到响应」，
 * 而稳定性策略需要「失败的原因与分类」——把两者都提供，装饰器就能自由选择。
 */
public final class OpenAiCompatibleChatModel implements ChatModel {

    /** 默认超时。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final HttpClient httpClient;

    public OpenAiCompatibleChatModel(String baseUrl, String apiKey, String model, Duration timeout) {
        this(baseUrl, apiKey, model, timeout, HttpClient.newBuilder()
                .connectTimeout(timeout == null ? DEFAULT_TIMEOUT : timeout)
                .build());
    }

    public OpenAiCompatibleChatModel(String baseUrl, String apiKey, String model, Duration timeout, HttpClient httpClient) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.model = model == null || model.isBlank() ? "gpt-4o-mini" : model;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        this.httpClient = httpClient;
    }

    /**
     * 从 {@link LabEnv} 构造。
     *
     * <p>没有配置 {@code OPENAI_BASE_URL} 时返回 null——调用方据此决定「用真实模型还是用 mock」。
     */
    public static OpenAiCompatibleChatModel fromEnv() {
        String baseUrl = LabEnv.get("OPENAI_BASE_URL", null);
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String apiKey = LabEnv.get("OPENAI_API_KEY", "");
        String model = LabEnv.get("OPENAI_MODEL", "gpt-4o-mini");
        int timeoutMs = LabEnv.getInt("OPENAI_TIMEOUT_MS", (int) DEFAULT_TIMEOUT.toMillis());
        return new OpenAiCompatibleChatModel(baseUrl, apiKey, model, Duration.ofMillis(timeoutMs));
    }

    @Override
    public String name() {
        return model;
    }

    /** 基础地址（去掉尾部斜杠）。 */
    public String baseUrl() {
        return baseUrl;
    }

    /** {@code /chat/completions} 完整地址。 */
    public URI endpoint() {
        return URI.create(baseUrl.endsWith("/chat/completions")
                ? baseUrl
                : baseUrl + "/chat/completions");
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        try {
            return chatOrThrow(request);
        } catch (ModelCallException e) {
            return errorResponse(e);
        }
    }

    /**
     * 执行调用；失败时抛 {@link ModelCallException}（带失败分类）。
     *
     * @throws ModelCallException 网络失败 / 超时 / 服务端错误 / 协议错误
     */
    public ChatResponse chatOrThrow(ChatRequest request) {
        long start = System.nanoTime();
        String body = Json.write(buildRequestBody(request));
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint())
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        // 没有 API Key 时不发 Authorization 头：本地网关 / 自建推理服务常常不需要鉴权。
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpRequest httpRequest = builder
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new ModelCallException("模型调用超时（" + timeout.toMillis() + "ms）",
                    FailureCategory.TIMEOUT, 0, e);
        } catch (IOException e) {
            throw new ModelCallException("模型调用网络异常: " + e.getMessage(),
                    FailureCategory.NETWORK, 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("模型调用被中断", FailureCategory.UNKNOWN, 0, e);
        }

        long latency = (System.nanoTime() - start) / 1_000_000L;
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            FailureCategory category = FailureClassifier.fromStatusCode(status);
            throw new ModelCallException("模型服务返回错误状态 " + status + ": " + truncate(response.body()),
                    category, status);
        }
        return parseResponse(response.body(), latency);
    }

    /** 构造请求体（测试可直接断言这个结构）。 */
    public Map<String, Object> buildRequestBody(ChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model() == null || request.model().isBlank() ? model : request.model());
        body.put("messages", encodeMessages(request.messages()));
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        body.put("stream", false);
        if (!request.tools().isEmpty()) {
            body.put("tools", encodeTools(request.tools()));
            body.put("tool_choice", "auto");
        }
        return body;
    }

    /**
     * 消息编码：覆盖四种角色。
     *
     * <p>assistant 的 tool_calls 与 tool 结果回灌（{@code tool_call_id}）是
     * Agent Loop 的「模型 → 工具 → 结果」闭环在协议层的体现，
     * 少一个字段，多轮工具调用就会断链。
     */
    static List<Map<String, Object>> encodeMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatMessage message : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            switch (message.role()) {
                case SYSTEM -> {
                    item.put("role", "system");
                    item.put("content", message.content());
                }
                case USER -> {
                    item.put("role", "user");
                    item.put("content", message.content());
                }
                case ASSISTANT -> {
                    item.put("role", "assistant");
                    item.put("content", message.content());
                    if (message.hasToolCalls()) {
                        List<Map<String, Object>> calls = new ArrayList<>();
                        for (ToolCall call : message.toolCalls()) {
                            Map<String, Object> fn = new LinkedHashMap<>();
                            fn.put("name", call.name());
                            fn.put("arguments", call.argumentsJson());
                            Map<String, Object> toolCall = new LinkedHashMap<>();
                            toolCall.put("id", call.id());
                            toolCall.put("type", "function");
                            toolCall.put("function", fn);
                            calls.add(toolCall);
                        }
                        item.put("tool_calls", calls);
                    }
                }
                case TOOL -> {
                    item.put("role", "tool");
                    item.put("content", message.content());
                    if (message.toolCallId() != null) {
                        item.put("tool_call_id", message.toolCallId());
                    }
                    if (message.name() != null) {
                        item.put("name", message.name());
                    }
                }
                default -> {
                    item.put("role", "user");
                    item.put("content", message.content());
                }
            }
            out.add(item);
        }
        return out;
    }

    /**
     * 工具声明编码：{@link ToolSpec} → JSON Schema。
     *
     * <p>一份声明两处复用：{@code ToolSpec.parameters()} 既喂给模型，
     * 又能被运行时拿来做入参校验——避免「模型以为的参数」和「代码真正接受的参数」长期漂移。
     */
    static List<Map<String, Object>> encodeTools(List<ToolSpec> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolSpec spec : tools) {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            spec.parameters().forEach((name, param) -> {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", param.type());
                if (param.description() != null) {
                    schema.put("description", param.description());
                }
                if (param.defaultValue() != null) {
                    schema.put("default", param.defaultValue());
                }
                properties.put(name, schema);
                if (param.required()) {
                    required.add(name);
                }
            });
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("type", "object");
            parameters.put("properties", properties);
            parameters.put("required", required);

            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", spec.name());
            function.put("description", spec.description());
            function.put("parameters", parameters);

            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", function);
            out.add(tool);
        }
        return out;
    }

    /**
     * 解析响应。
     *
     * <p><b>解析失败降级为纯文本</b>：如果返回体不是预期结构（例如网关返回了 HTML 错误页），
     * 直接把原始文本作为答案返回，而不是让整条链路挂掉。
     * 理由：模型输出属于「不可信外部输入」，解析器必须有兜底路径。
     */
    ChatResponse parseResponse(String rawBody, long latencyMillis) {
        Map<String, Object> root = Json.readMap(rawBody);
        if (root.isEmpty()) {
            // 不是 JSON（或为空）：降级为纯文本
            return new ChatResponse(rawBody == null ? "" : rawBody, List.of(), TokenUsage.ZERO,
                    StopReason.END, latencyMillis);
        }
        Object choicesObj = root.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            Object content = root.get("content");
            String text = content == null ? (rawBody == null ? "" : rawBody) : String.valueOf(content);
            return new ChatResponse(text, List.of(), readUsage(root), StopReason.END, latencyMillis);
        }
        Map<String, Object> choice = asMap(choices.get(0));
        if (choice == null) {
            return new ChatResponse(rawBody == null ? "" : rawBody, List.of(), readUsage(root),
                    StopReason.END, latencyMillis);
        }
        Map<String, Object> message = asMap(choice.get("message"));
        String content = message == null ? "" : stringValue(message.get("content"));

        List<ToolCall> toolCalls = new ArrayList<>();
        if (message != null && message.get("tool_calls") instanceof List<?> rawCalls) {
            for (Object rawCall : rawCalls) {
                Map<String, Object> callMap = asMap(rawCall);
                if (callMap == null) {
                    continue;
                }
                Map<String, Object> function = asMap(callMap.get("function"));
                String name = function == null ? null : stringValue(function.get("name"));
                if (name == null || name.isBlank()) {
                    continue;
                }
                String id = stringValue(callMap.get("id"));
                // arguments 保持原始字符串：模型经常返回不合法 JSON，
                // 交给工具层做容错解析（见 core.ToolCall 的注释）。
                String arguments = function == null ? "{}" : stringValue(function.get("arguments"));
                toolCalls.add(new ToolCall(id, name, arguments == null ? "{}" : arguments));
            }
        }

        StopReason reason = mapStopReason(stringValue(choice.get("finish_reason")), toolCalls);
        return new ChatResponse(content, toolCalls, readUsage(root), reason, latencyMillis);
    }

    private static StopReason mapStopReason(String finishReason, List<ToolCall> toolCalls) {
        if (finishReason == null) {
            return toolCalls.isEmpty() ? StopReason.END : StopReason.TOOL_CALLS;
        }
        switch (finishReason) {
            case "stop":
                return StopReason.END;
            case "length":
                return StopReason.LENGTH;
            case "tool_calls":
            case "function_call":
                return StopReason.TOOL_CALLS;
            case "content_filter":
                return StopReason.CONTENT_FILTER;
            default:
                return toolCalls.isEmpty() ? StopReason.END : StopReason.TOOL_CALLS;
        }
    }

    private static TokenUsage readUsage(Map<String, Object> root) {
        Map<String, Object> usage = asMap(root.get("usage"));
        if (usage == null) {
            return TokenUsage.ZERO;
        }
        return TokenUsage.of(intValue(usage.get("prompt_tokens")), intValue(usage.get("completion_tokens")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private ChatResponse errorResponse(ModelCallException e) {
        String payload = ModelErrors.payload(e.category(), e.statusCode(), e.getMessage());
        return new ChatResponse(payload, List.of(), TokenUsage.ZERO, StopReason.ERROR, 0L);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "…";
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
