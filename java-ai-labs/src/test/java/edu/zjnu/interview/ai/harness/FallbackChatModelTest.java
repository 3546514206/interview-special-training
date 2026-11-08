package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.StopReason;
import edu.zjnu.interview.ai.core.mock.MockChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 降级链回归：主模型失败 → 备选模型接管；降级事件落 span；全部失败返回结构化错误。
 */
class FallbackChatModelTest {

    private static final ChatRequest REQUEST = ChatRequest.of(
            List.of(ChatMessage.user("今天天气怎么样"))).withModel("qwen-plus");

    @Test
    @DisplayName("主模型失败 → 自动切到备选模型，并记录降级事件")
    void primaryFailureFallsBackToBackupModel() {
        FlakyChatModel primary = new FlakyChatModel("qwen-plus", 1, FailureCategory.SERVER_ERROR,
                ChatResponse.text("【主模型】"));
        MockChatModel backup = MockChatModel.builder("qwen-turbo")
                .fallback(ChatResponse.text("【备选模型】")).build();

        InMemoryTraceSink sink = new InMemoryTraceSink();
        Tracer tracer = new Tracer(sink);
        FallbackChatModel model = new FallbackChatModel(primary, List.of(backup), tracer);

        ChatResponse response = model.chat(REQUEST);
        assertEquals("【备选模型】", response.content());
        assertEquals(1, model.degradations().size());
        DegradationEvent event = model.degradations().get(0);
        assertEquals("qwen-plus", event.fromModel());
        assertEquals("qwen-turbo", event.toModel());
        assertEquals("fallback_model", event.trigger());

        assertEquals(1, sink.spansOfName("llm.degrade").size(), "降级必须落 span");
        assertEquals(Boolean.TRUE, sink.spansOfName("llm.chat").get(0).attributes().get(SpanAttrs.DEGRADED));
        assertEquals("qwen-plus", primary.requestedModels().get(0), "主模型用的是原模型名");
    }

    @Test
    @DisplayName("降级链按顺序尝试多个备选模型")
    void fallbackChainTriesModelsInOrder() {
        FlakyChatModel primary = new FlakyChatModel("primary", 1, FailureCategory.TIMEOUT, ChatResponse.text("x"));
        FlakyChatModel second = new FlakyChatModel("second", 1, FailureCategory.RATE_LIMIT, ChatResponse.text("x"));
        MockChatModel third = MockChatModel.builder("third").fallback(ChatResponse.text("【最终成功】")).build();

        FallbackChatModel model = new FallbackChatModel(primary, List.of(second, third));
        ChatResponse response = model.chat(REQUEST);
        assertEquals("【最终成功】", response.content());
        assertEquals(1, model.degradations().size());
        assertEquals("third", model.degradations().get(0).toModel());
        assertEquals(List.of("primary", "second", "third"), model.chainNames());
    }

    @Test
    @DisplayName("全部模型失败 → 返回结构化错误响应（不抛异常）")
    void allModelsFailReturnsStructuredError() {
        FlakyChatModel primary = new FlakyChatModel("primary", 99, FailureCategory.SERVER_ERROR, ChatResponse.text("x"));
        FlakyChatModel backup = new FlakyChatModel("backup", 99, FailureCategory.SERVER_ERROR, ChatResponse.text("x"));

        FallbackChatModel model = new FallbackChatModel(primary, List.of(backup));
        ChatResponse response = model.chat(REQUEST);
        assertEquals(StopReason.ERROR, response.stopReason());
        assertTrue(response.content().contains("SERVER_ERROR"), "错误内容应带失败分类");
        assertEquals(2, primary.callCount() + backup.callCount());
    }

    @Test
    @DisplayName("主模型成功时不会触发降级")
    void successPathDoesNotDegrade() {
        MockChatModel primary = MockChatModel.builder("primary").fallback(ChatResponse.text("【成功】")).build();
        MockChatModel backup = MockChatModel.builder("backup").fallback(ChatResponse.text("【不该被调用】")).build();

        FallbackChatModel model = new FallbackChatModel(primary, List.of(backup));
        ChatResponse response = model.chat(REQUEST);
        assertEquals("【成功】", response.content());
        assertTrue(model.degradations().isEmpty());
        assertEquals(0, backup.callCount());
        assertFalse(response.stopReason() == StopReason.ERROR);
    }
}
