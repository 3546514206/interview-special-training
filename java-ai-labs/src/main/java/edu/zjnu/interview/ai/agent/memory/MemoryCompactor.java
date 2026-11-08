package edu.zjnu.interview.ai.agent.memory;

import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.Text;
import edu.zjnu.interview.ai.core.TokenCounter;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆压缩器：把最早的若干轮对话压成一段摘要。
 *
 * <p><b>为什么需要它</b>：Agent Loop 会不断往上下文里塞「工具结果」，
 * 几轮之后上下文就会超出模型窗口或者预算上限。
 * 三种常见做法里——直接截断（丢最早的对话）、只留最后 N 条（丢掉中间的重要结论）、
 * <b>压缩成摘要</b>——第三种的信息保真度最高，所以它是默认策略。
 *
 * <p><b>降级策略</b>：如果摘要模型不可用（没配模型、模型抛异常、返回空），
 * 就退化为「截断式摘要」——保留早期文本的前若干字符作为占位摘要。
 * 这一点很重要：<b>压缩本身不能成为新的失败点</b>，
 * 否则上下文一长，Agent 就因为「压缩失败」而整体挂掉，这是很常见的生产事故。
 *
 * <p>本类不持有任何状态，压缩结果由调用方（{@link ConversationState}）持有，
 * 这样压缩策略可以替换（换模型、换摘要风格）而不影响会话状态机。
 */
public final class MemoryCompactor {

    /** 摘要消息的前缀：让模型明确知道「这段是历史摘要，不是新指令」。 */
    public static final String SUMMARY_PREFIX = "【历史对话摘要（自动压缩）】";

    private static final String DEFAULT_INSTRUCTION =
            "你是对话压缩器。请把下面的早期对话压缩成不超过 150 字的要点摘要："
                    + "保留事实、结论、数字与未完成事项，丢弃寒暄、重复与过程细节。只输出摘要正文。";

    private final ChatModel model;
    private final int keepRecent;
    private final int maxSummaryChars;

    /**
     * @param model           摘要模型；为 null 时退化为截断式摘要
     * @param keepRecent      最近保留多少条消息原文
     * @param maxSummaryChars 摘要最大字符数
     */
    public MemoryCompactor(ChatModel model, int keepRecent, int maxSummaryChars) {
        this.model = model;
        this.keepRecent = Math.max(1, keepRecent);
        this.maxSummaryChars = Math.max(80, maxSummaryChars);
    }

    public static MemoryCompactor of(ChatModel model, int keepRecent) {
        return new MemoryCompactor(model, keepRecent, 400);
    }

    /** 不使用模型（纯截断摘要）。 */
    public static MemoryCompactor truncationOnly(int keepRecent) {
        return new MemoryCompactor(null, keepRecent, 400);
    }

    public boolean modelAvailable() {
        return model != null;
    }

    public int keepRecent() {
        return keepRecent;
    }

    /**
     * 压缩：把 {@code head} 这批早期消息压成摘要文本。
     *
     * @param head            早期消息
     * @param previousSummary 上一轮摘要（可为空），会与新摘要合并
     * @return 压缩结果
     */
    public CompactionResult compact(List<ChatMessage> head, String previousSummary) {
        if (head == null || head.isEmpty()) {
            return new CompactionResult(previousSummary == null ? "" : previousSummary, false, 0);
        }
        String transcript = renderTranscript(head);
        boolean degraded = true;
        String summary = summarizeByTruncation(transcript, head.size());
        if (model != null) {
            try {
                ChatResponse response = model.chat(new ChatRequest(
                        List.of(ChatMessage.system(DEFAULT_INSTRUCTION), ChatMessage.user(transcript)),
                        List.of(), 0.0, 256, model.name()));
                String content = response == null ? "" : response.content();
                if (content != null && !content.isBlank()) {
                    summary = content.strip();
                    degraded = false;
                }
            } catch (Exception e) {
                // 模型不可用不是致命错误：退化为截断摘要，保证链路继续。
                summary = summarizeByTruncation(transcript, head.size());
                degraded = true;
            }
        }
        if (previousSummary != null && !previousSummary.isBlank()) {
            summary = previousSummary.strip() + " | " + summary;
        }
        if (summary.length() > maxSummaryChars) {
            summary = summary.substring(0, maxSummaryChars) + "…";
        }
        return new CompactionResult(summary, degraded, head.size());
    }

    /** 把消息渲染成可读的对话记录。 */
    public String renderTranscript(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            if (m == null) {
                continue;
            }
            String role = switch (m.role()) {
                case SYSTEM -> "SYSTEM";
                case USER -> "USER";
                case ASSISTANT -> "ASSISTANT";
                case TOOL -> "TOOL(" + (m.name() == null ? "?" : m.name()) + ")";
            };
            String content = m.content();
            if (content == null || content.isBlank()) {
                for (var call : m.toolCalls()) {
                    content = (content == null ? "" : content) + "[调用 " + call.name() + " " + call.argumentsJson() + "] ";
                }
            }
            sb.append(role).append(": ").append(content == null ? "" : content.strip()).append('\n');
        }
        return sb.toString();
    }

    /** 截断式摘要：模型不可用时的降级路径。 */
    private String summarizeByTruncation(String transcript, int messageCount) {
        String body = Text.truncate(transcript, Math.max(20, maxSummaryChars / 4));
        return "[已压缩 " + messageCount + " 条早期消息] " + body;
    }

    /** 估算一段对话的 token 数。 */
    public static int estimateTokens(List<ChatMessage> messages) {
        return TokenCounter.estimateMessages(messages);
    }

    /**
     * 压缩结果。
     *
     * @param summary      摘要正文
     * @param degraded     是否走了降级路径（模型不可用）
     * @param summarizedCount 被压缩掉的消息条数
     */
    public record CompactionResult(String summary, boolean degraded, int summarizedCount) {
    }

    /** 便于测试：查看摘要消息的完整形态。 */
    public static ChatMessage summaryMessage(String summary) {
        return ChatMessage.assistant(SUMMARY_PREFIX + summary);
    }

    /** 便于测试：把消息列表切成「早期 / 最近」两段。 */
    public static List<List<ChatMessage>> split(List<ChatMessage> messages, int keepRecent) {
        List<ChatMessage> all = new ArrayList<>(messages == null ? List.of() : messages);
        if (all.size() <= keepRecent) {
            return List.of(all, List.of());
        }
        int cut = all.size() - keepRecent;
        return List.of(List.copyOf(all.subList(0, cut)), List.copyOf(all.subList(cut, all.size())));
    }
}
