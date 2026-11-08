package edu.zjnu.interview.ai.agent.memory;

import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.TokenCounter;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话状态：Agent Loop 的「短期记忆」。
 *
 * <p>它管理三样东西：
 * <ol>
 *   <li><b>消息列表</b>：系统提示 + 用户输入 + 模型回复 + 工具结果；</li>
 *   <li><b>token 预算</b>：超预算时触发压缩；</li>
 *   <li><b>压缩摘要</b>：被压缩掉的早期对话以摘要形式保留。</li>
 * </ol>
 *
 * <p><b>关键设计：摘要与原文分开存放</b>。
 * 如果把摘要当成一条普通消息塞回列表，下一次压缩就会把「上一次的摘要」再摘要一遍，
 * 出现摘要套摘要、上下文越压越涨的经典 bug。
 * 这里把摘要单独放在 {@code summary} 字段里，原文列表只保存「还没被压缩的最近若干条」，
 * 压缩是<b>单向且幂等</b>的。
 *
 * <p>本类线程不安全（Agent Loop 是单线程顺序执行的刻意设计：LLM 调用天然是串行的，
 * 引入并发会让「模型-工具-回灌」的时序变得不可推理）。
 */
public final class ConversationState {

    /** 不限制 token 预算。 */
    public static final int UNLIMITED = -1;

    private final String systemPrompt;
    private final int tokenBudget;
    private final MemoryCompactor compactor;
    private final List<ChatMessage> messages = new ArrayList<>();

    private String summary = "";
    private int compactionCount;
    private int compactedMessageCount;

    public ConversationState(String systemPrompt, int tokenBudget, MemoryCompactor compactor) {
        this.systemPrompt = systemPrompt;
        this.tokenBudget = tokenBudget;
        this.compactor = compactor;
    }

    public static ConversationState of(String systemPrompt) {
        return new ConversationState(systemPrompt, UNLIMITED, null);
    }

    public static ConversationState of(String systemPrompt, int tokenBudget, MemoryCompactor compactor) {
        return new ConversationState(systemPrompt, tokenBudget, compactor);
    }

    /** 追加一条消息。 */
    public ConversationState add(ChatMessage message) {
        if (message != null) {
            messages.add(message);
        }
        return this;
    }

    public ConversationState addUser(String text) {
        return add(ChatMessage.user(text));
    }

    public ConversationState addAssistant(String text) {
        return add(ChatMessage.assistant(text));
    }

    public ConversationState addAssistantToolCalls(List<edu.zjnu.interview.ai.core.ToolCall> calls) {
        return add(ChatMessage.assistantToolCalls(calls));
    }

    public ConversationState addToolResult(String callId, String toolName, String content) {
        return add(ChatMessage.toolResult(callId, toolName, content));
    }

    /** 未压缩的原始消息（不含系统提示与摘要）。 */
    public List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    /** 完整上下文：系统提示 + 摘要 + 未压缩消息。 */
    public List<ChatMessage> context() {
        ensureWithinBudget();
        return rawContext();
    }

    /** 完整上下文快照（不触发压缩），用于统计与调试。 */
    public List<ChatMessage> rawContext() {
        List<ChatMessage> out = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            out.add(ChatMessage.system(systemPrompt));
        }
        if (summary != null && !summary.isBlank()) {
            out.add(MemoryCompactor.summaryMessage(summary));
        }
        out.addAll(messages);
        return List.copyOf(out);
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public int tokenBudget() {
        return tokenBudget;
    }

    public String summary() {
        return summary;
    }

    /** 触发过几次压缩。 */
    public int compactionCount() {
        return compactionCount;
    }

    /** 一共被压缩掉多少条消息。 */
    public int compactedMessageCount() {
        return compactedMessageCount;
    }

    /** 当前上下文的估算 token 数。 */
    public int estimatedTokens() {
        return TokenCounter.estimateMessages(rawContext());
    }

    public boolean overBudget() {
        return tokenBudget > 0 && estimatedTokens() > tokenBudget;
    }

    /**
     * 超预算时压缩最早的消息。
     *
     * <p>返回 true 表示本次真的做了压缩。压缩是不可用时也安全：
     * 没有配置压缩器时直接返回 false（保持超预算状态，由上层预算熔断兜住）。
     */
    public boolean ensureWithinBudget() {
        if (compactor == null || !overBudget()) {
            return false;
        }
        int keep = Math.max(1, compactor.keepRecent());
        if (messages.size() <= keep) {
            // 只剩最近若干条还超预算：再压也压不动了，交给上层预算熔断处理。
            return false;
        }
        int cut = messages.size() - keep;
        List<ChatMessage> head = List.copyOf(messages.subList(0, cut));
        List<ChatMessage> tail = List.copyOf(messages.subList(cut, messages.size()));
        MemoryCompactor.CompactionResult result = compactor.compact(head, summary);
        summary = result.summary();
        messages.clear();
        messages.addAll(tail);
        compactionCount++;
        compactedMessageCount += result.summarizedCount();
        return true;
    }

    /** 清空消息（保留系统提示与压缩配置）。 */
    public void clear() {
        messages.clear();
        summary = "";
        compactionCount = 0;
        compactedMessageCount = 0;
    }
}
