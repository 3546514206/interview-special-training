package edu.zjnu.interview.ai.rag.retrieve;

import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.StopReason;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 查询改写 / 多查询扩展。
 *
 * <p><b>为什么需要它？</b>
 * 用户的问题和知识库里的表述经常不一样：「私募的门槛是多少」vs「管理人实缴资本要求」。
 * 向量检索能弥合一部分语义差异，但对<b>术语</b>（合规黑话、业务专名）无能为力。
 * 把原问题改写成 2~3 个说法不同的检索式再合并召回，等于用「多次抽签」换召回率——
 * 单次召回失败的查询，换个说法可能就命中了。
 *
 * <p><b>降级是必须能力，不是补丁</b>：
 * 线上模型会超时、会限流、会返回空内容。如果改写失败就让整条链路失败，
 * 系统可用性会被一个「锦上添花的优化」绑架。所以这里的设计是：
 * <b>改写失败 → 降级为原查询 → 链路照常工作</b>，并把降级标记透出到 {@link Plan#degraded()}，
 * 便于监控「改写成功率」这个指标。
 */
public final class QueryRewriter {

    /** 默认生成数量。 */
    public static final int DEFAULT_VARIANTS = 3;

    private static final String SYSTEM_PROMPT = """
            你是企业知识库的检索式改写器。
            用户问题可能口语化，或省略了业务术语。请把问题改写成 2~3 个不同的检索式，
            分别覆盖：业务专有名词说法、同义表达说法、完整问法。
            只输出检索式本身，每行一个，不要编号，不要解释，不要输出任何其它内容。""";

    private final ChatModel model;
    private final int maxVariants;

    public QueryRewriter(ChatModel model) {
        this(model, DEFAULT_VARIANTS);
    }

    public QueryRewriter(ChatModel model, int maxVariants) {
        this.model = model;
        this.maxVariants = Math.max(1, maxVariants);
    }

    /**
     * 改写结果。
     *
     * @param original   原问题
     * @param variants   改写出的检索式（不含原问题）
     * @param degraded   是否降级（模型不可用 / 输出不可解析）
     * @param rawResponse 模型原始输出（便于排查）
     */
    public record Plan(String original, List<String> variants, boolean degraded, String rawResponse) {

        public Plan {
            variants = variants == null ? List.of() : List.copyOf(variants);
        }

        /** 原问题 + 改写式（去重），作为实际要检索的查询集合。 */
        public List<String> queries() {
            Set<String> all = new LinkedHashSet<>();
            all.add(original);
            all.addAll(variants);
            return new ArrayList<>(all);
        }

        /** 是否产生了额外查询。 */
        public boolean expanded() {
            return !variants.isEmpty();
        }
    }

    /**
     * 执行改写。
     *
     * @param query 用户问题
     * @return 改写计划（任何失败都表现为降级，不会抛异常）
     */
    public Plan rewrite(String query) {
        if (query == null || query.isBlank()) {
            return new Plan(query == null ? "" : query, List.of(), true, "");
        }
        if (model == null) {
            return new Plan(query, List.of(), true, "");
        }
        String user = "问题：" + query + "\n请输出 " + maxVariants + " 个检索式。";
        ChatRequest request = new ChatRequest(
                List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(user)),
                List.of(), 0.0, 256, model.name());
        ChatResponse response;
        try {
            response = model.chat(request);
        } catch (RuntimeException e) {
            // 模型链路异常：降级，绝不让优化环节拖垮主链路
            return new Plan(query, List.of(), true, "exception:" + e.getClass().getSimpleName());
        }
        if (response == null) {
            return new Plan(query, List.of(), true, "null-response");
        }
        String raw = response.content();
        if (response.stopReason() == StopReason.ERROR) {
            return new Plan(query, List.of(), true, raw);
        }
        List<String> variants = parseVariants(raw, maxVariants);
        if (variants.isEmpty()) {
            return new Plan(query, List.of(), true, raw);
        }
        return new Plan(query, variants, false, raw);
    }

    /** 解析模型输出：一行一个检索式，容忍编号、项目符号等噪声。 */
    static List<String> parseVariants(String raw, int max) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String line : raw.split("\\R")) {
            String value = line.strip();
            if (value.isEmpty()) {
                continue;
            }
            value = value.replaceAll("^[-*•·]\\s*", "")
                    .replaceAll("^[（(]?\\d{1,2}[)）.、]?\\s*", "")
                    .replaceAll("^[①-⑳]\\s*", "")
                    .strip();
            // 去掉可能的引号包裹
            value = value.replaceAll("^[\"“”'](.*)[\"“”']$", "$1").strip();
            if (value.isEmpty() || value.length() > 120) {
                continue;
            }
            if (seen.add(value)) {
                out.add(value);
            }
            if (out.size() >= max) {
                break;
            }
        }
        return out;
    }
}
