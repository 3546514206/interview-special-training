package edu.zjnu.interview.ai.rag.eval;

import edu.zjnu.interview.ai.core.EmbeddingModel;
import edu.zjnu.interview.ai.core.mock.HashEmbeddingModel;
import edu.zjnu.interview.ai.rag.Document;
import edu.zjnu.interview.ai.rag.index.IndexBuilder;
import edu.zjnu.interview.ai.rag.index.KnowledgeIndex;
import edu.zjnu.interview.ai.rag.pipeline.RagPipeline;
import edu.zjnu.interview.ai.rag.pipeline.LocalAnswerer;
import edu.zjnu.interview.ai.rag.rerank.FeatureReranker;
import edu.zjnu.interview.ai.rag.rerank.RerankWeights;
import edu.zjnu.interview.ai.rag.store.InMemoryVectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评测器测试：指标口径必须精确可复现。
 *
 * <p>指标公式错一个符号，整个实验的结论就全错。这里分两层验证：
 * 一层用构造数据断言每个指标的<b>精确数学值</b>；
 * 另一层用「合成语料 + 内存向量库」跑端到端流水线，断言链路级指标。
 */
class RagEvaluatorTest {

    // ------------------------------------------------------------------ 指标精确值

    @Test
    @DisplayName("MRR：首个正确文档在第 2 位 → 0.5；不在 → 0")
    void reciprocalRankExact() {
        assertEquals(0.5, RagEvaluator.reciprocalRank(List.of("x", "a", "b"), java.util.Set.of("a")), 1e-12);
        assertEquals(1.0, RagEvaluator.reciprocalRank(List.of("a", "x"), java.util.Set.of("a", "b")), 1e-12);
        assertEquals(0.0, RagEvaluator.reciprocalRank(List.of("x", "y"), java.util.Set.of("a")), 1e-12);
    }

    @Test
    @DisplayName("nDCG@10：单期望第 1 位 = 1.0；两个期望第 1、3 位 = DCG/IDCG 精确值")
    void ndcgExact() {
        // 期望 {a}，检索 [a, x]：DCG = 1，IDCG = 1 → 1.0
        assertEquals(1.0, RagEvaluator.ndcg(List.of("a", "x"), java.util.Set.of("a"), 10), 1e-12);

        // 期望 {a, b}，检索 [a, x, b]：DCG = 1 + 1/log2(4) = 1.5，IDCG = 1 + 1/log2(3)
        double expected = (1.0 + 1.0 / (Math.log(4) / Math.log(2)))
                / (1.0 + 1.0 / (Math.log(3) / Math.log(2)));
        assertEquals(expected, RagEvaluator.ndcg(List.of("a", "x", "b"), java.util.Set.of("a", "b"), 10), 1e-12);
    }

    @Test
    @DisplayName("引用解析：[1] 与 [3] 被提取，非法编号被忽略")
    void citationParsing() {
        assertEquals(List.of(1, 3), RagEvaluator.parseCitations("答案第一句 [1]，第二句 [3]。"));
        assertTrue(RagEvaluator.parseCitations("没有引用").isEmpty());
    }

    @Test
    @DisplayName("文档去重保序：同一文档多个片段只计第一次出现的位置")
    void distinctDocIdsKeepOrder() {
        // 用 RagAnswer 承载 reranked 序列
        edu.zjnu.interview.ai.rag.pipeline.RagAnswer answer = buildAnswerWithDocSequence("d1", "d2", "d1", "d3");

        List<String> distinct = RagEvaluator.distinctDocIds(answer, 5);

        assertEquals(List.of("d1", "d2", "d3"), distinct);
    }

    // ------------------------------------------------------------------ 端到端（合成语料 + 内存向量库）

    private static final Document DOC_ASSET = new Document("doc-asset", "私募资产接入标准",
            "私募资产准入要求实缴资本不低于 1000 万元。机构投资者需提供近三年审计报告。"
                    + "准入材料由合规部门审核，审核周期一般为五个工作日。",
            "corpus/asset.md", java.util.Map.of("category", "准入"));
    private static final Document DOC_RISK = new Document("doc-risk", "风险识别规则",
            "单一私募基金集中度不得超过 30%。客户风险等级 R4 以上禁止申购高风险产品。"
                    + "发现异常交易应在当日上报风控部门。",
            "corpus/risk.md", java.util.Map.of("category", "风险"));
    private static final Document DOC_ADVISORY = new Document("doc-advisory", "投顾服务流程",
            "投顾服务开始前必须完成适当性评估。评估结果有效期为一年。"
                    + "服务记录保存期限不少于五年。",
            "corpus/advisory.md", java.util.Map.of("category", "服务"));

    private static RagPipeline pipeline() {
        EmbeddingModel embedding = new HashEmbeddingModel();
        InMemoryVectorStore store = new InMemoryVectorStore("unit-test");
        KnowledgeIndex index = new IndexBuilder(new edu.zjnu.interview.ai.rag.Chunker(), embedding, store, "Cosine")
                .build(List.of(DOC_ASSET, DOC_RISK, DOC_ADVISORY));
        RagPipeline.Config config = new RagPipeline.Config(5, true, true, RerankWeights.defaults(),
                800, 2, 0.28, false, 20);
        return new RagPipeline(index, config, new FeatureReranker(RerankWeights.defaults(),
                java.time.Clock.systemUTC()), null, new LocalAnswerer(), null);
    }

