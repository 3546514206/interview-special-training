package edu.zjnu.interview.ai.core;

/**
 * Token 用量。所有成本控制（预算、降级、配额）都以它为唯一计量口径。
 *
 * @param promptTokens     输入（prompt）token 数
 * @param completionTokens 输出（completion）token 数
 */
public record TokenUsage(int promptTokens, int completionTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0);

    public static TokenUsage of(int promptTokens, int completionTokens) {
        return new TokenUsage(promptTokens, completionTokens);
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(promptTokens + other.promptTokens, completionTokens + other.completionTokens);
    }
}
