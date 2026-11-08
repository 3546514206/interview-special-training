package edu.zjnu.interview.ai.rag.rerank;

import edu.zjnu.interview.ai.rag.retrieve.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 特征重排器测试。
 *
 * <p>重排是 RAG 效果的主战场，这里锁定四个核心行为：
 * 分数拆解可解释、不可用特征权重剔除并重新归一、BM25 饱和变换单调有界、
 * MMAR（MMR）在近重复候选中保住多样性。
 */
class FeatureRerankerTest {

    private static Candidate candidate(String chunkId, String docId, String content,
                                       double dense, double bm25, int ordinal) {
        Map<String, Double> scores = Map.of(
                Candidate.CHANNEL_DENSE, dense,
                Candidate.CHANNEL_BM25, bm25);
        return new Candidate(chunkId, docId, "标题", content, "src", ordinal, scores, Map.of());
    }

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("分数拆解：解释文本包含各特征值与权重贡献")
    void explanationContainsFeatureBreakdown() {
        FeatureReranker reranker = new FeatureReranker(RerankWeights.defaults(), FIXED);
        Candidate candidate = candidate("c1", "d1", "私募资产准入要求实缴资本 1000 万元", 0.9, 12.0, 0);

        List<ScoredCandidate> result = reranker.rerank("私募资产准入的实缴资本门槛", List.of(candidate), 1);

        String explanation = result.get(0).explanation();
        assertTrue(explanation.contains("dense="), explanation);
        assertTrue(explanation.contains("bm25="), explanation);
        assertTrue(explanation.contains("coverage="), explanation);
        assertTrue(explanation.contains("⇒"), explanation);
        assertTrue(result.get(0).score() > 0);
        assertEquals(0.9, result.get(0).feature(ScoredCandidate.FEATURE_DENSE), 1e-9);
    }

    @Test
    @DisplayName("不可用特征剔除权重：dense 全为 0 时解释里没有 dense 项")
    void unavailableFeatureWeightRemoved() {
        FeatureReranker reranker = new FeatureReranker(RerankWeights.defaults(), FIXED);
        // 所有候选 dense 都是 0（模拟 dense-only 关闭的召回通道）
        Candidate a = candidate("a", "d1", "准入门槛是 1000 万元", 0.0, 16.0, 0);
        Candidate b = candidate("b", "d2", "风险识别红线清单", 0.0, 2.0, 0);

        List<ScoredCandidate> result = reranker.rerank("准入门槛", List.of(a, b), 2);

        assertTrue(result.get(0).score() > result.get(1).score());
        for (ScoredCandidate scored : result) {
            assertTrue(!scored.explanation().contains("dense=")
                            || scored.feature(ScoredCandidate.FEATURE_DENSE) > 0,
                    "dense 特征不可用时不应参与加权: " + scored.explanation());
        }
    }

    @Test
    @DisplayName("BM25 饱和变换：单调、有界 (0,1)")
    void bm25SaturationIsMonotonicAndBounded() {
        assertEquals(0.0, FeatureReranker.saturate(0.0), 1e-12);
        assertEquals(0.5, FeatureReranker.saturate(FeatureReranker.BM25_SCALE), 1e-12);
        assertTrue(FeatureReranker.saturate(16.0) > FeatureReranker.saturate(8.0));
        assertTrue(FeatureReranker.saturate(80.0) < 1.0);
    }

    @Test
    @DisplayName("MMR 去冗余：近重复候选让位给内容不同的候选")
    void mmrKeepsDiversity() {
        // a 与 b 内容几乎相同；c 内容不同但分数略低
        FeatureReranker diverse = new FeatureReranker(
                new RerankWeights(0.35, 0.30, 0.20, 0.05, 0.10, 0.30), FIXED);
        Candidate a = candidate("a", "d1", "私募资产准入的实缴资本要求是不低于 1000 万元人民币", 0.90, 10.0, 0);
        Candidate b = candidate("b", "d1", "私募资产准入的实缴资本要求是不低于 1000 万元人民币整", 0.88, 9.0, 1);
        Candidate c = candidate("c", "d2", "风险识别的红线包括洗钱欺诈与市场操纵行为", 0.55, 4.0, 0);

        List<ScoredCandidate> selected = diverse.rerank("私募准入与风险", List.of(a, b, c), 2);

        assertEquals("a", selected.get(0).chunkId(), "最高分候选必选");
        assertEquals("c", selected.get(1).chunkId(),
                "近重复的 b 应被 MMR 惩罚让位给内容不同的 c，实际选中: " + selected.get(1).chunkId());
        assertTrue(selected.get(1).feature(ScoredCandidate.FEATURE_MMR_PENALTY) < 0.1,
                "c 与已选内容不同，惩罚应接近 0");
    }

