package edu.zjnu.interview.ai.agent.tools;

import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.core.KnowledgeBase;
import edu.zjnu.interview.ai.core.Tool;
import edu.zjnu.interview.ai.core.ToolCall;
import edu.zjnu.interview.ai.core.ToolContext;
import edu.zjnu.interview.ai.core.ToolResult;
import edu.zjnu.interview.ai.core.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识检索工具：把 Agent 与 RAG 连接起来。
 *
 * <p><b>为什么这里不实现检索</b>：检索（切分 / 向量化 / 召回 / 重排）是 RAG 方向的职责，
 * 它的质量直接决定 Agent 的答案质量。如果 Agent 里再写一套「简易检索」，
 * 就会出现两套检索逻辑、两种召回口径，评测结果没法解释。
 * 所以本工具只做一件事：<b>把 core.KnowledgeBase 这个端口包成模型看得懂的工具</b>，
 * 检索实现由外部注入（生产接真实检索器，测试接桩）。
 *
 * <p>返回给模型的格式沿用 {@code [1] (id) 标题 + 正文} 的证据格式，
 * 这样下游的「生成」节点可以直接引用编号，也便于评测「引用是否正确」。
 */
public final class KnowledgeSearchTool implements Tool {

    public static final String NAME = "knowledge_search";

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;

    private final KnowledgeBase knowledgeBase;

    public KnowledgeSearchTool(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    public KnowledgeBase knowledgeBase() {
        return knowledgeBase;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder(NAME, "在知识库中检索与问题相关的资料，返回带编号的证据列表")
                .param("query", "string", true, "检索问题")
                .optional("topK", "number", "返回条数", DEFAULT_TOP_K)
                .sideEffect(ToolSpec.SideEffect.READ_ONLY)
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        if (knowledgeBase == null) {
            return ToolResult.error("NO_KNOWLEDGE_BASE", "知识库未注入，无法检索");
        }
        Map<String, Object> args = Json.readMap(call.argumentsJson());
        Object rawQuery = args.get("query");
        if (rawQuery == null) {
            return ToolResult.error("MISSING_ARGUMENT", "缺少参数 query");
        }
        String query = String.valueOf(rawQuery).strip();
        if (query.isEmpty()) {
            return ToolResult.error("EMPTY_QUERY", "检索词为空");
        }
        int topK = DEFAULT_TOP_K;
        Object rawTopK = args.get("topK");
        if (rawTopK instanceof Number number) {
            topK = number.intValue();
        } else if (rawTopK != null) {
            try {
                topK = Integer.parseInt(String.valueOf(rawTopK));
            } catch (NumberFormatException e) {
                return ToolResult.error("INVALID_ARGUMENT", "topK 必须是整数: " + rawTopK);
            }
        }
        topK = Math.max(1, Math.min(MAX_TOP_K, topK));

        List<KnowledgeBase.Hit> hits;
        try {
            hits = knowledgeBase.search(query, topK);
        } catch (Exception e) {
            // 检索失败也是「工具失败」，结构化回灌，模型可以换个说法再试。
            return ToolResult.error("SEARCH_FAILED", "检索失败: " + e.getMessage());
        }
        if (hits == null) {
            hits = List.of();
        }
        StringBuilder sb = new StringBuilder();
        List<String> ids = new ArrayList<>();
        int index = 0;
        for (KnowledgeBase.Hit hit : hits) {
            index++;
            ids.add(hit.id());
            // 证据格式必须是 "[n](id) 标题"：core.PromptBlocks 的解析器按 "](" 定位 id，
            // 中间多一个空格就会整条证据被跳过（这是 core 契约里最容易被忽略的一个细节）。
            sb.append('[').append(index).append("](").append(hit.id()).append(") ")
                    .append(hit.title() == null ? "" : hit.title()).append('\n');
            sb.append(hit.content() == null ? "" : hit.content().strip()).append('\n');
        }
        if (index == 0) {
            sb.append("（没有检索到相关资料）");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hitCount", index);
        data.put("ids", ids);
        return ToolResult.ok(sb.toString().strip(), data);
    }
}
