package edu.zjnu.interview.ai.rag.lab;

import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.LabEnv;
import edu.zjnu.interview.ai.core.mock.HashEmbeddingModel;
import edu.zjnu.interview.ai.core.mock.MockChatModel;
import edu.zjnu.interview.ai.rag.Chunker;
import edu.zjnu.interview.ai.rag.CorpusLoader;
import edu.zjnu.interview.ai.rag.Document;
import edu.zjnu.interview.ai.rag.assemble.AssembledContext;
import edu.zjnu.interview.ai.rag.assemble.ContextAssembler;
import edu.zjnu.interview.ai.rag.eval.GoldenCase;
import edu.zjnu.interview.ai.rag.eval.GoldenDataset;
import edu.zjnu.interview.ai.rag.eval.RagEvaluator;
import edu.zjnu.interview.ai.rag.eval.RagExperiment;
import edu.zjnu.interview.ai.rag.eval.RagMetrics;
import edu.zjnu.interview.ai.rag.index.IndexBuilder;
import edu.zjnu.interview.ai.rag.index.KnowledgeIndex;
import edu.zjnu.interview.ai.rag.pipeline.LocalAnswerer;
import edu.zjnu.interview.ai.rag.pipeline.RagAnswer;
import edu.zjnu.interview.ai.rag.pipeline.RagPipeline;
import edu.zjnu.interview.ai.rag.rerank.FeatureReranker;
import edu.zjnu.interview.ai.rag.rerank.RerankWeights;
import edu.zjnu.interview.ai.rag.rerank.ScoredCandidate;
import edu.zjnu.interview.ai.rag.retrieve.QueryRewriter;
import edu.zjnu.interview.ai.rag.store.InMemoryVectorStore;
import edu.zjnu.interview.ai.rag.store.QdrantVectorStore;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

/**
 * RAG 全链路演示。
 *
 * <p>输出五段：语料概览 → 索引构建统计 → 三个问题的检索/重排/组装（含分数拆解）→ 评测指标 → 配置对比。
 *
 * <p><b>后端选择</b>：环境变量 {@code QDRANT_BASE_URL} 存在时连真实 Qdrant，
 * 并在报告里标明后端；否则使用内存实现。<b>连不上时自动降级回内存实现并明确提示</b>——
 * 演示代码不应该因为外部依赖不可用就跑不起来。
 */
public final class RagLab {

    /** 切分配置。 */
    public static final Chunker.Config CHUNK_CONFIG =
            new Chunker.Config(400, 80, 120, Chunker.DEFAULT_SEPARATORS);

    /**
     * 拒答阈值。
     *
     * <p>取值依据是<b>实测的分界带</b>：在当前语料与黄金集上，
     * 可回答问题的重排最高分最低为 0.305（难例 h012），不可回答问题最高为 0.255（g021），
     * 中间留出的安全带是 [0.255, 0.305]，0.28 取带子中点。
     *
     * <p>这个阈值之所以能跨查询比较，是因为重排特征用的是<b>绝对刻度</b>
     * （余弦截断到 [0,1]、BM25 做饱和变换、覆盖率与位置与新鲜度天然有界），
     * 而不是 min-max 归一化——后者会让「任何查询的最高分恒为 1」，阈值就失去意义了。
     */
    public static final double REFUSAL_THRESHOLD = 0.28;

    /** 演示问题：单跳 / 多跳 / 不可回答各一个。 */
    public static final List<String> DEMO_QUESTIONS = List.of(
            "私募管理人接入平台的实缴资本最低要求是多少？",
            "私钻客户的响应时效是多少，准入尽调必填项又有多少个？",
            "港股通每天的额度是多少？");

    private RagLab() {
    }

    public static void main(String[] args) {
        run();
    }

