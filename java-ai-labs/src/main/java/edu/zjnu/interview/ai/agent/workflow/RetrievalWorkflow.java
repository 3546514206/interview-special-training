package edu.zjnu.interview.ai.agent.workflow;

import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.KnowledgeBase;
import edu.zjnu.interview.ai.core.PromptBlocks;
import edu.zjnu.interview.ai.core.Text;

import java.util.List;

/**
 * 示例工作流：检索增强问答（路由 → 检索 → 生成 → 校验 → 不通过回到检索 → 收尾）。
 *
 * <pre>
 *   route ──(needRetrieval=true)──▶ retrieve ──▶ generate ──▶ verify
 *     │                                                 ▲         │
 *     └──(needRetrieval=false)────────────────────────┘         │
 *                                              (未通过且次数未用完)
 *                                                              │
 *                                            finalize ◀─────────┘
 * </pre>
 *
 * <p><b>为什么把 RAG 做成工作流而不是塞进 Agent Loop</b>：
 * Agent Loop 的控制流由模型决定，适合「不确定要做什么」的探索型任务；
 * 而「检索→生成→校验→必要时重试」是一条<b>已知且固定</b>的业务流程，
 * 它的每一步该调什么、失败了怎么办、重试几次都是确定的。
 * 确定性的流程用工作流编排（可测试、可审计、可画图），
 * 不确定的探索用 Agent Loop（模型自主决策）——<b>这是两者最本质的分工</b>。
 *
 * <p>路由用的是<b>确定性关键词规则</b>而不是模型：路由决策要可复现、可解释，
 * 「这个问题要不要查资料」用规则判断足够；生产上若需要语义路由，
 * 把这一节点换成小模型分类即可，其余节点完全不用改。
 */
public final class RetrievalWorkflow {

    /** 节点名。 */
    public static final String NODE_ROUTE = "route";
    public static final String NODE_RETRIEVE = "retrieve";
    public static final String NODE_GENERATE = "generate";
    public static final String NODE_VERIFY = "verify";
    public static final String NODE_FINALIZE = "finalize";

    /** 上下文 key。 */
    public static final String KEY_QUESTION = "question";
    public static final String KEY_NEED_RETRIEVAL = "needRetrieval";
    public static final String KEY_EVIDENCE = "evidence";
    public static final String KEY_ANSWER = "answer";
    public static final String KEY_VERIFIED = "verified";
    public static final String KEY_VERIFY_ATTEMPTS = "verifyAttempts";

    /** 触发检索的关键词（明确提到内部资料）。 */
    private static final List<String> RETRIEVAL_KEYWORDS = List.of(
            "资料", "文档", "规范", "接口", "规则", "手册", "白皮书", "说明", "定义", "流程",
            "制度", "策略", "配置", "参数", "库", "表", "字段", "检索", "查一下", "内部");

    /**
     * 「事实类问题」标记：问的是具体数字/条件/规则，答案不来自通用知识，必须查资料。
     *
     * <p>这一组词让路由不必依赖关键词表覆盖所有业务词——
     * 「多少、是什么、怎么算」这类<b>问句形态</b>本身就是「要事实」的信号。
     */
    private static final List<String> FACT_QUESTION_MARKERS = List.of(
            "多少", "是什么", "怎么", "如何", "哪些", "几天", "多久", "规定", "要求", "条件",
            "费率", "优惠", "限额", "额度", "收费", "手续费", "比例", "利率", "期限", "上限",
            "下限", "标准", "门槛", "步骤", "范围", "是否");

    private RetrievalWorkflow() {
    }

