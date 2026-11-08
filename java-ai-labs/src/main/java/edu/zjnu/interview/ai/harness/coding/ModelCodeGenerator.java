package edu.zjnu.interview.ai.harness.coding;

import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.core.StopReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 由真实模型驱动的代码生成器（AI Coding 的「生成」环节）。
 *
 * <p><b>设计要点：模型只负责「写」，验证完全交给流水线</b>。
 * 生成器不做任何自我判断——它不检查自己写的代码对不对，
 * 因为「模型自评」在工程上不可信。判断由
 * {@link CodingDeliveryPipeline} 的编译 + 执行 + 变异检验来做。
 *
 * <p><b>两处兜底</b>：
 * <ol>
 *   <li><b>抽取兜底</b>：模型输出里解析不出代码块 → 退回模板生成；</li>
 *   <li><b>编译兜底</b>：模型代码编译不过 → 流水线改用模板生成器重试。</li>
 * </ol>
 * 这正是「无人化交付」的核心含义：<b>不追求一次生成就对，而是保证最终交付物一定可验证</b>。
 *
 * <p>测试用例始终来自模板（契约固定），实现来自模型——
 * 这样「模型写得对不对」就变成了一个可以机械判定的问题。
 */
public final class ModelCodeGenerator implements CodeGenerator {

    private final ChatModel model;
    private final CodeGenerator fallback;

    public ModelCodeGenerator(ChatModel model, CodeGenerator fallback) {
        if (model == null) {
            throw new IllegalArgumentException("模型不能为空");
        }
        this.model = model;
        this.fallback = fallback == null ? new TemplateCodeGenerator() : fallback;
    }

    @Override
    public List<PlanPoint> plan(Requirement requirement) {
        String prompt = "需求：" + requirement.title() + "\n"
                + "描述：" + requirement.description() + "\n"
                + "验收标准：\n" + bullet(requirement.acceptanceCriteria()) + "\n"
                + "请给出 3~5 条实现要点。只输出 JSON 数组，"
                + "格式为 [{\"title\":\"...\",\"detail\":\"...\"}]，不要输出任何其它文字。";
        ChatResponse response = model.chat(ChatRequest.of(List.of(
                ChatMessage.system("你是资深 Java 工程师，输出必须严格遵守格式要求。"),
                ChatMessage.user(prompt))));
        List<PlanPoint> points = parsePlan(response);
        return points.isEmpty() ? fallback.plan(requirement) : points;
    }

    @Override
    public CodeArtifact generate(Requirement requirement) {
        CodeArtifact template = fallback.generate(requirement);
        String prompt = "请实现以下需求，只输出一个完整的 Java 源码文件，"
                + "不要输出解释、不要输出 markdown 以外的文字。\n"
                + "类名必须是 " + requirement.implementationClassName() + "。\n"
                + "必须提供两个 public static 方法：\n"
                + "  double simpleInterest(double principal, double annualRate, int days)\n"
                + "  double compoundInterest(double principal, double annualRate, int days)\n"
                + "参数为负时必须抛 IllegalArgumentException；返回金额保留两位小数。\n"
                + "需求：" + requirement.title() + "\n"
                + "验收标准：\n" + bullet(requirement.acceptanceCriteria());
        ChatResponse response = model.chat(ChatRequest.of(List.of(
                ChatMessage.system("你是资深 Java 工程师，只输出可编译的 Java 源码。"),
                ChatMessage.user(prompt))));
        String source = extractJava(response.content());
        if (source == null || !source.contains("class " + requirement.implementationClassName())) {
            // 模型没给出可用代码 → 退回模板
            return template;
        }
        String mutant = SourceMutator.mutate(source);
        return new CodeArtifact(requirement.implementationClassName(), source, mutant,
                requirement.testClassName(), template.testSource());
    }

    /** 从模型输出里抽取 Java 源码（支持 ```java 代码块与裸代码两种形态）。 */
    static String extractJava(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String text = content.strip();
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int afterFence = text.indexOf('\n', fence);
            if (afterFence < 0) {
                return null;
            }
            int end = text.indexOf("```", afterFence);
            if (end < 0) {
                return null;
            }
            text = text.substring(afterFence + 1, end).strip();
        }
        int classIndex = text.indexOf("class ");
        if (classIndex < 0) {
            return null;
        }
        // 往前吃掉可能的修饰符与 import 行起点
        int lineStart = text.lastIndexOf('\n', classIndex);
        int start = lineStart < 0 ? 0 : lineStart + 1;
        return text.substring(start).strip();
    }

    @SuppressWarnings("unchecked")
    private List<PlanPoint> parsePlan(ChatResponse response) {
        if (response == null || response.stopReason() == StopReason.ERROR) {
            return List.of();
        }
        String content = response.content();
        List<Object> items = Json.readList(content);
        if (items.isEmpty()) {
            // 模型可能把 JSON 包在 markdown 代码块里
            int fence = content == null ? -1 : content.indexOf('[');
            int end = content == null ? -1 : content.lastIndexOf(']');
            if (fence >= 0 && end > fence) {
                items = Json.readList(content.substring(fence, end + 1));
            }
        }
        List<PlanPoint> points = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                Object title = map.get("title");
                Object detail = map.get("detail");
                if (title != null) {
                    points.add(new PlanPoint(String.valueOf(title),
                            detail == null ? "" : String.valueOf(detail)));
                }
            }
        }
        return points;
    }

    private static String bullet(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            sb.append("- ").append(item).append('\n');
        }
        return sb.toString();
    }
}
