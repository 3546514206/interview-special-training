package edu.zjnu.interview.ai.rag.retrieve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合召回器：dense 与 sparse 两路召回 + RRF 融合。
 *
 * <p><b>为什么用 RRF 而不是「归一化后加权求和」？</b>
 * 两路分数的尺度完全不同：余弦相似度在 [0,1]，BM25 随语料规模可以到几十。
 * 加权求和前必须归一化，而 min-max 归一化对「一批分数挤在一起」的查询极不稳定
 * （此时分母趋近 0，微小差异会被放大成排名剧变）。
 * RRF 只看<b>排名</b>不看分数：{@code score(d) = Σ_c w_c / (k + rank_c(d))}，
 * 天然免疫尺度问题与分数分布，是工业界做异构召回融合的默认选择。
 *
 * <p><b>k = 60 的含义</b>：k 越大越「平」（头部排名的差距被压缩），
 * k 越小越「陡」（第一名几乎独吞）。60 是原论文的经验值，效果稳定，
 * 本模块把它做成可配置项，方便实验里做敏感性分析。
 *
 * <p><b>权重</b>：dense 与 sparse 的权重内部会归一化（除以两者之和），
 * 这样融合分始终落在 (0, 1] 量级，便于与后续的特征分数放在同一把尺子上比较。
 */
public final class HybridRetriever implements Retriever {

    /** RRF 默认常数。 */
    public static final int DEFAULT_RRF_K = 60;

    private final Retriever dense;
    private final Retriever sparse;
    private final double denseWeight;
    private final double sparseWeight;
    private final int rrfK;

    public HybridRetriever(Retriever dense, Retriever sparse) {
        this(dense, sparse, 0.5, 0.5, DEFAULT_RRF_K);
    }

    /**
     * @param dense        向量召回器
     * @param sparse       词法召回器
     * @param denseWeight  向量权重
     * @param sparseWeight 词法权重
     * @param rrfK         RRF 常数
     */
    public HybridRetriever(Retriever dense, Retriever sparse,
                           double denseWeight, double sparseWeight, int rrfK) {
        this.dense = dense;
        this.sparse = sparse;
        double sum = denseWeight + sparseWeight;
        if (sum <= 0) {
            this.denseWeight = 0.5;
            this.sparseWeight = 0.5;
        } else {
            this.denseWeight = denseWeight / sum;
            this.sparseWeight = sparseWeight / sum;
        }
        this.rrfK = Math.max(1, rrfK);
    }

    @Override
    public List<Candidate> retrieve(String query, int topK) {
        return retrieve(query, topK, null);
    }

    @Override
    public List<Candidate> retrieve(String query, int topK, Map<String, Object> filter) {
        if (topK <= 0) {
            return List.of();
        }
        // 每路多取一些：融合前的召回池太浅，RRF 只能在很小的候选集里排序
        int pool = Math.max(topK * 3, 10);
        List<Candidate> denseHits = dense == null ? List.of() : dense.retrieve(query, pool, filter);
        List<Candidate> sparseHits = sparse == null ? List.of() : sparse.retrieve(query, pool, filter);

        Map<String, Candidate> byId = new LinkedHashMap<>();
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, Map<String, Double>> channels = new LinkedHashMap<>();

        accumulate(byId, channels, fused, denseHits, denseWeight, Candidate.CHANNEL_DENSE, rrfK);
        accumulate(byId, channels, fused, sparseHits, sparseWeight, Candidate.CHANNEL_BM25, rrfK);

        List<Candidate> out = new ArrayList<>(byId.size());
        for (Map.Entry<String, Candidate> entry : byId.entrySet()) {
            Map<String, Double> scores = new LinkedHashMap<>(channels.get(entry.getKey()));
            scores.put(Candidate.CHANNEL_FUSION, fused.get(entry.getKey()));
            out.add(entry.getValue().withScores(scores));
        }
        out.sort(Comparator.comparingDouble(Candidate::fusionScore).reversed()
                .thenComparing(Candidate::chunkId));
        return out.size() <= topK ? out : new ArrayList<>(out.subList(0, topK));
    }

    private static void accumulate(Map<String, Candidate> byId,
                                   Map<String, Map<String, Double>> channels,
                                   Map<String, Double> fused,
                                   List<Candidate> hits,
                                   double weight,
                                   String channel,
                                   int rrfK) {
        for (int i = 0; i < hits.size(); i++) {
            Candidate candidate = hits.get(i);
            int rank = i + 1;
            byId.putIfAbsent(candidate.chunkId(), candidate);
            channels.computeIfAbsent(candidate.chunkId(), k -> new LinkedHashMap<>())
                    .put(channel, candidate.channelScores().getOrDefault(channel, candidate.score()));
            fused.merge(candidate.chunkId(), weight / ((double) rrfK + rank), Double::sum);
        }
    }

    /** RRF 常数（便于测试与实验）。 */
    public int rrfK() {
        return rrfK;
    }

    /** 归一化后的向量通道权重。 */
    public double denseWeight() {
        return denseWeight;
    }

    /** 归一化后的词法通道权重。 */
    public double sparseWeight() {
        return sparseWeight;
    }
}
