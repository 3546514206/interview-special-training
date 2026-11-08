package edu.zjnu.interview.ai.agent.multi;

import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.Json;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型路由器：让 LLM 决定把任务派给谁。
 *
 * <p><b>它必须能降级</b>：模型可能返回非法 JSON、编造不存在的专家名、或者干脆不返回内容。
 * 所以本类的策略是「<b>解析成功且专家名合法 → 用模型的判断；否则退回关键词路由</b>」。
 * 一个会因为模型输出不合法就整条链路挂掉的路由器，在生产上是不合格的。
 *
 * <p>输出协议用 JSON 而不是自然语言，是因为结构化输出可以被程序校验：
 * 「专家名必须在白名单里」这种约束，用自由文本根本没法保证。
 */
public final class ModelPlanner implements Planner {

    private static final String INSTRUCTION =
            "你是任务分派器。请把任务分派给最合适的专家。"
                    + "只输出 JSON，格式：{\"assignments\":[{\"specialist\":\"<专家名>\",\"task\":\"<子任务>\"}]}。"
                    + "如果没有合适专家，输出 {\"assignments\":[]}。不要输出解释文字。";

    private final ChatModel model;
    private final Planner fallback;

    public ModelPlanner(ChatModel model, Planner fallback) {
        this.model = model;
        this.fallback = fallback == null ? new KeywordPlanner() : fallback;
    }

    @Override
    public Plan plan(SupervisorContext context) {
        if (model == null) {
            return fallback.plan(context);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("任务：").append(context.task()).append('\n');
        sb.append("可用专家：\n");
        for (SpecialistAgent specialist : context.specialists()) {
            sb.append("- ").append(specialist.name()).append("：").append(specialist.role())
                    .append("（擅长：").append(String.join("、", specialist.keywords())).append("）\n");
        }
        if (!context.collected().isEmpty()) {
            sb.append("已获得的结论：\n");
            for (SupervisorAgent.Delegation delegation : context.collected()) {
                sb.append("- [").append(delegation.specialist()).append("] ")
                        .append(delegation.answer()).append('\n');
            }
            sb.append("若信息已足够，请输出空的 assignments。\n");
        }

        String content;
        try {
            ChatResponse response = model.chat(new ChatRequest(
                    List.of(ChatMessage.system(INSTRUCTION), ChatMessage.user(sb.toString())),
                    List.of(), 0.0, 512, model.name()));
            content = response == null ? "" : response.content();
        } catch (Exception e) {
            return fallback.plan(context);
        }
        if (content == null || content.isBlank()) {
            return fallback.plan(context);
        }

        Plan parsed = parse(content, context);
        return parsed == null ? fallback.plan(context) : parsed;
    }

    /** 解析模型输出；失败返回 null（交给降级路径）。 */
    @SuppressWarnings("unchecked")
    private Plan parse(String content, SupervisorContext context) {
        java.util.Map<String, Object> root = Json.readMap(content);
        if (root.isEmpty()) {
            return null;
        }
        Object rawAssignments = root.get("assignments");
        if (!(rawAssignments instanceof List<?> list) || list.isEmpty()) {
            return Plan.empty("模型判定无需再委派");
        }
        Set<String> valid = new LinkedHashSet<>();
        for (SpecialistAgent specialist : context.specialists()) {
            valid.add(specialist.name());
        }
        List<Planner.Assignment> assignments = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof java.util.Map<?, ?> map)) {
                continue;
            }
            Object name = map.get("specialist");
            Object task = map.get("task");
            if (name == null) {
                continue;
            }
            String specialistName = String.valueOf(name);
            if (!valid.contains(specialistName)) {
                // 模型编造了不存在的专家：忽略这一条，而不是整个计划作废
                continue;
            }
            assignments.add(new Planner.Assignment(specialistName,
                    task == null ? context.task() : String.valueOf(task)));
        }
        if (assignments.isEmpty()) {
            // 模型给出了白名单之外的专家名：这份计划不可用，退回确定性路由
            return null;
        }
        return new Plan(assignments, "模型路由");
    }
}
