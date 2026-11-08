package edu.zjnu.interview.ai.agent.multi;

import java.util.List;

/**
 * 任务分派策略（Planner）：决定「这一轮派给谁、派什么」。
 *
 * <p><b>为什么把分派抽象成接口</b>：分派是 Supervisor 里最需要替换的部分。
 * 离线环境要<b>确定性</b>（关键词路由），生产环境要<b>语义路由</b>（模型判断）。
 * 把两者做成同一个接口的两种实现，Supervisor 的其余逻辑一行都不用改，
 * 而且两种实现可以单测——这正是 Agent 系统「可回归」的基础。
 *
 * <p>Planner 同时承担「<b>是否还要再委派一轮</b>」的决策：
 * 返回空计划 = 「信息够了，可以收尾」。
 */
public interface Planner {

    /**
     * 规划一轮委派。
     *
     * @param context 当前轮次、已收集的结果、可用专家
     * @return 委派计划（assignments 为空表示不需要再委派）
     */
    Plan plan(SupervisorContext context);

    /**
     * 一次委派。
     *
     * @param specialist 专家名
     * @param task       交给该专家的子任务
     */
    record Assignment(String specialist, String task) {
    }

    /**
     * 一轮的计划。
     *
     * @param assignments 委派列表
     * @param rationale   分派理由（写进 trace，便于审计「为什么派给这个人」）
     */
    record Plan(List<Assignment> assignments, String rationale) {

        public Plan {
            assignments = assignments == null ? List.of() : List.copyOf(assignments);
            rationale = rationale == null ? "" : rationale;
        }

        public static Plan empty(String rationale) {
            return new Plan(List.of(), rationale);
        }
    }
}
