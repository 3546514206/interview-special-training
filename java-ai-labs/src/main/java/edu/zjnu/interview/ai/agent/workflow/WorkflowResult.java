package edu.zjnu.interview.ai.agent.workflow;

import java.util.List;

/**
 * 工作流执行结果。
 *
 * @param workflowName   工作流名
 * @param status         执行状态
 * @param executions     每个节点的执行明细
 * @param output         最终产出（最后一个成功节点的输出）
 * @param failureReason  失败原因
 * @param traceId        链路 id
 * @param durationMillis 总耗时
 */
public record WorkflowResult(String workflowName,
                             Status status,
                             List<NodeExecution> executions,
                             Object output,
                             String failureReason,
                             String traceId,
                             long durationMillis) {

    public WorkflowResult {
        status = status == null ? Status.FAILED : status;
        executions = executions == null ? List.of() : List.copyOf(executions);
        failureReason = failureReason == null ? "" : failureReason;
    }

    /** 工作流状态。 */
    public enum Status {
        /** 正常跑完（走到没有出边的节点）。 */
        SUCCESS,
        /** 某个节点最终失败（重试耗尽）。 */
        FAILED,
        /** 超过最大节点执行次数，被熔断。 */
        ABORTED
    }

    /** 是否成功。 */
    public boolean success() {
        return status == Status.SUCCESS;
    }

    /** 节点执行明细。 */
    public record NodeExecution(String node,
                                int attempts,
                                boolean success,
                                Object value,
                                String error,
                                long durationMillis) {
    }
}