    /**
     * 构造工作流。
     *
     * @param model             生成模型
     * @param knowledgeBase     检索源（可为空，此时检索节点返回空证据）
     * @param maxRetrieveRounds 最多检索几轮（校验不过回到检索的次数上限）
     */
    public static Workflow build(ChatModel model, KnowledgeBase knowledgeBase, int maxRetrieveRounds) {
        int maxRounds = Math.max(1, maxRetrieveRounds);

        Workflow.Builder builder = Workflow.builder("retrieval-qa");

        // 1) 路由：判断这个问题要不要查资料
        builder.node(WorkflowNode.builder(NODE_ROUTE)
                .handler(ctx -> {
                    String question = ctx.getString(KEY_QUESTION, "");
                    boolean need = Text.containsAny(question, RETRIEVAL_KEYWORDS)
                            || Text.containsAny(question, FACT_QUESTION_MARKERS);
                    ctx.put(KEY_NEED_RETRIEVAL, need);
                    ctx.put("routeReason", need
                            ? "命中检索信号（资料类关键词或事实类问句），需要检索"
                            : "无需检索，直接作答");
                    return NodeOutput.ok(need);
                })
                .build());

        // 2) 检索：走 KnowledgeBase 端口（检索实现由 RAG 方向注入）
        builder.node(WorkflowNode.builder(NODE_RETRIEVE)
                .handler(ctx -> {
                    String question = ctx.getString(KEY_QUESTION, "");
                    List<KnowledgeBase.Hit> hits = knowledgeBase == null
                            ? List.of()
                            : knowledgeBase.search(question, 5);
                    StringBuilder sb = new StringBuilder();
                    int index = 0;
                    for (KnowledgeBase.Hit hit : hits) {
                        index++;
                        // 证据格式 "[n](id) 标题"：与 core.PromptBlocks.parseEvidence 的解析规则严格对齐
                        sb.append('[').append(index).append("](").append(hit.id()).append(") ")
                                .append(hit.title() == null ? "" : hit.title()).append('\n')
                                .append(hit.content() == null ? "" : hit.content().strip()).append('\n');
                    }
                    String evidence = sb.toString();
                    ctx.put(KEY_EVIDENCE, evidence);
                    return NodeOutput.ok(evidence, java.util.Map.of("hitCount", index));
                })
                .build());

        // 3) 生成：把证据包进 <context> 块，明确区分「资料」与「指令」
        builder.node(WorkflowNode.builder(NODE_GENERATE)
                .handler(ctx -> {
                    String question = ctx.getString(KEY_QUESTION, "");
                    String evidence = ctx.getString(KEY_EVIDENCE, "");
                    String prompt = PromptBlocks.wrapContext(evidence) + "\n问题：" + question;
                    String answer = "";
                    if (model != null) {
                        ChatResponse response = model.chat(new ChatRequest(
                                List.of(ChatMessage.system("请基于给定资料回答问题，并在句末用 [n] 标注引用来源。"),
                                        ChatMessage.user(prompt)),
                                List.of(), 0.0, 512, model.name()));
                        if (response != null) {
                            answer = response.content();
                        }
                    }
                    ctx.put(KEY_ANSWER, answer);
                    return NodeOutput.ok(answer);
                })
                .build());

        // 4) 校验：判断答案是否「有据可依」
        builder.node(WorkflowNode.builder(NODE_VERIFY)
                .handler(ctx -> {
                    String answer = ctx.getString(KEY_ANSWER, "");
                    int attempts = ctx.increment(KEY_VERIFY_ATTEMPTS);
                    boolean hasCitation = answer.contains("[1]") || answer.contains("[2]");
                    boolean usable = answer != null && !answer.isBlank()
                            && !answer.contains("不足以回答")
                            && hasCitation;
                    ctx.put(KEY_VERIFIED, usable);
                    ctx.put("verifyReason", usable ? "答案有引用支撑" : "答案缺少有效引用");
                    return NodeOutput.ok(usable, java.util.Map.of("attempts", attempts));
                })
                .build());

        // 5) 收尾：组装最终交付物
        builder.node(WorkflowNode.builder(NODE_FINALIZE)
                .handler(ctx -> {
                    String answer = ctx.getString(KEY_ANSWER, "");
                    boolean verified = ctx.getBoolean(KEY_VERIFIED, false);
                    int attempts = ctx.counter(KEY_VERIFY_ATTEMPTS);
                    String finalAnswer = verified
                            ? answer
                            : answer + "\n（注：经过 " + attempts + " 轮检索仍未通过引用校验，以上为当前最佳结果）";
                    ctx.put("finalAnswer", finalAnswer);
                    return NodeOutput.ok(finalAnswer);
                })
                .build());

        // ---- 边：条件路由 ----
        builder.edge(NODE_ROUTE, NODE_RETRIEVE,
                (ctx, out) -> ctx.getBoolean(KEY_NEED_RETRIEVAL, false), "需要检索");
        builder.edge(NODE_ROUTE, NODE_GENERATE,
                (ctx, out) -> !ctx.getBoolean(KEY_NEED_RETRIEVAL, false), "无需检索");
        builder.edge(NODE_RETRIEVE, NODE_GENERATE);
        builder.edge(NODE_GENERATE, NODE_VERIFY);
        builder.edge(NODE_VERIFY, NODE_RETRIEVE,
                (ctx, out) -> !ctx.getBoolean(KEY_VERIFIED, true) && ctx.counter(KEY_VERIFY_ATTEMPTS) < maxRounds,
                "校验不通过，回到检索");
        builder.edge(NODE_VERIFY, NODE_FINALIZE,
                (ctx, out) -> ctx.getBoolean(KEY_VERIFIED, false) || ctx.counter(KEY_VERIFY_ATTEMPTS) >= maxRounds,
                "校验通过或次数用尽，收尾");

        builder.entry(NODE_ROUTE);
        return builder.build();
    }

    /** 默认最多检索 2 轮。 */
    public static Workflow build(ChatModel model, KnowledgeBase knowledgeBase) {
        return build(model, knowledgeBase, 2);
    }

    /** 运行工作流（便捷方法）。 */
    public static WorkflowResult run(WorkflowRunner runner, ChatModel model, KnowledgeBase knowledgeBase,
                                     String question, String traceId) {
        Workflow workflow = build(model, knowledgeBase);
        WorkflowContext ctx = new WorkflowContext(traceId);
        ctx.put(KEY_QUESTION, question);
        return runner.run(workflow, ctx);
    }
}
