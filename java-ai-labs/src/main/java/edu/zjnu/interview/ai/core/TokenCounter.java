package edu.zjnu.interview.ai.core;

import java.util.List;

/**
 * Token 估算器。
 *
 * <p>没有 tokenizer 依赖时，用「汉字按 1 token / 拉丁字符按 4 字符 1 token」估算，
 * 对中英混排的工程决策（截断、预算、上下文打包）误差可接受。
 *
 * <p>注意：真实 tokenizer（bge / cl100k 等）结果会不同，
 * 因此生产系统应当在预算层留 10%~15% 余量，而不是把估算值当成精确值用。
 */
public final class TokenCounter {

    /** 每条消息的固定开销（role / 分隔符等）。 */
    public static final int MESSAGE_OVERHEAD = 4;

    private TokenCounter() {
    }

    /** 估算一段文本的 token 数。 */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Text.isCjk(c)) {
                cjk++;
            } else if (!Character.isWhitespace(c)) {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    /** 估算一组消息的 token 数（含消息开销）。 */
    public static int estimateMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ChatMessage m : messages) {
            total += estimate(m.content()) + MESSAGE_OVERHEAD;
            for (ToolCall call : m.toolCalls()) {
                total += estimate(call.name()) + estimate(call.argumentsJson());
            }
        }
        return total;
    }
}
