package edu.zjnu.interview.ai.agent.multi;

import java.util.List;

/**
 * 规划器看到的上下文。
 *
 * @param task        原始任务
 * @param round       当前轮次（从 1 开始）
 * @param collected   已收集到的委派结果
 * @param specialists 可用专家
 */
public record SupervisorContext(String task,
                                int round,
                                List<SupervisorAgent.Delegation> collected,
                                List<SpecialistAgent> specialists) {

    public SupervisorContext {
        collected = collected == null ? List.of() : List.copyOf(collected);
        specialists = specialists == null ? List.of() : List.copyOf(specialists);
    }

    /** 是否有失败的委派。 */
    public boolean hasFailure() {
        return collected.stream().anyMatch(d -> !d.success());
    }
}
