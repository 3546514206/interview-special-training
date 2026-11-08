package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.core.StopReason;
import edu.zjnu.interview.ai.core.ToolSpec;
import edu.zjnu.interview.ai.core.model.OpenAiCompatibleChatModel;
import edu.zjnu.interview.ai.core.model.StubChatModelServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI 兼容适配器回归：用本地 stub server 断言「请求体 + 响应解析」。
 *
 * <p>完全离线：不连外网、不需要 API Key。
 */
class OpenAiCompatibleChatModelTest {

    private StubChatModelServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = StubChatModelServer.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    private OpenAiCompatibleChatModel model(Duration timeout) {
        return new OpenAiCompatibleChatModel(server.baseUrl(), "test-key", "qwen-plus", timeout);
    }

    @Test
    @DisplayName("请求体包含 model / messages / temperature / max_tokens")
    void buildsChatCompletionRequestBody() {
        server.respondJson(StubChatModelServer.chatCompletionJson("你好"));
        ChatRequest request = new ChatRequest(
                List.of(ChatMessage.system("你是助手"), ChatMessage.user("你好")),
                List.of(), 0.3, 512, "qwen-plus");

        model(Duration.ofSeconds(5)).chat(request);

        String body = server.lastRequestBody();
        assertNotNull(body);
        Map<String, Object> parsed = Json.readMap(body);
        assertEquals("qwen-plus", parsed.get("model"));
        assertEquals(0.3, (Double) parsed.get("temperature"), 1e-9);
        assertEquals(512, parsed.get("max_tokens"));

        List<?> messages = (List<?>) parsed.get("messages");
        assertEquals(2, messages.size());
        Map<?, ?> system = (Map<?, ?>) messages.get(0);
        Map<?, ?> user = (Map<?, ?>) messages.get(1);
        assertEquals("system", system.get("role"));
        assertEquals("你是助手", system.get("content"));
        assertEquals("user", user.get("role"));
        assertEquals("你好", user.get("content"));
    }

    @Test
    @DisplayName("工具声明被转成 JSON Schema（type / properties / required）")
    void encodesToolsAsJsonSchema() {
        server.respondJson(StubChatModelServer.chatCompletionJson("ok"));
        ToolSpec spec = ToolSpec.builder("interest_calculator", "计算利息")
                .param("principal", "number", true, "本金")
                .optional("days", "number", "天数", 365)
                .build();
        ChatRequest request = new ChatRequest(List.of(ChatMessage.user("算利息")),
                List.of(spec), 0.2, 256, "qwen-plus");

        model(Duration.ofSeconds(5)).chat(request);

        Map<String, Object> body = Json.readMap(server.lastRequestBody());
        List<?> tools = (List<?>) body.get("tools");
        assertNotNull(tools);
        assertEquals(1, tools.size());
        Map<?, ?> tool = (Map<?, ?>) tools.get(0);
        assertEquals("function", tool.get("type"));
        Map<?, ?> function = (Map<?, ?>) tool.get("function");
        assertEquals("interest_calculator", function.get("name"));
        Map<?, ?> parameters = (Map<?, ?>) function.get("parameters");
        assertEquals("object", parameters.get("type"));
        Map<?, ?> properties = (Map<?, ?>) parameters.get("properties");
        assertNotNull(properties.get("principal"));
        assertEquals("number", ((Map<?, ?>) properties.get("principal")).get("type"));
        List<?> required = (List<?>) parameters.get("required");
        assertEquals(List.of("principal"), required, "只有必填参数进 required");
    }

    @Test
    @DisplayName("解析文本响应：内容、usage、终止原因")
    void parsesTextResponse() {
        server.respondJson(StubChatModelServer.chatCompletionJson("单利是 36.00 元"));
        ChatResponse response = model(Duration.ofSeconds(5))
                .chat(ChatRequest.of(List.of(ChatMessage.user("算利息"))));

        assertEquals("单利是 36.00 元", response.content());
        assertEquals(StopReason.END, response.stopReason());
        assertEquals(11, response.usage().promptTokens());
        assertEquals(7, response.usage().completionTokens());
        assertTrue(response.latencyMillis() >= 0);
    }

    @Test
    @DisplayName("解析 tool_calls：工具名与参数原样保留")
    void parsesToolCalls() {
        server.respondJson(StubChatModelServer.toolCallCompletionJson(
                "call_abc", "interest_calculator", "{\"principal\":1000,\"annualRate\":0.036,\"days\":365}"));

        ToolSpec spec = ToolSpec.builder("interest_calculator", "计算利息")
                .param("principal", "number", true, "本金")
                .build();
        ChatResponse response = model(Duration.ofSeconds(5)).chat(new ChatRequest(
                List.of(ChatMessage.user("算利息")), List.of(spec), 0.2, 256, "qwen-plus"));

        assertTrue(response.hasToolCalls());
        assertEquals(StopReason.TOOL_CALLS, response.stopReason());
        assertEquals("interest_calculator", response.toolCalls().get(0).name());
        assertEquals("call_abc", response.toolCalls().get(0).id());
        assertTrue(response.toolCalls().get(0).argumentsJson().contains("principal"));
    }

