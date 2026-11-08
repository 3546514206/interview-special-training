package edu.zjnu.interview.ai.core;

/**
 * 用量与成本计量端口。
 *
 * <p>成本不是事后统计出来的报表，而是运行时的硬约束：
 * Agent Loop 每一步都要问「还能再花吗」。所以计量必须发生在调用路径上，
 * 而不是靠日志离线聚合。
 */
public interface UsageMeter {

    /** 记一次用量。 */
    void charge(String model, TokenUsage usage);

    /** 已产生的成本（美元）。 */
    double spentUsd();

    /** 已消耗的 token 总数。 */
    TokenUsage totalTokens();

    /** 不做任何统计。 */
    static UsageMeter noop() {
        return new UsageMeter() {
            @Override
            public void charge(String model, TokenUsage usage) {
            }

            @Override
            public double spentUsd() {
                return 0.0;
            }

            @Override
            public TokenUsage totalTokens() {
                return TokenUsage.ZERO;
            }
        };
    }
}
