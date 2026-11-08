package edu.zjnu.interview.ai.agent.multi;

import edu.zjnu.interview.ai.core.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 确定性路由器：按关键词匹配把任务派给最合适的专家。
 *
 * <p><b>为什么先做它</b>：模型路由（让 LLM 决定派给谁）效果更好，但不可复现、不好测、
 * 每次多花一次模型调用。工程上正确的顺序是：
 * <b>先用确定性路由覆盖 80% 的场景，把模型路由留给真正模糊的长尾</b>。
 *
 * <p>本实现同时承担「是否再委派一轮」的决策：
 * <ul>
 *   <li>第一轮：按关键词打分选专家；</li>
 *   <li>后续轮：只有当上一轮<b>有失败</b>的委派时才重试（换个专家或换种子任务）；
 *       全都成功就收尾——<b>否则 Supervisor 会无限自我委派，烧光预算</b>。</li>
 * </ul>
 */
public final class KeywordPlanner implements Planner {

    private final boolean retryFailed;

    public KeywordPlanner() {
        this(true);
    }

    public KeywordPlanner(boolean retryFailed) {
        this.retryFailed = retryFailed;
    }

    @Override
    public Plan plan(SupervisorContext context) {
        List<SpecialistAgent> specialists = context.specialists();
        if (specialists.isEmpty()) {
            return Plan.empty("没有可用专家");
        }
        if (context.round() > 1) {
            if (!retryFailed || !context.hasFailure()) {
                return Plan.empty("上一轮委派均成功，无需再次委派");
            }
            // 重试：优先重派给上一轮失败的专家
            for (SupervisorAgent.Delegation delegation : context.collected()) {
                if (!delegation.success()) {
                    String retryTask = "上一次的回答未通过校验，请换一种方式重新完成以下任务：\n"
                            + context.task();
                    return new Plan(List.of(new Planner.Assignment(delegation.specialist(), retryTask)),
                            "上一轮委派失败，重新委派给 " + delegation.specialist());
                }
            }
            return Plan.empty("没有可重试的委派");
        }

        SpecialistAgent best = null;
        int bestScore = 0;
        List<Planner.Assignment> assignments = new ArrayList<>();
        for (SpecialistAgent specialist : specialists) {
            int score = score(context.task(), specialist);
            if (score > bestScore) {
                bestScore = score;
                best = specialist;
            }
            if (score > 0) {
                // 命中即委派：任务可能天然需要多个专家（如「查资料并计算」）
                assignments.add(new Planner.Assignment(specialist.name(), context.task()));
            }
        }
        if (!assignments.isEmpty()) {
            return new Plan(assignments, "关键词命中，派给 " + assignments.size() + " 位专家");
        }
        if (best == null) {
            // 没有命中任何关键词：派给通用专家（否则派给第一个专家）
            best = specialists.stream().filter(SpecialistAgent::generalist).findFirst()
                    .orElse(specialists.get(0));
            return new Plan(List.of(new Planner.Assignment(best.name(), context.task())),
                    "未命中关键词，派给兜底专家 " + best.name());
        }
        return new Plan(List.of(new Planner.Assignment(best.name(), context.task())),
                "关键词命中 " + bestScore + " 个，派给 " + best.name());
    }

    /** 关键词命中数。 */
    private int score(String task, SpecialistAgent specialist) {
        if (task == null) {
            return 0;
        }
        String lower = task.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : specialist.keywords()) {
            if (keyword != null && !keyword.isBlank() && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                score++;
            }
        }
        // 角色描述里出现任务关键词也算弱信号
        if (score == 0 && Text.coverage(task, specialist.role()) > 0.2) {
            score = 1;
        }
        return score;
    }
}