    @Test
    @DisplayName("纯相关性（mmrLambda=1）时退化为按分数排序")
    void pureRelevanceModeIgnoresMmr() {
        FeatureReranker pure = new FeatureReranker(
                new RerankWeights(0.35, 0.30, 0.20, 0.05, 0.10, 1.0), FIXED);
        Candidate a = candidate("a", "d1", "内容甲", 0.9, 10.0, 0);
        Candidate b = candidate("b", "d2", "内容甲", 0.8, 9.0, 0);

        List<ScoredCandidate> selected = pure.rerank("查询", List.of(a, b), 2);

        assertEquals("a", selected.get(0).chunkId());
        assertEquals("b", selected.get(1).chunkId());
    }

    @Test
    @DisplayName("绝对刻度：无关查询得分显著低于相关查询（可跨查询比较、可设拒答阈值）")
    void absoluteScaleAcrossQueries() {
        FeatureReranker reranker = new FeatureReranker(RerankWeights.defaults(), FIXED);
        Candidate related = candidate("a", "d1", "私募资产准入要求实缴资本不低于 1000 万元", 0.82, 14.0, 0);
        // 无关查询对无关内容：dense 余弦很低、BM25 无命中 → 分数只剩中性位置先验与新鲜度
        Candidate unrelatedContent = candidate("b", "d2", "投顾服务流程需要适当性评估", 0.15, 2.0, 0);

        double relatedScore = reranker.rerank("私募准入的实缴资本门槛", List.of(related), 1).get(0).score();
        double unrelatedScore = reranker.rerank("数字人民币钱包年度限额", List.of(unrelatedContent), 1).get(0).score();

        assertTrue(relatedScore > 0.28, "相关查询应高于常见拒答阈值: " + relatedScore);
        assertTrue(unrelatedScore < 0.28, "无关查询应低于拒答阈值: " + unrelatedScore);
        // 关键点：分数不经过 min-max 归一，无关查询不会「永远是当批最高分 1.0」
        assertTrue(unrelatedScore < 0.9, "min-max 归一化的危险就在于此：无关查询也会得到高分");
    }

    @Test
    @DisplayName("新鲜度：epoch 与 ISO 日期都能解析，老文档得分衰减")
    void freshnessDecay() {
        Candidate old = candidate("a", "d1", "内容", 0.5, 5.0, 0);
        Candidate fresh = new Candidate("b", "d2", "标题", "内容", "src", 0,
                Map.of(Candidate.CHANNEL_DENSE, 0.5, Candidate.CHANNEL_BM25, 5.0),
                Map.of("updatedAt", "2025-12-31"));

        FeatureReranker reranker = new FeatureReranker(RerankWeights.defaults(), FIXED);
        assertTrue(reranker.freshness(fresh) > reranker.freshness(old),
                "昨天更新的文档应比无时间戳（中性 0.5）的文档更新鲜");
        assertEquals(0.5, reranker.freshness(old), 1e-9, "无时间戳返回中性 0.5");

        // 半衰期：180 天前的文档 ≈ 0.5
        Candidate halfLife = new Candidate("c", "d3", "标题", "内容", "src", 0,
                Map.of(), Map.of("updatedAt", "2025-07-05T00:00:00Z"));
        double freshness = reranker.freshness(halfLife);
        assertTrue(freshness > 0.4 && freshness < 0.6, "约一个半衰期的文档应衰减到 0.5 附近: " + freshness);
    }

    @Test
    @DisplayName("updatedAt 支持多种格式")
    void updatedAtParsing() {
        assertEquals(1577836800000L, FeatureReranker.parseEpoch("2020-01-01T00:00:00Z"));
        assertEquals(java.time.LocalDate.parse("2020-01-01")
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                FeatureReranker.parseEpoch("2020-01-01"));
        assertEquals(1234567890123L, FeatureReranker.parseEpoch(1234567890123L));
        assertEquals(null, FeatureReranker.parseEpoch("不是时间"));
        assertEquals(null, FeatureReranker.parseEpoch(null));
    }
}