    @Test
    @DisplayName("HTTP 错误 → 结构化错误响应（不抛异常），并带上失败分类")
    void returnsStructuredErrorOnHttpFailure() {
        server.respond(StubChatModelServer.StubResponse.status(429,
                StubChatModelServer.errorJson("rate_limit", "too many requests")));

        ChatResponse response = model(Duration.ofSeconds(5))
                .chat(ChatRequest.of(List.of(ChatMessage.user("你好"))));

        assertEquals(StopReason.ERROR, response.stopReason());
        assertEquals(FailureCategory.RATE_LIMIT, ModelErrors.categoryOf(response.content()));
        assertEquals(429, ModelErrors.statusOf(response.content()));
    }

    @Test
    @DisplayName("服务端错误可被重试策略识别")
    void serverErrorIsRetryable() {
        server.respond(StubChatModelServer.StubResponse.status(503, "{\"error\":\"unavailable\"}"));
        ChatResponse response = model(Duration.ofSeconds(5))
                .chat(ChatRequest.of(List.of(ChatMessage.user("你好"))));
        assertTrue(FailureClassifier.defaultClassifier().isRetryable(response),
                "5xx 应当被判定为可重试");
    }

    @Test
    @DisplayName("解析失败降级为纯文本（不抛异常、不丢内容）")
    void degradesToPlainTextWhenParsingFails() {
        server.respondJson("这不是 JSON，只是一段纯文本");
        ChatResponse response = model(Duration.ofSeconds(5))
                .chat(ChatRequest.of(List.of(ChatMessage.user("你好"))));

        assertEquals("这不是 JSON，只是一段纯文本", response.content());
        assertEquals(StopReason.END, response.stopReason());
        assertFalse(response.hasToolCalls());
    }

    @Test
    @DisplayName("缺少 choices 字段时降级为纯文本")
    void degradesWhenChoicesMissing() {
        server.respondJson("{\"foo\":\"bar\"}");
        ChatResponse response = model(Duration.ofSeconds(5))
                .chat(ChatRequest.of(List.of(ChatMessage.user("你好"))));
        assertNotNull(response.content());
        assertEquals(StopReason.END, response.stopReason());
    }

    @Test
    @DisplayName("超时 → 结构化错误，且分类为 TIMEOUT")
    void timeoutReturnsStructuredError() {
        server.handleWith(request -> {
            try {
                Thread.sleep(800L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return StubChatModelServer.StubResponse.ok(StubChatModelServer.chatCompletionJson("late"));
        });

        ChatResponse response = model(Duration.ofMillis(150))
                .chat(ChatRequest.of(List.of(ChatMessage.user("你好"))));

        assertEquals(StopReason.ERROR, response.stopReason());
        assertEquals(FailureCategory.TIMEOUT, ModelErrors.categoryOf(response.content()));
    }

    @Test
    @DisplayName("多轮工具调用：assistant 的 tool_calls 与 tool 结果都能正确编码")
    void encodesMultiTurnToolConversation() {
        server.respondJson(StubChatModelServer.chatCompletionJson("利息是 36 元"));
        ChatRequest request = new ChatRequest(List.of(
                ChatMessage.user("算利息"),
                ChatMessage.assistantToolCalls(List.of(
                        edu.zjnu.interview.ai.core.ToolCall.of("call_1", "interest_calculator", "{\"principal\":1000}"))),
                ChatMessage.toolResult("call_1", "interest_calculator", "{\"interest\":36.0}")),
                List.of(), 0.2, 256, "qwen-plus");

        model(Duration.ofSeconds(5)).chat(request);

        List<?> messages = (List<?>) Json.readMap(server.lastRequestBody()).get("messages");
        assertEquals(3, messages.size());
        Map<?, ?> assistant = (Map<?, ?>) messages.get(1);
        assertEquals("assistant", assistant.get("role"));
        List<?> toolCalls = (List<?>) assistant.get("tool_calls");
        assertEquals(1, toolCalls.size());
        Map<?, ?> toolCall = (Map<?, ?>) toolCalls.get(0);
        assertEquals("call_1", toolCall.get("id"));
        assertEquals("function", toolCall.get("type"));

        Map<?, ?> tool = (Map<?, ?>) messages.get(2);
        assertEquals("tool", tool.get("role"));
        assertEquals("call_1", tool.get("tool_call_id"));
        assertEquals("interest_calculator", tool.get("name"));
    }
}
