package edu.zjnu.interview.ai.agent;

import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.TokenUsage;

import java.util.List;

/**
 * 一次 Agent 运行的结果。
 *
 * <p>Agent 的返回值不是一段字符串，而是<b>一份可复盘的运行档案</b>：
 * 最终答案 + 每一步明细 + 工具调用记录 + traceId + token 用量 + 终止原因 + 失败原因。
 *
 * <p>为什么失败原因要单独一个字段：终止原因（{@link TerminationReason}）是<b>分类</b>，
 * 失败原因是<b>具体解释</b>。分类用于聚合告警（「今天有 37 次 LOOP_DETECTED」），
 * 具体解释用于定位（「第 3 步用 calculator 计算 (1+2)*3 之后又调了 5 次同样的调用」）。
 * 只有分类没法定位，只有解释没法统计。
 *
 * @param finalAnswer      最终答案（未收敛时可能是空串）
 * @param steps            迭代明细
 * @param toolInvocations  工具调用记录
 * @param traceId          链路 id
 * @param usage            本次运行的 token 用量
 * @param terminationReason 终止原因
 * @param failureReason    失败原因（成功时为 null）
 * @param context          运行结束时的完整上下文快照
 */
public record AgentResult(String finalAnswer,
                          List<AgentStep> steps,
                          List<ToolInvocation> toolInvocations,
                          String traceId,
                          TokenUsage usage,
                          TerminationReason terminationReason,
                          String failureReason,
                          List<ChatMessage> context) {

    public AgentResult {
        finalAnswer = finalAnswer == null ? "" : finalAnswer;
        steps = steps == null ? List.of() : List.copyOf(steps);
        toolInvocations = toolInvocations == null ? List.of() : List.copyOf(toolInvocations);
        usage = usage == null ? TokenUsage.ZERO : usage;
        failureReason = failureReason == null ? "" : failureReason;
        context = context == null ? List.of() : List.copyOf(context);
    }

    /** 是否正常收敛。 */
    public boolean success() {
        return terminationReason == TerminationReason.FINAL_ANSWER;
    }

    /** 迭代轮数。 */
    public int stepCount() {
        return steps.size();
    }

    /** 工具调用次数。 */
    public int toolCallCount() {
        return toolInvocations.size();
    }

    /** 失败的工具调用次数。 */
    public long failedToolCallCount() {
        return toolInvocations.stream().filter(i -> !i.success()).count();
    }
}