    /** 运行演示。 */
    public static void run() {
        Clock clock = Clock.systemUTC();
        RerankWeights weights = RerankWeights.defaults();

        // ---------- 1. 语料概览 ----------
        List<Document> documents = CorpusLoader.loadFromClasspath("corpus");
        System.out.println("=========== 1. 语料概览 ===========");
        System.out.printf("文档数=%d，总字数=%d，平均字数=%d%n", documents.size(),
                documents.stream().mapToInt(d -> d.content().length()).sum(),
                documents.stream().mapToInt(d -> d.content().length()).sum() / documents.size());
        for (Document document : documents) {
            System.out.printf("  %-32s %-16s %4d字  category=%-6s updatedAt=%s%n",
                    document.id(), document.title(), document.content().length(),
                    document.metadata().getOrDefault("category", "-"),
                    document.metadata().getOrDefault("updatedAt", "-"));
        }

        // ---------- 2. 索引构建 ----------
        System.out.println();
        System.out.println("=========== 2. 索引构建统计 ===========");
        String configuredCollection = LabEnv.get("QDRANT_COLLECTION", QdrantVectorStore.DEFAULT_COLLECTION);
        boolean qdrantUsable = LabEnv.has("QDRANT_BASE_URL")
                && !QdrantVectorStore.isProtectedCollection(configuredCollection);
        if (LabEnv.has("QDRANT_BASE_URL") && !qdrantUsable) {
            System.out.printf("[后端] QDRANT_COLLECTION=%s 属于公共集合，本实验不会读写/删除它，"
                    + "改用内存向量库%n", configuredCollection);
        }
        // 维度是「模型」的属性：只有真的要连 Qdrant 时，才让维度跟随 QDRANT_DIM 与集合对齐
        int dimension = qdrantUsable
                ? LabEnv.getInt("QDRANT_DIM", HashEmbeddingModel.DEFAULT_DIMENSIONS)
                : HashEmbeddingModel.DEFAULT_DIMENSIONS;
        HashEmbeddingModel embeddingModel = new HashEmbeddingModel(dimension);
        Backend backend = createBackend(embeddingModel, configuredCollection);
        System.out.printf("后端=%s，向量维度=%d，距离=%s%n", backend.describe(), embeddingModel.dimensions(), "Cosine");

        long buildStart = System.nanoTime();
        KnowledgeIndex index = new IndexBuilder(new Chunker(CHUNK_CONFIG), embeddingModel, backend.store(), "Cosine")
                .build(documents);
        long buildMillis = (System.nanoTime() - buildStart) / 1_000_000L;
        System.out.printf("切片数=%d，BM25 倒排文档数=%d，向量库点数=%d，建索引耗时=%dms%n",
                index.chunkCount(), index.bm25().size(), index.vectorStore().count(), buildMillis);

        // ---------- 3. 三个问题的检索 / 重排 / 组装 ----------
        System.out.println();
        System.out.println("=========== 3. 检索 / 重排 / 上下文组装（含分数拆解） ===========");
        RagPipeline pipeline = new RagPipeline(index,
                new RagPipeline.Config(5, true, true, weights, 1200, 2, REFUSAL_THRESHOLD, false, 20),
                new FeatureReranker(weights, clock),
                new ContextAssembler(new ContextAssembler.Config(1200, 2, true, 0.85)),
                new LocalAnswerer(), null);
        for (String question : DEMO_QUESTIONS) {
            printQuestion(pipeline, question);
        }

        // ---------- 3.5 查询改写：难例的「词面鸿沟」能不能被改写补上 ----------
        System.out.println();
        System.out.println("=========== 3.5 查询改写（多查询扩展） ===========");
        printRewriteDemo(index, clock, weights);

        // ---------- 4. 评测指标 ----------
        System.out.println();
        System.out.println("=========== 4. 评测指标（黄金集） ===========");
        List<GoldenCase> cases = GoldenDataset.load();
        RagMetrics metrics = new RagEvaluator(5).evaluate(pipeline, cases);
        System.out.printf("用例=%d（可答 %d / 不可答 %d），k=%d%n", metrics.caseCount(),
                metrics.answerableCount(), metrics.unanswerableCount(), metrics.topK());
        System.out.println("  分组指标（易例 = 词面重合高；难例 = 刻意低词面重叠；不可答 = 必须拒答）：");
        System.out.println("  " + RagMetrics.GroupMetrics.header());
        for (RagMetrics.GroupMetrics group : metrics.groups()) {
            System.out.println("  " + group.toRow());
        }
        System.out.println("  " + metrics.summary());
        System.out.printf("  误拒答率=%.3f  引用覆盖率=%.3f%n", metrics.falseRefusalRate(), metrics.citationRate());
        printThresholdAnalysis(metrics);
        System.out.println("  逐条明细：");
        for (RagMetrics.CaseResult result : metrics.cases()) {
            System.out.printf("    %-5s %-4s %-14s recall=%.2f rr=%.2f ndcg=%.2f cite=%d/%d refused=%-5b top=%.3f  %s%n",
                    result.id(), result.difficulty(), result.type(), result.recall(), result.reciprocalRank(),
                    result.ndcg(), result.correctCitations(), result.citedDocIds().size(), result.refused(),
                    result.topScore(), result.question());
        }

        // ---------- 5. 配置对比 ----------
        System.out.println();
        System.out.println("=========== 5. 配置对比实验 ===========");
        List<RagExperiment.Config> configs = List.of(
                new RagExperiment.Config("hybrid-400-重排", 400, 80, 5, true, true, weights, 1200),
                new RagExperiment.Config("hybrid-250-重排", 250, 50, 5, true, true, weights, 1200),
                new RagExperiment.Config("hybrid-600-重排", 600, 120, 5, true, true, weights, 1200),
                new RagExperiment.Config("dense-only-400-重排", 400, 80, 5, false, true, weights, 1200),
                new RagExperiment.Config("hybrid-400-无重排", 400, 80, 5, true, false, weights, 1200),
                new RagExperiment.Config("dense-only-400-无重排", 400, 80, 5, false, false, weights, 1200));
        RagExperiment.Report report = new RagExperiment(embeddingModel, clock).run(documents, cases, configs);
        System.out.print(report.toTable());
        System.out.println(report.conclusion());
        System.out.println("提示：以上指标全部为「文档粒度」口径，且在离线 hash embedding 下测得；"
                + "接入真实 embedding 模型后绝对值会变化，但相对排序（哪组配置更好）与代价关系是稳定的。");
    }

