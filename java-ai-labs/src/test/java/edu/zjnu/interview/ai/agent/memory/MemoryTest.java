package edu.zjnu.interview.ai.agent.memory;

import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态与记忆：上下文压缩、长期笔记、事实记忆。
 */
class MemoryTest {

    /** 可推进的时钟，用于验证 TTL。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Test
    @DisplayName("超预算时自动压缩：token 下降、摘要保留、最近消息逐字保留")
    void compressesWhenOverBudget() {
        ConversationState state = ConversationState.of("你是投顾助手", 200,
                MemoryCompactor.of(new edu.zjnu.interview.ai.agent.lab.ScriptedChatModel("summarizer",
                        List.of(request -> ChatResponse.text("摘要：用户在问费率与优惠"))), 3));
        for (int i = 0; i < 8; i++) {
            state.addUser("第 " + i + " 个问题：费率是多少");
            state.addAssistant("第 " + i + " 个回答：1.5%");
        }
        int before = state.estimatedTokens();
        assertTrue(before > 200, "压缩前应超过预算");

        List<ChatMessage> context = state.context();
        int after = state.estimatedTokens();

        assertTrue(after < before, "压缩后 token 应下降");
        assertTrue(after <= 200, "压缩后应回到预算内：" + after);
        assertEquals(1, state.compactionCount());
        assertTrue(state.compactedMessageCount() > 0);
        assertTrue(state.summary().contains("摘要"));
        // 最近的消息逐字保留
        assertEquals("第 7 个回答：1.5%", context.get(context.size() - 1).content());
    }

    @Test
    @DisplayName("模型不可用时压缩降级为截断，不抛异常")
    void compactionDegradesGracefully() {
        MemoryCompactor compactor = MemoryCompactor.truncationOnly(2);
        assertFalse(compactor.modelAvailable());

        ConversationState state = ConversationState.of("sys", 60, compactor);
        for (int i = 0; i < 6; i++) {
            state.addUser("问题 " + i);
            state.addAssistant("回答 " + i);
        }
        List<ChatMessage> context = state.context();

        assertTrue(state.compactionCount() > 0);
        assertTrue(state.summary().contains("已压缩"));
        assertTrue(context.size() <= 5);
    }

    @Test
    @DisplayName("摘要与原文分开存放：连续压缩不会产生摘要套摘要")
    void summaryIsNotNested() {
        ConversationState state = ConversationState.of("sys", 100,
                MemoryCompactor.truncationOnly(1));
        for (int i = 0; i < 20; i++) {
            state.addUser("问题 " + i);
            state.addAssistant("回答 " + i);
        }
        state.context();
        int afterFirst = state.compactionCount();
        state.addUser("再来一个问题");
        state.addAssistant("再来一个回答");
        state.context();

        assertEquals(afterFirst + 1, state.compactionCount());
        // 摘要消息在上下文里只出现一条
        long summaryMessages = state.context().stream()
                .filter(m -> m.content().contains(MemoryCompactor.SUMMARY_PREFIX))
                .count();
        assertEquals(1, summaryMessages);
    }

    @Test
    @DisplayName("Scratchpad：JSONL 追加持久化，目录自动创建")
    void scratchpadAppendsJsonl(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nested/notes/scratchpad.jsonl");
        Scratchpad scratchpad = new Scratchpad(file,
                Clock.fixed(Instant.parse("2026-01-15T10:30:00Z"), ZoneOffset.UTC));

        scratchpad.append("session", "exp-1");
        scratchpad.append("注意：这是一条纯文本笔记");

        assertTrue(Files.isRegularFile(file), "文件（含父目录）应被自动创建");
        List<Scratchpad.Entry> entries = scratchpad.readAll();
        assertEquals(2, entries.size());
        assertEquals("session", entries.get(0).key());
        assertEquals("exp-1", entries.get(0).value());
        assertEquals(Instant.parse("2026-01-15T10:30:00Z"), entries.get(0).timestamp());
        assertTrue(entries.get(1).note().contains("纯文本"));
        // 每行都是合法 JSON
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("{"));
    }

    @Test
    @DisplayName("Scratchpad：损坏行被跳过，不影响其它记录")
    void scratchpadSkipsBrokenLines(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("scratchpad.jsonl");
        Files.writeString(file, "{\"ts\":\"2026-01-15T10:30:00Z\",\"note\":\"好的\"}\n坏行\n", StandardCharsets.UTF_8);

        Scratchpad scratchpad = new Scratchpad(file);

        assertEquals(1, scratchpad.readAll().size());
    }

    @Test
    @DisplayName("MemoryStore：scope 隔离")
    void memoryStoreIsolatesScopes() {
        MemoryStore store = new MemoryStore(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        store.put("偏好", "简洁", MemoryStore.Scope.SESSION);
        store.put("偏好", "详细", MemoryStore.Scope.USER);

        assertEquals("简洁", store.get("偏好", MemoryStore.Scope.SESSION).orElseThrow().value());
        assertEquals("详细", store.get("偏好", MemoryStore.Scope.USER).orElseThrow().value());
        assertEquals(1, store.all(MemoryStore.Scope.SESSION).size());
        assertEquals(1, store.all(MemoryStore.Scope.USER).size());
    }

    @Test
    @DisplayName("MemoryStore：TTL 到期后事实失效")
    void memoryStoreHonoursTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-15T00:00:00Z"));
        MemoryStore store = new MemoryStore(clock);
        store.put("临时事实", "只活 1 小时", MemoryStore.Scope.SESSION, Duration.ofHours(1));

        assertTrue(store.get("临时事实", MemoryStore.Scope.SESSION).isPresent());
        clock.advance(Duration.ofHours(2));
        assertTrue(store.get("临时事实", MemoryStore.Scope.SESSION).isEmpty());
    }

    @Test
    @DisplayName("MemoryStore：模糊匹配（措辞不同也能召回）")
    void memoryStoreFindsByFuzzyMatch() {
        MemoryStore store = new MemoryStore(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        store.put("用户风险等级", "R3 稳健型", MemoryStore.Scope.USER);
        store.put("通知方式", "邮件", MemoryStore.Scope.USER);

        List<MemoryStore.Fact> hits = store.find("风险等级", MemoryStore.Scope.USER);

        assertFalse(hits.isEmpty());
        assertEquals("用户风险等级", hits.get(0).key());
        assertTrue(hits.get(0).score() > 0);
    }

    @Test
    @DisplayName("ConversationState：未超预算时不做任何压缩")
    void doesNotCompactWithinBudget() {
        ConversationState state = ConversationState.of("sys", 10_000,
                MemoryCompactor.truncationOnly(2));
        state.addUser("你好");

        state.context();

        assertEquals(0, state.compactionCount());
        assertEquals(1, state.messages().size());
    }

    @Test
    @DisplayName("摘要消息使用 assistant 角色，不在对话中途插入新的系统指令")
    void summaryUsesAssistantRole() {
        ConversationState state = ConversationState.of("sys", 100,
                MemoryCompactor.truncationOnly(1));
        for (int i = 0; i < 10; i++) {
            state.addUser("问题 " + i);
            state.addAssistant("回答 " + i);
        }
        List<ChatMessage> context = state.context();

        ChatMessage summary = context.stream()
                .filter(m -> m.content().contains(MemoryCompactor.SUMMARY_PREFIX))
                .findFirst().orElseThrow();
        assertEquals(ChatMessage.Role.ASSISTANT, summary.role());
        assertEquals(ChatMessage.Role.SYSTEM, context.get(0).role());
    }

    @Test
    @DisplayName("压缩器渲染对话记录时保留角色信息")
    void rendersTranscriptWithRoles() {
        MemoryCompactor compactor = MemoryCompactor.truncationOnly(2);
        String transcript = compactor.renderTranscript(List.of(
                ChatMessage.user("问题"),
                ChatMessage.assistant("回答")));

        assertTrue(transcript.contains("USER: 问题"));
        assertTrue(transcript.contains("ASSISTANT: 回答"));
    }

    @Test
    @DisplayName("摘要模型异常时降级而不是抛出")
    void summarizerFailureDegrades() {
        edu.zjnu.interview.ai.core.ChatModel broken = new edu.zjnu.interview.ai.agent.lab.ScriptedChatModel(
                "broken", List.of(request -> {
                    throw new IllegalStateException("模型挂了");
                }));
        MemoryCompactor compactor = MemoryCompactor.of(broken, 2);
        MemoryCompactor.CompactionResult result = compactor.compact(
                List.of(ChatMessage.user("问题"), ChatMessage.assistant("回答")), null);

        assertTrue(result.degraded());
        assertTrue(result.summary().contains("已压缩"));
    }

    @Test
    @DisplayName("摘要模型可用时不降级")
    void summarizerSucceeds() {
        edu.zjnu.interview.ai.core.ChatModel ok = new edu.zjnu.interview.ai.agent.lab.ScriptedChatModel(
                "ok", List.of(request -> ChatResponse.text("要点摘要")));
        MemoryCompactor compactor = MemoryCompactor.of(ok, 2);
        MemoryCompactor.CompactionResult result = compactor.compact(
                List.of(ChatMessage.user("问题"), ChatMessage.assistant("回答")), null);

        assertFalse(result.degraded());
        assertEquals("要点摘要", result.summary());
        assertEquals(2, result.summarizedCount());
    }
}
