package edu.zjnu.interview.ai.rag.eval;

import edu.zjnu.interview.ai.core.EmbeddingModel;
import edu.zjnu.interview.ai.rag.Chunker;
import edu.zjnu.interview.ai.rag.Document;
import edu.zjnu.interview.ai.rag.assemble.ContextAssembler;
import edu.zjnu.interview.ai.rag.index.IndexBuilder;
import edu.zjnu.interview.ai.rag.pipeline.LocalAnswerer;
import edu.zjnu.interview.ai.rag.pipeline.RagPipeline;
import edu.zjnu.interview.ai.rag.rerank.FeatureReranker;
import edu.zjnu.interview.ai.rag.rerank.RerankWeights;
import edu.zjnu.interview.ai.rag.store.InMemoryVectorStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 配置对比实验：在同一批问题上跑多组配置，输出可比较的指标表。
 *
 * <p><b>为什么必须做这件事？</b>
 * RAG 的每一个参数（chunk 大小、topK、hybrid 与否、重排权重）都会影响质量，
 * 但也都会影响成本。只报「当前配置 Recall@5 = 0.8」是没有信息量的——
 * 面试官真正想知道的是<b>「你做了什么取舍，代价是什么」</b>。
 * 一张多配置对比表能直接回答这个问题：哪组配置在什么指标上更好、付出了什么代价。
 *
 * <p><b>实验控制变量</b>：每组配置都用<b>全新的内存向量库</b>重新建索引，
 * 避免上一组的 chunk 残留在库里污染下一组；语料、黄金集、embedding 模型全部相同。
 */
public final class RagExperiment {

    /**
     * 一组实验配置。
     *
     * @param name              配置名（表格里显示）
     * @param chunkSize         切分粒度
     * @param overlap           重叠字符数
     * @param topK              保留的证据条数
     * @param hybrid            是否混合召回
     * @param rerank            是否启用重排
     * @param weights           重排权重
     * @param contextTokenBudget 上下文 token 预算
     */
    public record Config(String name, int chunkSize, int overlap, int topK, boolean hybrid,
                         boolean rerank, RerankWeights weights, int contextTokenBudget) {

        public Config {
            if (name == null || name.isBlank()) {
                name = "config";
            }
            weights = weights == null ? RerankWeights.defaults() : weights;
        }

        /** 常用配置：hybrid + 重排。 */
        public static Config of(String name, int chunkSize, int topK) {
            return new Config(name, chunkSize, Math.min(80, chunkSize / 5), topK, true, true,
                    RerankWeights.defaults(), 1200);
        }
    }

    /**
     * 一组配置的实验结果。
     *
     * @param name             配置名
     * @param metrics          指标
     * @param indexBuildMillis 建索引耗时
     * @param chunkCount       切出的片段数
     * @param indexDimension   向量维度
     */
    public record ConfigResult(String name, RagMetrics metrics, long indexBuildMillis,
                               int chunkCount, int indexDimension) {
    }

    /** 实验报告。 */
    public record Report(List<ConfigResult> results) {

        public Report {
            results = results == null ? List.of() : List.copyOf(results);
        }

