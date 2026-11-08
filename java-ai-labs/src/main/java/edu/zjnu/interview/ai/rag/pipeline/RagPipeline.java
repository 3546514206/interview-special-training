package edu.zjnu.interview.ai.rag.pipeline;

import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.rag.assemble.AssembledContext;
import edu.zjnu.interview.ai.rag.assemble.ContextAssembler;
import edu.zjnu.interview.ai.rag.index.KnowledgeIndex;
import edu.zjnu.interview.ai.rag.rerank.RerankWeights;
import edu.zjnu.interview.ai.rag.rerank.Reranker;
import edu.zjnu.interview.ai.rag.rerank.ScoredCandidate;
import edu.zjnu.interview.ai.rag.retrieve.Candidate;
import edu.zjnu.interview.ai.rag.retrieve.HybridRetriever;
import edu.zjnu.interview.ai.rag.retrieve.QueryRewriter;
import edu.zjnu.interview.ai.rag.retrieve.Retriever;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 端到端流水线：查询改写 → 召回 → 重排 → 上下文组装 → 生成/拒答。
 *
 * <p>把六段流水线串成一个可配置的对象，是为了让<b>配置对比实验</b>成为可能：
 * 实验要能在同一批问题上只改一个变量（chunk 大小 / topK / 是否 hybrid / 权重），
 * 其它一切保持不变。把流水线做成「可装配的组件」而不是一段过程代码，是这件事的前提。
 *
 * <p><b>拒答阈值</b>：重排分数是绝对刻度（见 {@code FeatureReranker}），
 * 所以可以跨查询比较。最高分低于阈值就拒答——
 * 这不是「模型不听话」，而是<b>检索链路的确定性判定</b>：
 * 知识库里没有的东西，不能让模型编一个（hallucination 的第一道防线）。
 */
public final class RagPipeline {

    private static final String SYSTEM_PROMPT = """
            你是私募资产服务领域的知识库助手。
            只依据提供的资料回答问题；如果资料不足以回答，请直接说明资料不足。
            每个结论后面必须标注引用编号，格式为 [n]，n 是资料条目前的编号。""";

    private final KnowledgeIndex index;
    private final Config config;
    private final Retriever retriever;
    private final Reranker reranker;
    private final ContextAssembler assembler;
    private final ChatModel answerModel;
    private final QueryRewriter rewriter;

    /**
     * 流水线配置。
     *
     * @param topK               上下文里保留的证据条数
     * @param hybrid             是否混合召回
     * @param rerankEnabled      是否启用重排（关闭时直接按召回分取 topK）
     * @param weights            重排权重
     * @param contextTokenBudget 上下文 token 预算
     * @param maxPerSource       单来源最多证据数
     * @param refusalThreshold   拒答阈值（最高分低于它就拒答）
     * @param rewriteQueries     是否做查询改写
     * @param candidatePool      送入重排的候选池大小
     */
    public record Config(int topK, boolean hybrid, boolean rerankEnabled, RerankWeights weights,
                         int contextTokenBudget, int maxPerSource, double refusalThreshold,
                         boolean rewriteQueries, int candidatePool) {

        public Config {
            if (topK <= 0) {
                throw new IllegalArgumentException("topK must be positive");
            }
            if (candidatePool < topK) {
                candidatePool = topK;
            }
        }

        /** 默认配置：hybrid + 重排 + 改写，拒答阈值 0.28。 */
        public static Config defaults() {
            return new Config(5, true, true, RerankWeights.defaults(),
                    1200, 2, 0.28, true, 20);
        }
    }

    public RagPipeline(KnowledgeIndex index, Config config, Reranker reranker,
                       ContextAssembler assembler, ChatModel answerModel, QueryRewriter rewriter) {
        this.index = index;
        this.config = config == null ? Config.defaults() : config;
        this.reranker = reranker;
        this.assembler = assembler == null ? new ContextAssembler(
                new ContextAssembler.Config(this.config.contextTokenBudget(), this.config.maxPerSource(), true, 0.85))
                : assembler;
        this.answerModel = answerModel;
        this.rewriter = rewriter;
        this.retriever = buildRetriever(index, this.config);
    }

    private static Retriever buildRetriever(KnowledgeIndex index, Config config) {
        if (index == null) {
            return null;
        }
        edu.zjnu.interview.ai.rag.retrieve.DenseRetriever dense =
                new edu.zjnu.interview.ai.rag.retrieve.DenseRetriever(index);
        edu.zjnu.interview.ai.rag.retrieve.SparseRetriever sparse =
                new edu.zjnu.interview.ai.rag.retrieve.SparseRetriever(index);
        return config.hybrid() ? new HybridRetriever(dense, sparse) : dense;
    }

    /** 检索器（实验里替换召回策略时用）。 */
    public Retriever retriever() {
        return retriever;
    }

    public Config config() {
        return config;
    }

    /**
     * 回答一个问题。
     *
     * @param question 用户问题
     */
    public RagAnswer answer(String question) {
        long start = System.nanoTime();
        List<String> queries = resolveQueries(question);
        List<Candidate> candidates = recall(queries);
        List<ScoredCandidate> reranked = rerank(question, candidates);
        AssembledContext context = assembler.assemble(question, reranked);

        double topScore = reranked.stream().mapToDouble(ScoredCandidate::score).max().orElse(0.0);
        boolean refused = topScore < config.refusalThreshold();
        String response;
        if (refused) {
            response = "知识库中没有检索到足够相关的内容，无法回答该问题。";
        } else if (answerModel == null) {
            response = "";
        } else {
            ChatRequest request = new ChatRequest(
                    List.of(ChatMessage.system(SYSTEM_PROMPT),
                            ChatMessage.user("问题：" + question + "\n\n" + context.text())),
                    List.of(), 0.0, 512, answerModel.name());
            ChatResponse response0 = answerModel.chat(request);
            response = response0 == null ? "" : response0.content();
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000L;
        return new RagAnswer(question, queries, reranked, context, response, refused, elapsed);
    }

    /** 决定实际用于检索的查询集合。 */
    private List<String> resolveQueries(String question) {
        if (!config.rewriteQueries() || rewriter == null) {
            return List.of(question);
        }
        QueryRewriter.Plan plan = rewriter.rewrite(question);
        return plan.queries();
    }

    /** 多查询召回后按 chunkId 合并（同一片段取更高分）。 */
    private List<Candidate> recall(List<String> queries) {
        if (retriever == null) {
            return List.of();
        }
        Map<String, Candidate> merged = new LinkedHashMap<>();
        for (String query : queries) {
            for (Candidate candidate : retriever.retrieve(query, config.candidatePool())) {
                merged.merge(candidate.chunkId(), candidate, (a, b) -> a.score() >= b.score() ? a : b);
            }
        }
        List<Candidate> out = new ArrayList<>(merged.values());
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }

    /** 重排（关闭时直接按召回分截断）。 */
    private List<ScoredCandidate> rerank(String question, List<Candidate> candidates) {
        if (!config.rerankEnabled() || reranker == null) {
            List<ScoredCandidate> out = new ArrayList<>();
            int limit = Math.min(candidates.size(), config.topK());
            for (int i = 0; i < limit; i++) {
                Candidate candidate = candidates.get(i);
                out.add(new ScoredCandidate(candidate, candidate.score(), Map.of(), "未启用重排，直接采用召回排序"));
            }
            return out;
        }
        return reranker.rerank(question, candidates, config.topK());
    }
}
