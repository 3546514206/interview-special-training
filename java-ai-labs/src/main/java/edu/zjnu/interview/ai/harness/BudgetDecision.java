package edu.zjnu.interview.ai.harness;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 预算判定结果。
 *
 * <p><b>三态而不是两态</b>：只区分「允许 / 拒绝」是不够的。
 * 真实系统里绝大多数情况是「还能花，但该省着花了」——这时候正确动作是
 * <b>降级</b>（换便宜模型、缩短输出），而不是直接拒绝服务。
 * 只有真正越过硬阈值才拒绝，否则用户体验会断崖式下跌。
 *
 * @param action         判定动作
 * @param effectiveModel 实际应当使用的模型（降级时是便宜模型）
 * @param reason         判定理由（人类可读）
 * @param spentUsd       判定时已花费（美元）
 * @param usdLimit       美元预算上限（0 表示不限）
 * @param spentTokens    判定时已消耗 token
 * @param tokenLimit     token 上限（0 表示不限）
 * @param degradation    降级事件（未降级时为 null）
 */
public record BudgetDecision(BudgetPolicy.Action action,
                             String effectiveModel,
                             String reason,
                             double spentUsd,
                             double usdLimit,
                             long spentTokens,
                             long tokenLimit,
                             DegradationEvent degradation) {

    public BudgetDecision {
        if (action == null) {
            action = BudgetPolicy.Action.ALLOW;
        }
    }

    /** 放行（不降级）。 */
    public static BudgetDecision allow(String model, String reason, UsageSnapshot usage,
                                       double usdLimit, long tokenLimit) {
        return new BudgetDecision(BudgetPolicy.Action.ALLOW, model, reason,
                usage.spentUsd(), usdLimit, usage.tokens(), tokenLimit, null);
    }

    /** 降级到便宜模型。 */
    public static BudgetDecision degrade(String fromModel, String toModel, String reason, UsageSnapshot usage,
                                          double usdLimit, long tokenLimit, DegradationEvent event) {
        return new BudgetDecision(BudgetPolicy.Action.DEGRADE, toModel, reason,
                usage.spentUsd(), usdLimit, usage.tokens(), tokenLimit, event);
    }

    /** 拒绝（结构化错误）。 */
    public static BudgetDecision reject(String model, String reason, UsageSnapshot usage,
                                         double usdLimit, long tokenLimit) {
        return new BudgetDecision(BudgetPolicy.Action.REJECT, model, reason,
                usage.spentUsd(), usdLimit, usage.tokens(), tokenLimit, null);
    }

    /** 是否允许继续调用（放行或降级都算允许）。 */
    public boolean allowed() {
        return action != BudgetPolicy.Action.REJECT;
    }

    /** 是否发生了降级。 */
    public boolean degraded() {
        return action == BudgetPolicy.Action.DEGRADE;
    }

    /** 是否被拒绝。 */
    public boolean rejected() {
        return action == BudgetPolicy.Action.REJECT;
    }

    /**
     * 结构化错误载荷（拒绝时返回给调用方 / 回灌给模型）。
     *
     * <p>刻意做成机器可读：上层 Agent 可以据此决定「换个更省的策略重试」
     * 或「直接告诉用户预算不足」，而不是去解析一段自然语言。
     */
    public Map<String, Object> toErrorPayload() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error_code", "BUDGET_EXCEEDED");
        map.put("reason", reason);
        map.put("action", action.name());
        map.put("spent_usd", spentUsd);
        map.put("limit_usd", usdLimit);
        map.put("spent_tokens", spentTokens);
        map.put("token_limit", tokenLimit);
        return map;
    }

    /** 文本描述（演示用）。 */
    public String describe() {
        return String.format("[%s] model=%s spent=$%.6f/%.6f tokens=%d/%d reason=%s",
                action, effectiveModel, spentUsd, usdLimit, spentTokens, tokenLimit, reason);
    }
}