    private static List<GoldenCase> goldenCases() {
        return List.of(
                new GoldenCase("g1", "私募资产准入的实缴资本要求是多少",
                        List.of("doc-asset"), List.of("1000"), "实缴资本不低于 1000 万元",
                        GoldenCase.TYPE_SINGLE_HOP, GoldenCase.DIFFICULTY_EASY, true),
                new GoldenCase("g2", "单一私募基金的集中度上限",
                        List.of("doc-risk"), List.of("30%"), "不得超过 30%",
                        GoldenCase.TYPE_SINGLE_HOP, GoldenCase.DIFFICULTY_EASY, true),
                new GoldenCase("g3", "数字人民币钱包的年度限额",
                        List.of(), List.of(), "",
                        GoldenCase.TYPE_UNANSWERABLE, GoldenCase.DIFFICULTY_EASY, false));
    }

    private static edu.zjnu.interview.ai.rag.pipeline.RagAnswer buildAnswerWithDocSequence(String... docIds) {
        java.util.List<edu.zjnu.interview.ai.rag.rerank.ScoredCandidate> reranked = new java.util.ArrayList<>();
        int i = 0;
        for (String docId : docIds) {
            edu.zjnu.interview.ai.rag.retrieve.Candidate candidate =
                    new edu.zjnu.interview.ai.rag.retrieve.Candidate("c" + i, docId, "标题", "内容", "src", 0,
                            java.util.Map.of(edu.zjnu.interview.ai.rag.retrieve.Candidate.CHANNEL_DENSE, 0.9),
                            java.util.Map.of());
            reranked.add(new edu.zjnu.interview.ai.rag.rerank.ScoredCandidate(candidate,
                    0.9, java.util.Map.of(), "解释"));
            i++;
        }
        edu.zjnu.interview.ai.rag.assemble.AssembledContext context =
                new edu.zjnu.interview.ai.rag.assemble.ContextAssembler().assemble("查询", reranked);
        return new edu.zjnu.interview.ai.rag.pipeline.RagAnswer("q", List.of("q"), reranked, context,
                "回答 [1]", false, 1L);
    }

    @Test
    @DisplayName("端到端：可答问题全部命中（Recall@5=1.0），不可答问题被拒答")
    void endToEndMetrics() {
        RagPipeline pipeline = pipeline();
        RagMetrics metrics = new RagEvaluator(5).evaluate(pipeline, goldenCases());

        assertEquals(3, metrics.caseCount());
        assertEquals(2, metrics.answerableCount());
        assertEquals(1, metrics.unanswerableCount());
        assertTrue(metrics.recallAtK() >= 0.999,
                "单跳问题的召回应为满分（哈希向量 + 词法双通道），实际: " + metrics.recallAtK());
        assertEquals(1.0, metrics.mrrAt10(), 1e-9,
                "正确文档应排第 1 位");
        assertEquals(1.0, metrics.refusalRate(), 1e-9,
                "不可答问题应全部被拒答（绝对刻度阈值）");
        assertEquals(0.0, metrics.falseRefusalRate(), 1e-9,
                "可答问题不应被误拒");
        assertTrue(metrics.avgContextTokens() > 0);
    }

    @Test
    @DisplayName("分组指标：易例 / 难例 / 不可答三组各自归集")
    void groupMetricsBucketing() {
        RagPipeline pipeline = pipeline();
        RagMetrics metrics = new RagEvaluator(5).evaluate(pipeline, goldenCases());

        assertEquals(3, metrics.groups().size());
        RagMetrics.GroupMetrics easy = metrics.groups().get(0);
        RagMetrics.GroupMetrics hard = metrics.groups().get(1);
        RagMetrics.GroupMetrics unanswerable = metrics.groups().get(2);

        assertEquals(2, easy.caseCount());
        assertEquals(0, hard.caseCount());
        assertEquals(1, unanswerable.caseCount());
        assertEquals(1, unanswerable.unanswerableCount());
        assertEquals(1.0, unanswerable.refusalRate(), 1e-9);
    }

    @Test
    @DisplayName("评测明细逐条可追溯：每条 case 都能拿到召回文档序列与得分")
    void caseResultsAreTraceable() {
        RagPipeline pipeline = pipeline();
        RagMetrics metrics = new RagEvaluator(5).evaluate(pipeline, goldenCases());

        assertEquals(3, metrics.cases().size());
        RagMetrics.CaseResult first = metrics.cases().get(0);
        assertEquals("g1", first.id());
        assertTrue(first.retrievedDocIds().size() <= 5, "topK=5 的去重文档序列");
        assertTrue(first.topScore() >= 0.0);
        assertTrue(first.latencyMillis() >= 0);
    }
}