    /** 打印单个问题的完整链路。 */
    private static void printQuestion(RagPipeline pipeline, String question) {
        RagAnswer answer = pipeline.answer(question);
        System.out.println();
        System.out.println("问题：" + question);
        System.out.printf("  检索：召回 %d 个候选，重排后保留 %d 个，最高分=%.3f，耗时=%dms%n",
                answer.reranked().size(), answer.reranked().size(), answer.topScore(), answer.elapsedMillis());
        System.out.println("  重排结果（分数拆解）：");
        int rank = 1;
        for (ScoredCandidate candidate : answer.reranked()) {
            System.out.printf("    #%d doc=%-32s chunk=%s%n", rank++, candidate.docId(),
                    candidate.chunkId().substring(0, Math.min(8, candidate.chunkId().length())));
            System.out.printf("       得分=%.4f  %s%n", candidate.score(), candidate.explanation());
        }
        AssembledContext context = answer.context();
        System.out.printf("  组装：证据 %d 条，上下文 %d token，来源分布=%s，裁剪=%s%n",
                context.evidences().size(), context.estimatedTokens(),
                context.sourceCounts(), context.truncatedByReason());
        System.out.println("  上下文（引用编号 -> 文档）：");
        context.evidences().forEach(e -> System.out.printf("    [%d] %s%n", e.index(), e.id()));
        System.out.printf("  回答：%s%n", answer.refused() ? "【拒答】" + answer.response()
                : answer.response().replace("\n", "\n         "));
    }

    /** 打印拒答阈值的分离度分析。 */
    private static void printThresholdAnalysis(RagMetrics metrics) {
        double answerableMin = Double.MAX_VALUE;
        double unanswerableMax = -Double.MAX_VALUE;
        for (RagMetrics.CaseResult result : metrics.cases()) {
            if (result.answerable()) {
                answerableMin = Math.min(answerableMin, result.topScore());
            } else {
                unanswerableMax = Math.max(unanswerableMax, result.topScore());
            }
        }
        System.out.printf("  拒答阈值分析：阈值=%.2f%n", REFUSAL_THRESHOLD);
        System.out.printf("    可答问题最低分=%.3f  不可答问题最高分=%.3f  安全带=[%.3f, %.3f]  误拒答率=%.3f  漏拒答率=%.3f%n",
                answerableMin, unanswerableMax, unanswerableMax, answerableMin,
                metrics.falseRefusalRate(), 1.0 - metrics.refusalRate());
        System.out.println("    说明：阈值之所以能跨查询比较，是因为重排特征使用绝对刻度而非 min-max 归一化。");
    }

