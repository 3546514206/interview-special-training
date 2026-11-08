package edu.zjnu.interview.ai.harness;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 降级事件：一次「本来要花大钱 / 用大模型，最后改用便宜方案」的结构化记录。
 *
 * <p><b>为什么要把降级做成事件而不是日志行</b>：
 * 降级是「有代价的妥协」——用户拿到的是更弱的模型、更差的答案。
 * 这类事件必须能被机器统计（发生率、影响的请求数、省了多少钱），
 * 也必须能回灌给上层（告诉用户「本次已降级」）。
 * 一行普通日志是做不到这两件事的。
 *
 * @param fromModel  降级前的模型
 * @param toModel    降级后的模型
 * @param reason     原因描述
 * @param trigger    触发源：{@code budget_soft_threshold} / {@code fallback_model} / ...
 * @param spentUsd   触发时已花费（美元）
 * @param limitUsd   预算上限（美元），无预算时为 0
 * @param tokensUsed 触发时已消耗 token
 * @param tokenLimit token 上限，无限制时为 0
 */
public record DegradationEvent(String fromModel,
                               String toModel,
                               String reason,
                               String trigger,
                               double spentUsd,
                               double limitUsd,
                               long tokensUsed,
                               long tokenLimit) {

    /** 预算软阈值触发的降级。 */
    public static DegradationEvent budget(String fromModel, String toModel, String reason,
                                           double spentUsd, double limitUsd, long tokensUsed, long tokenLimit) {
        return new DegradationEvent(fromModel, toModel, reason, "budget_soft_threshold",
                spentUsd, limitUsd, tokensUsed, tokenLimit);
    }

    /** 主模型失败触发的降级。 */
    public static DegradationEvent fallback(String fromModel, String toModel, String reason) {
        return new DegradationEvent(fromModel, toModel, reason, "fallback_model",
                0.0, 0.0, 0L, 0L);
    }

    /** 结构化视图（写 span / 返回给调用方）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("from_model", fromModel);
        map.put("to_model", toModel);
        map.put("reason", reason);
        map.put("trigger", trigger);
        map.put("spent_usd", spentUsd);
        map.put("limit_usd", limitUsd);
        map.put("tokens_used", tokensUsed);
        map.put("token_limit", tokenLimit);
        return map;
    }
}
