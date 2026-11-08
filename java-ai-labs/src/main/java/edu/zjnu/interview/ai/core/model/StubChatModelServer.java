package edu.zjnu.interview.ai.core.model;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 本地 stub 模型服务：用 JDK 内置 {@code com.sun.net.httpserver} 起一个假网关。
 *
 * <p><b>为什么需要它</b>：适配器的正确性最容易在「协议细节」上出错——
 * 请求体字段名错、tool_calls 结构解析错、错误状态码没被识别。
 * 这些问题靠 mock（{@code core.mock}）永远测不出来，因为 mock 根本不碰 HTTP。
 * 而真实厂商 API 又不能进 CI（要 Key、要外网、还有费用）。
 * 本地 stub 把「HTTP 往返」这一段补上，让集成测试<b>完全离线、零成本、可重复</b>。
 *
 * <p><b>能断言什么</b>：收到的原始请求体（断言字段结构）、请求头、路径，
 * 以及「我喂什么它就回什么」的响应解析。
 */
public final class StubChatModelServer implements AutoCloseable {

    /**
     * 收到的一次请求。
     *
     * @param method  HTTP 方法
     * @param path    请求路径
     * @param body    请求体（原始字符串）
     * @param headers 请求头
     */
    public record StubRequest(String method, String path, String body, Map<String, String> headers) {
    }

    /**
     * stub 返回的响应。
     *
     * @param status      HTTP 状态码
     * @param body        响应体
     * @param contentType Content-Type
     */
    public record StubResponse(int status, String body, String contentType) {

        public static StubResponse ok(String body) {
            return new StubResponse(200, body, "application/json");
        }

        public static StubResponse status(int status, String body) {
            return new StubResponse(status, body, "application/json");
        }
    }

    private final HttpServer server;
    private final List<StubRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile Function<StubRequest, StubResponse> handler;

    private StubChatModelServer(HttpServer server) {
        this.server = server;
    }

    /** 启动一个监听随机端口的 stub 服务。 */
    public static StubChatModelServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        StubChatModelServer stub = new StubChatModelServer(server);
        server.createContext("/", stub::handle);
        server.setExecutor(null);
        server.start();
        return stub;
    }

    /** 端口。 */
    public int port() {
        return server.getAddress().getPort();
    }

    /** 基础地址（可直接喂给 {@link OpenAiCompatibleChatModel}）。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + port() + "/v1";
    }

    /** 设置固定响应。 */
    public StubChatModelServer respond(StubResponse response) {
        this.handler = request -> response;
        return this;
    }

    /** 设置固定响应（JSON 字符串）。 */
    public StubChatModelServer respondJson(String json) {
        return respond(StubResponse.ok(json));
    }

    /**
     * 设置响应序列：按调用次序依次返回。
     *
     * <p>用于测试「第一次 429、第二次成功」这类<b>状态迁移</b>场景——
     * 这正是重试策略最需要验证的地方。
     */
    public StubChatModelServer respondSequence(StubResponse... responses) {
        List<StubResponse> list = List.of(responses);
        this.handler = request -> {
            int index = sequence.getAndIncrement();
            return index < list.size() ? list.get(index) : list.get(list.size() - 1);
        };
        return this;
    }

    /** 自定义处理器。 */
    public StubChatModelServer handleWith(Function<StubRequest, StubResponse> value) {
        this.handler = value;
        return this;
    }

    /** 已收到的请求列表。 */
    public List<StubRequest> requests() {
        return List.copyOf(requests);
    }

    /** 最近一次请求。 */
    public StubRequest lastRequest() {
        List<StubRequest> list = requests;
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    /** 最近一次请求体。 */
    public String lastRequestBody() {
        StubRequest request = lastRequest();
        return request == null ? null : request.body();
    }

    /** 请求次数。 */
    public int requestCount() {
        return requests.size();
    }

    /** 重置请求记录。 */
    public void reset() {
        requests.clear();
        sequence.set(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, String.join(",", v)));
        StubRequest request = new StubRequest(exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(), body, headers);
        requests.add(request);

        StubResponse response;
        Function<StubRequest, StubResponse> current = handler;
        if (current == null) {
            response = StubResponse.status(404, "{\"error\":\"no stub configured\"}");
        } else {
            response = current.apply(request);
        }
        byte[] payload = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type",
                response.contentType() == null ? "application/json" : response.contentType());
        exchange.sendResponseHeaders(response.status(), payload.length);
        try (var os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** 便捷方法：构造一个标准 OpenAI 风格的对话响应。 */
    public static String chatCompletionJson(String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", 11);
        usage.put("completion_tokens", 7);
        usage.put("total_tokens", 18);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", "chatcmpl-stub");
        root.put("object", "chat.completion");
        root.put("choices", List.of(choice));
        root.put("usage", usage);
        return edu.zjnu.interview.ai.core.Json.write(root);
    }

    /** 便捷方法：构造一个带 tool_calls 的响应。 */
    public static String toolCallCompletionJson(String callId, String toolName, String argumentsJson) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", toolName);
        function.put("arguments", argumentsJson);
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", callId);
        toolCall.put("type", "function");
        toolCall.put("function", function);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", "");
        message.put("tool_calls", List.of(toolCall));
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "tool_calls");
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", 20);
        usage.put("completion_tokens", 9);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", "chatcmpl-stub-tool");
        root.put("choices", List.of(choice));
        root.put("usage", usage);
        return edu.zjnu.interview.ai.core.Json.write(root);
    }

    /** 便捷方法：构造一个错误响应体。 */
    public static String errorJson(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("error", error);
        return edu.zjnu.interview.ai.core.Json.write(root);
    }

    /** 便捷方法：构造一个 embeddings 响应体。 */
    public static String embeddingJson(List<Double> vector) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("object", "embedding");
        item.put("index", 0);
        item.put("embedding", vector);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("object", "list");
        root.put("data", List.of(item));
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", 4);
        usage.put("total_tokens", 4);
        root.put("usage", usage);
        return edu.zjnu.interview.ai.core.Json.write(root);
    }

    /** 空列表（避免未使用告警）。 */
    static List<Object> emptyList() {
        return new ArrayList<>();
    }
}
