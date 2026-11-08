package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.ToolCall;
import edu.zjnu.interview.ai.core.ToolContext;

/**
 * 审批回调：高风险工具（尤其是网络类）在执行前需要人（或策略）点头。
 *
 * <p><b>默认拒绝</b>是一个刻意的设计选择：
 * 「没人回复就等于同意」在安全上是错的。超时、无人值守、消息丢失都可能被误读成批准。
 * 所以 {@link #denyByDefault()} 是默认实现——拿不到明确批准，就不执行。
 *
 * <p>生产落地时通常有三种实现：
 * <ul>
 *   <li>人工审批（IM / 工单里点确认）；</li>
 *   <li>策略审批（白名单域名、低金额、只读接口自动放行，其余转人工）；</li>
 *   <li>无审批（离线实验环境，用 {@link #allowAll()}）。</li>
 * </ul>
 */
@FunctionalInterface
public interface ApprovalHandler {

    /**
     * 审批结果。
     *
     * @param approved 是否批准
     * @param reason   理由（拒绝时必须给出，会回灌给模型）
     */
    record Approval(boolean approved, String reason) {

        public static Approval granted() {
            return new Approval(true, "已批准");
        }

        public static Approval granted(String reason) {
            return new Approval(true, reason);
        }

        public static Approval refused(String reason) {
            return new Approval(false, reason);
        }
    }

    /**
     * 审批。
     *
     * @param toolName 工具名
     * @param call     模型发起的调用
     * @param ctx      执行上下文
     */
    Approval decide(String toolName, ToolCall call, ToolContext ctx);

    /** 默认拒绝（拿不到批准就不执行）。 */
    static ApprovalHandler denyByDefault() {
        return (toolName, call, ctx) -> Approval.refused("工具 " + toolName + " 需要审批，但未配置审批策略，默认拒绝");
    }

    /** 全部放行（仅离线实验环境使用）。 */
    static ApprovalHandler allowAll() {
        return (toolName, call, ctx) -> Approval.granted("实验环境：自动批准");
    }

    /** 全部拒绝并带统一理由。 */
    static ApprovalHandler denyAll(String reason) {
        return (toolName, call, ctx) -> Approval.refused(reason);
    }
}