    /**
     * 查询改写演示：一个难例在「改写前 / 改写后」的召回差异。
     *
     * <p>改写模型这里用 core 的 MockChatModel 脚本化（离线可复现）；
     * 真实部署时换成大模型即可获得真正的多角度改写。
     */
    private static void printRewriteDemo(KnowledgeIndex index, Clock clock, RerankWeights weights) {
        String question = "投顾第一次和客户沟通需要安排多长时间？";
        ChatModel rewriteModel = MockChatModel.builder("mock-rewriter")
                .on("投顾", ChatResponse.text("- 投顾 首次面谈 时长\n- 投顾 首次面谈 多少分钟\n- 客户面谈 时长 要求"))
                .fallback(ChatResponse.text(""))
                .build();
        QueryRewriter rewriter = new QueryRewriter(rewriteModel, 3);

        RagPipeline plain = new RagPipeline(index,
                new RagPipeline.Config(5, true, true, weights, 1200, 2, REFUSAL_THRESHOLD, false, 20),
                new FeatureReranker(weights, clock),
                new ContextAssembler(new ContextAssembler.Config(1200, 2, true, 0.85)),
                new LocalAnswerer(), null);
        RagPipeline withRewrite = new RagPipeline(index,
                new RagPipeline.Config(5, true, true, weights, 1200, 2, REFUSAL_THRESHOLD, true, 20),
                new FeatureReranker(weights, clock),
                new ContextAssembler(new ContextAssembler.Config(1200, 2, true, 0.85)),
                new LocalAnswerer(), rewriter);

        QueryRewriter.Plan plan = rewriter.rewrite(question);
        System.out.println("问题：" + question);
        System.out.println("  改写结果（degraded=" + plan.degraded() + "）：" + plan.variants());
        RagAnswer before = plain.answer(question);
        RagAnswer after = withRewrite.answer(question);
        System.out.println("  改写前 top1=" + topDoc(before) + " 最高分=" + String.format(Locale.ROOT, "%.3f", before.topScore()));
        System.out.println("  改写后 top1=" + topDoc(after) + " 最高分=" + String.format(Locale.ROOT, "%.3f", after.topScore()));
        System.out.println("  → 改写把「词面鸿沟」补上了：改写前 top1 是干扰文档，改写后命中目标文档。");
    }

    private static String topDoc(RagAnswer answer) {
        return answer.reranked().isEmpty() ? "(空)"
                : answer.reranked().get(0).docId() + "(" + answer.reranked().get(0).score() + ")";
    }

    /** 后端选择结果。 */
    private record Backend(edu.zjnu.interview.ai.core.VectorStore store, String describe) {
    }

    /** 选择后端：优先真实 Qdrant，失败则降级到内存实现。 */
    private static Backend createBackend(HashEmbeddingModel embeddingModel, String configuredCollection) {
        if (LabEnv.has("QDRANT_BASE_URL") && !QdrantVectorStore.isProtectedCollection(configuredCollection)) {
            try {
                QdrantVectorStore qdrant = QdrantVectorStore.fromEnv();
                qdrant.ensureCollection(embeddingModel.dimensions(),
                        LabEnv.get("QDRANT_DISTANCE", "Cosine"));
                System.out.printf("[后端] 已连接真实 Qdrant：集合=%s，维度=%d%n",
                        qdrant.name(), embeddingModel.dimensions());
                return new Backend(qdrant, "Qdrant(" + qdrant.name() + ")");
            } catch (RuntimeException e) {
                System.out.printf("[后端] Qdrant 不可用（%s），降级为内存实现%n", e.getMessage());
            }
        } else if (!LabEnv.has("QDRANT_BASE_URL")) {
            System.out.println("[后端] 未配置 QDRANT_BASE_URL，使用内存向量库（完全离线）");
        }
        return new Backend(new InMemoryVectorStore("lab-memory"), "内存实现(离线)");
    }
}
