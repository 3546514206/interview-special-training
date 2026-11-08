package edu.zjnu.interview.ai.agent;

/**
 * Agent Loop 的终止原因。
 *
 * <p><b>为什么必须显式枚举终止原因</b>：Agent 的失败模式远多于普通接口——
 * 「模型不收尾」「预算烧完」「工具反复失败」「死循环」是四种完全不同的故障，
 * 需要不同的处置（改 prompt？加预算？修工具？加熔断？）。
 * 如果只返回一个字符串，线上排查时只能靠猜。
 * 枚举让「终止原因」成为可统计、可告警、可回归的指标。
 */
public enum TerminationReason {

    /** 模型给出了最终答案（正常收敛）。 */
    FINAL_ANSWER,
    /** 达到最大步数仍未收敛。 */
    MAX_STEPS,
    /** 预算（token 或成本）超限熔断。 */
    BUDGET_EXCEEDED,
    /** 同一工具与参数重复调用达到阈值，判定为死循环。 */
    LOOP_DETECTED,
    /** 连续工具失败次数达到阈值。 */
    CONSECUTIVE_TOOL_FAILURES,
    /** 模型调用异常。 */
    MODEL_ERROR,
    /** 模型输出被 max_tokens 截断（有内容但可能不完整）。 */
    MODEL_TRUNCATED,
    /** 触发内容安全过滤。 */
    CONTENT_FILTERED;

    /** 是否属于「失败」终止（正常收敛之外都算）。 */
    public boolean isFailure() {
        return this != FINAL_ANSWER;
    }
}
