package edu.zjnu.interview.ai.rag.retrieve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 混合召回（RRF 融合）测试。
 *
 * <p>用假召回器精确断言 RRF 数学：融合分只由<b>排名</b>与权重决定，
 * 与各通道的原始分数尺度无关——这正是选择 RRF 而不是加权求和的原因。
 */
class HybridRetrieverTest {

    private static Candidate candidate(String chunkId, String docId, double denseScore) {
        return new Candidate(chunkId, docId, "标题-" + docId, "内容-" + chunkId, "src", 0,
                Map.of(Candidate.CHANNEL_DENSE, denseScore), Map.of());
    }

    /** 固定顺序返回候选的假召回器。 */
    private static Retriever fixed(List<Candidate> hits) {
        return new Retriever() {
            @Override
            public List<Candidate> retrieve(String query, int topK) {
                return hits;
            }
        };
    }

    @Test
    @DisplayName("RRF 数学：两路都召回的候选得分 = 各通道 w/(k+rank) 之和")
    void rrfFusionMath() {
        Retriever dense = fixed(List.of(candidate("A", "d1", 0.9), candidate("B", "d2", 0.8)));
        Retriever sparse = fixed(List.of(candidate("C", "d3", 0.0), candidate("A", "d1", 0.0)));
        HybridRetriever hybrid = new HybridRetriever(dense, sparse, 0.5, 0.5, HybridRetriever.DEFAULT_RRF_K);

        List<Candidate> fused = hybrid.retrieve("查询", 3);

        double aExpected = 0.5 / (60 + 1) + 0.5 / (60 + 2);
        double bExpected = 0.5 / (60 + 2);
        double cExpected = 0.5 / (60 + 1);

        assertEquals("A", fused.get(0).chunkId());
        assertEquals(aExpected, fused.get(0).fusionScore(), 1e-12);
        // B 只在 dense 第 2 名，C 只在 sparse 第 1 名 → C 高于 B
        assertEquals(cExpected, fused.get(1).fusionScore(), 1e-12);
        assertEquals(bExpected, fused.get(2).fusionScore(), 1e-12);
        // 分通道得分被保留（重排需要）
        assertEquals(0.9, fused.get(0).denseScore(), 1e-12);
        assertEquals(0.0, fused.get(0).bm25Score(), 1e-12);
    }

    @Test
    @DisplayName("权重归一：0.7/0.3 与 7/3 等价")
    void weightsAreNormalized() {
        Retriever dense = fixed(List.of(candidate("A", "d1", 0.9)));
        Retriever sparse = fixed(List.of(candidate("B", "d2", 0.0)));

        assertEquals(new HybridRetriever(dense, sparse, 0.7, 0.3, 60).denseWeight(),
                new HybridRetriever(dense, sparse, 7.0, 3.0, 60).denseWeight(), 1e-12);
    }

    @Test
    @DisplayName("单通道加权：dense 权重 1.0 时 BM25 通道不影响排名")
    void singleChannelDominatesWithFullWeight() {
        Retriever dense = fixed(List.of(candidate("A", "d1", 0.9), candidate("B", "d2", 0.8)));
        Retriever sparse = fixed(List.of(candidate("C", "d3", 0.0)));
        HybridRetriever hybrid = new HybridRetriever(dense, sparse, 1.0, 0.0, 60);

        List<Candidate> fused = hybrid.retrieve("查询", 3);

        assertEquals("A", fused.get(0).chunkId());
        assertEquals("B", fused.get(1).chunkId());
        assertEquals(1.0 / 61, fused.get(0).fusionScore(), 1e-12);
    }

    @Test
    @DisplayName("RRF 只吃排名：调高 dense 的原始分数不改变融合序")
    void fusionIgnoresRawScoreMagnitude() {
        // 两个 dense 候选分数差距悬殊，但排名相同 → 融合分必须相同
        Retriever first = fixed(List.of(candidate("A", "d1", 0.95), candidate("B", "d2", 0.50)));
        Retriever second = fixed(List.of(candidate("A", "d1", 0.99), candidate("B", "d2", 0.10)));
        HybridRetriever h1 = new HybridRetriever(first, null, 1.0, 0.0, 60);
        HybridRetriever h2 = new HybridRetriever(second, null, 1.0, 0.0, 60);

        assertEquals(h1.retrieve("q", 5).get(0).fusionScore(),
                h2.retrieve("q", 5).get(0).fusionScore(), 1e-12);
    }

    @Test
    @DisplayName("每路多取候选：融合池至少 10 条，避免浅池排序失真")
    void poolIsWiderThanTopK() {
        // 假召回器返回 12 条；topK=2 时融合仍应覆盖全部 12 条的排名信息
        List<Candidate> wide = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            wide.add(candidate("c" + i, "d" + i, 0.5));
        }
        HybridRetriever hybrid = new HybridRetriever(fixed(wide), null, 1.0, 0.0, 60);

        List<Candidate> fused = hybrid.retrieve("q", 2);

        assertEquals(2, fused.size());
        assertEquals("c0", fused.get(0).chunkId());
        assertEquals("c1", fused.get(1).chunkId());
        assertTrue(fused.get(0).fusionScore() > fused.get(1).fusionScore());
    }
}
