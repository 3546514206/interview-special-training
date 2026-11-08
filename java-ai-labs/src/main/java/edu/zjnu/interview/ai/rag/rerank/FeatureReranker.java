package edu.zjnu.interview.ai.rag.rerank;

import edu.zjnu.interview.ai.rag.retrieve.Candidate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 特征加权重排器 + MMR 多样性选择。
 *
 * <p><b>特征</b>（全部映射到 [0,1] 的绝对刻度）：
 * <ol>
 *   <li>{@code dense}：向量余弦相似度（负值截断为 0）；</li>
 *   <li>{@code bm25}：BM25 分数的饱和变换 {@code s/(s+k)}（k=8），把无上界的分数压进 [0,1)；</li>
 *   <li>{@code coverage}：查询多字词项被覆盖的比例（见 {@link Similarity}）；</li>
 *   <li>{@code position}：文档内位置先验 {@code 1/(1+ordinal)}（开头通常是范围与定义）；</li>
 *   <li>{@code freshness}：按半衰期衰减的新鲜度，缺省 0.5（中性，不奖不罚）。</li>
 * </ol>
 *
 * <p><b>为什么用绝对刻度而不是 min-max 归一化？</b>
 * 这是本模块最关键的一个设计取舍。min-max 会让「每批候选里的最高分恒等于 1」，
 * 于是<b>任何查询（包括完全无关的查询）的最高分都是 1.0</b>：
 * 分数失去了跨查询可比性，也就无法做「低于阈值就拒答」的判定——
 * 而「知识库里没有答案时必须拒答」恰恰是 RAG 落地的硬要求。
 * 绝对刻度下，无关查询的各项特征都接近 0，总分自然低于阈值。
 *
 * <p><b>权重动态归一</b>：如果某条通道（例如只启用 dense 召回时的 bm25）在全部候选上都是 0，
 * 说明该特征不可用，它的权重会被剔除并把剩余权重重新归一，避免「凭空扣掉 0.30 分」。
 * 这点在实验里很关键：dense-only 与 hybrid 的分数不在同一把尺子上，不能直接比大小。
 *
 * <p><b>MMR</b>：在最终选 topK 时用 MMR（Maximal Marginal Relevance）做去冗余，
 * {@code argmax[λ·score - (1-λ)·max_sim(候选, 已选)}。
 * 纯按分数选，很容易选出三条讲同一件事的片段，挤占上下文预算；
 * MMR 用少量相关性损失换来证据多样性。
 */
public final class FeatureReranker implements Reranker {

    /** BM25 饱和变换的尺度常数。 */
    public static final double BM25_SCALE = 8.0;

    /** 新鲜度半衰期（天）。 */
    public static final double FRESHNESS_HALF_LIFE_DAYS = 180.0;

    private final RerankWeights weights;
    private final Clock clock;

    public FeatureReranker(RerankWeights weights, Clock clock) {
        this.weights = weights == null ? RerankWeights.defaults() : weights;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public FeatureReranker() {
        this(RerankWeights.defaults(), Clock.systemUTC());
    }

    public RerankWeights weights() {
        return weights;
    }

    @Override
    public List<ScoredCandidate> rerank(String query, List<Candidate> candidates, int topK) {
        if (candidates == null || candidates.isEmpty() || topK <= 0) {
            return List.of();
        }
        boolean denseAvailable = candidates.stream().anyMatch(c -> c.denseScore() != 0.0);
        boolean bm25Available = candidates.stream().anyMatch(c -> c.bm25Score() != 0.0);

        // 权重归一：不可用的特征剔除后重新归一
        Map<String, Double> activeWeights = new LinkedHashMap<>();
        activeWeights.put(ScoredCandidate.FEATURE_DENSE, denseAvailable ? weights.dense() : 0.0);
        activeWeights.put(ScoredCandidate.FEATURE_BM25, bm25Available ? weights.bm25() : 0.0);
        activeWeights.put(ScoredCandidate.FEATURE_COVERAGE, weights.coverage());
        activeWeights.put(ScoredCandidate.FEATURE_POSITION, weights.position());
        activeWeights.put(ScoredCandidate.FEATURE_FRESHNESS, weights.freshness());
        double weightSum = activeWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (weightSum <= 0) {
            weightSum = 1.0;
        }

        List<ScoredCandidate> scored = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            Map<String, Double> features = new LinkedHashMap<>();
            features.put(ScoredCandidate.FEATURE_DENSE, clamp01(candidate.denseScore()));
            features.put(ScoredCandidate.FEATURE_BM25, saturate(candidate.bm25Score()));
            features.put(ScoredCandidate.FEATURE_COVERAGE,
                    Similarity.multiCharCoverage(query, candidate.title() + "\n" + candidate.content()));
            features.put(ScoredCandidate.FEATURE_POSITION, 1.0 / (1.0 + candidate.ordinal()));
            features.put(ScoredCandidate.FEATURE_FRESHNESS, freshness(candidate));

            Map<String, Double> contributions = new LinkedHashMap<>();
            double score = 0.0;
            for (Map.Entry<String, Double> entry : activeWeights.entrySet()) {
                if (entry.getValue() <= 0) {
                    continue;
                }
                double contribution = entry.getValue() / weightSum * features.getOrDefault(entry.getKey(), 0.0);
                contributions.put(entry.getKey(), entry.getValue() / weightSum);
                score += contribution;
            }
            scored.add(ScoredCandidate.of(candidate, score, features, contributions));
        }

        scored.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed()
                .thenComparing(ScoredCandidate::chunkId));
        return selectWithMmr(scored, topK);
    }

    /**
     * MMR 选择：在保证相关性的前提下提升证据多样性。
     *
     * @param scored 已按分数降序的候选
     * @param topK   目标条数
     */
    private List<ScoredCandidate> selectWithMmr(List<ScoredCandidate> scored, int topK) {
        double lambda = Math.min(1.0, Math.max(0.0, weights.mmrLambda()));
        List<ScoredCandidate> selected = new ArrayList<>(topK);
        List<ScoredCandidate> remaining = new ArrayList<>(scored);

        while (selected.size() < topK && !remaining.isEmpty()) {
            ScoredCandidate best = null;
            double bestValue = Double.NEGATIVE_INFINITY;
            double bestPenalty = 0.0;
            for (ScoredCandidate candidate : remaining) {
                double maxSim = 0.0;
                for (ScoredCandidate chosen : selected) {
                    maxSim = Math.max(maxSim, Similarity.multiCharJaccard(
                            candidate.candidate().content(), chosen.candidate().content()));
                }
                double value = lambda * candidate.score() - (1.0 - lambda) * maxSim;
                if (value > bestValue) {
                    bestValue = value;
                    best = candidate;
                    bestPenalty = maxSim;
                }
            }
            if (best == null) {
                break;
            }
            Map<String, Double> features = new LinkedHashMap<>(best.features());
            features.put(ScoredCandidate.FEATURE_MMR_PENALTY, bestPenalty);
            selected.add(new ScoredCandidate(best.candidate(), best.score(), features,
                    best.explanation() + String.format(" | mmr惩罚=%.3f", bestPenalty)));
            remaining.remove(best);
        }
        return selected;
    }

    /** BM25 饱和变换：把无上界的分数压到 [0,1)，且保持单调。 */
    static double saturate(double score) {
        if (score <= 0) {
            return 0.0;
        }
        return score / (score + BM25_SCALE);
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.min(1.0, Math.max(0.0, value));
    }

    /**
     * 新鲜度：按半衰期指数衰减；元数据没有 updatedAt 时返回 0.5（中性）。
     *
     * <p>时间来源注入 {@link Clock}，测试里用固定时钟即可完全复现。
     */
    double freshness(Candidate candidate) {
        Object raw = candidate.metadata() == null ? null : candidate.metadata().get("updatedAt");
        Long epoch = parseEpoch(raw);
        if (epoch == null) {
            return 0.5;
        }
        double ageDays = (clock.millis() - epoch) / (double) (24 * 60 * 60 * 1000);
        if (ageDays < 0) {
            ageDays = 0;
        }
        return Math.pow(0.5, ageDays / FRESHNESS_HALF_LIFE_DAYS);
    }

    /** 解析 updatedAt：支持 epoch 毫秒、ISO-8601 时刻、ISO 日期。 */
    static Long parseEpoch(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // 继续尝试其它格式
        }
        try {
            return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
