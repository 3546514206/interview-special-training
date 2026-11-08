package edu.zjnu.interview.ai.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Token 估算与预算控制的约定测试。
 *
 * <p>预算是运行时硬约束，估算是它的地基：估算偏小会导致实际超限，
 * 估算偏大则浪费预算，因此至少要保证「量级正确、同量级可比」。
 */
class TokenCounterTest {

    @Test
    @DisplayName("汉字按 1 token 估算")
    void chineseIsOneTokenPerChar() {
        assertEquals(6, TokenCounter.estimate("私募资产接入"));
    }

    @Test
    @DisplayName("英文按约 4 字符 1 token 估算")
    void latinIsFourCharsPerToken() {
        assertEquals(2, TokenCounter.estimate("abcdefgh"));
    }

    @Test
    @DisplayName("空文本为零 token")
    void emptyIsZero() {
        assertEquals(0, TokenCounter.estimate(""));
        assertEquals(0, TokenCounter.estimate(null));
    }

    @Test
    @DisplayName("消息列表估算包含每条消息的固定开销")
    void messagesIncludeOverhead() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("你是私募资产接入助手"),
                ChatMessage.user("准入标准是什么"));

        int tokens = TokenCounter.estimateMessages(messages);

        assertEquals(TokenCounter.estimate("你是私募资产接入助手")
                + TokenCounter.estimate("准入标准是什么")
                + 2 * TokenCounter.MESSAGE_OVERHEAD, tokens);
        assertTrue(tokens > 0);
    }
}
