package edu.zjnu.interview.ai.harness;

/**
 * 用量快照：某一时刻「已经花了多少」。预算判定需要一个可比较的标量视图。
 *
 * <p>为什么不直接传 {@code CostMeter}：请求级预算关心的是「这一次请求花了多少」，
 * 它和会话级账本是两套数字。抽成不可变的快照后，
 * 同一个 {@link BudgetPolicy} 既能判会话预算，也能判请求预算。
 *
 * @param spentUsd 已花费（美元）
 * @param tokens   已消耗 token 合计
 */
public record UsageSnapshot(double spentUsd, long tokens) {

    public static final UsageSnapshot ZERO = new UsageSnapshot(0.0, 0L);

    public static UsageSnapshot of(double spentUsd, long tokens) {
        return new UsageSnapshot(spentUsd, tokens);
    }

    /** 用量占比（相对上限）。上限为 0（不限制）时返回 0。 */
    public double ratio(double usdLimit, long tokenLimit) {
        double usdRatio = usdLimit > 0 ? spentUsd / usdLimit : 0.0;
        double tokenRatio = tokenLimit > 0 ? (double) tokens / tokenLimit : 0.0;
        return Math.max(usdRatio, tokenRatio);
    }
}