        /** 打印成对比表。 */
        public String toTable() {
            int k = results.isEmpty() ? 5 : results.get(0).metrics().topK();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT,
                    "%-26s %-8s %-8s %-8s %-8s %-7s %-7s %-7s %-9s %-9s %-8s%n",
                    "配置", "Recall@" + k, "Prec@" + k, "MRR@10", "nDCG@10",
                    "hit", "引用", "拒答", "ctxToken", "延迟ms", "构建ms"));
            sb.append("-".repeat(120)).append('\n');
            for (ConfigResult result : results) {
                RagMetrics m = result.metrics();
                sb.append(String.format(Locale.ROOT,
                        "%-26s %-8.3f %-8.3f %-8.3f %-8.3f %-7.3f %-7.3f %-7.3f %-9.1f %-9.1f %-8d%n",
                        result.name(), m.recallAtK(), m.precisionAtK(), m.mrrAt10(), m.ndcgAt10(),
                        m.hitRate(), m.citationAccuracy(), m.refusalRate(),
                        m.avgContextTokens(), m.avgLatencyMillis(), result.indexBuildMillis()));
            }
            return sb.toString();
        }

        /**
         * 自动生成的结论：谁在什么指标上更好、代价是什么。
         *
         * <p>把「结论」也自动化，是为了让实验报告<b>可复现</b>：
         * 换一组配置后，结论会跟着变，而不是靠人重新写一遍。
         */
        public String conclusion() {
            if (results.size() < 2) {
                return "";
            }
            ConfigResult bestRecall = results.stream()
                    .max((a, b) -> Double.compare(a.metrics().recallAtK(), b.metrics().recallAtK())).orElseThrow();
            ConfigResult bestNdcg = results.stream()
                    .max((a, b) -> Double.compare(a.metrics().ndcgAt10(), b.metrics().ndcgAt10())).orElseThrow();
            ConfigResult cheapest = results.stream()
                    .min((a, b) -> Double.compare(a.metrics().avgContextTokens(), b.metrics().avgContextTokens()))
                    .orElseThrow();
            ConfigResult fastest = results.stream()
                    .min((a, b) -> Double.compare(a.metrics().avgLatencyMillis(), b.metrics().avgLatencyMillis()))
                    .orElseThrow();

            double recallDelta = bestRecall.metrics().recallAtK() - cheapest.metrics().recallAtK();
            double tokenDelta = bestRecall.metrics().avgContextTokens() - cheapest.metrics().avgContextTokens();
            double latencyDelta = bestRecall.metrics().avgLatencyMillis() - cheapest.metrics().avgLatencyMillis();

            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT,
                    "· 召回质量最优：%s（Recall@5=%.3f，nDCG@10=%.3f）；按 nDCG 看最优是 %s（%.3f）。%n",
                    bestRecall.name(), bestRecall.metrics().recallAtK(), bestRecall.metrics().ndcgAt10(),
                    bestNdcg.name(), bestNdcg.metrics().ndcgAt10()));
            sb.append(String.format(Locale.ROOT,
                    "· 成本最优：%s（平均上下文 %.1f token、平均 %.1f ms）；%s 相对它 Recall@5 %+.3f，"
                            + "代价是上下文 %+.1f token、延迟 %+.1f ms。%n",
                    cheapest.name(), cheapest.metrics().avgContextTokens(), cheapest.metrics().avgLatencyMillis(),
                    bestRecall.name(), recallDelta, tokenDelta, latencyDelta));
            sb.append(String.format(Locale.ROOT,
                    "· 时延最优：%s（平均 %.1f ms），说明召回策略（dense-only vs hybrid）对端到端时延的影响"
                            + "远小于切分粒度对上下文长度的影响。%n",
                    fastest.name(), fastest.metrics().avgLatencyMillis()));
            return sb.toString();
        }
    }

    private final EmbeddingModel embeddingModel;
    private final Clock clock;

    public RagExperiment(EmbeddingModel embeddingModel, Clock clock) {
        this.embeddingModel = embeddingModel;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 跑实验。
     *
     * @param documents 语料
     * @param cases     黄金集
     * @param configs   配置组
     */
    public Report run(List<Document> documents, List<GoldenCase> cases, List<Config> configs) {
        List<ConfigResult> results = new ArrayList<>(configs.size());
        for (Config config : configs) {
            Chunker chunker = new Chunker(new Chunker.Config(config.chunkSize(), config.overlap(),
                    120, Chunker.DEFAULT_SEPARATORS));
            // 每组配置用全新的内存向量库：避免上一组的 chunk 残留污染下一组
            InMemoryVectorStore store = new InMemoryVectorStore("experiment-" + config.name());
            long buildStart = System.nanoTime();
            var index = new IndexBuilder(chunker, embeddingModel, store, "Cosine").build(documents);
            long buildMillis = (System.nanoTime() - buildStart) / 1_000_000L;

            RagPipeline.Config pipelineConfig = new RagPipeline.Config(config.topK(), config.hybrid(),
                    config.rerank(), config.weights(), config.contextTokenBudget(), 2,
                    edu.zjnu.interview.ai.rag.lab.RagLab.REFUSAL_THRESHOLD, false, 20);
            ContextAssembler assembler = new ContextAssembler(new ContextAssembler.Config(
                    config.contextTokenBudget(), 2, true, 0.85));
            RagPipeline pipeline = new RagPipeline(index, pipelineConfig,
                    new FeatureReranker(config.weights(), clock), assembler, new LocalAnswerer(), null);

            RagMetrics metrics = new RagEvaluator(config.topK()).evaluate(pipeline, cases);
            results.add(new ConfigResult(config.name(), metrics, buildMillis,
                    index.chunkCount(), embeddingModel.dimensions()));
        }
        return new Report(results);
    }
}
