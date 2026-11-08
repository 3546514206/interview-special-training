package edu.zjnu.interview.ai.rag.rerank;

import edu.zjnu.interview.ai.rag.retrieve.Candidate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 重排结果：候选 + 最终分数 + <b>可解释的分数拆解</b>。
 *
 * <p><b>为什么要拆解？</b>
 * RAG 出问题时的排查路径是「为什么这条被选上了/为什么该选的那条没被选上」。
 * 只给一个 0.73 的总分，排查只能靠猜；给出
 * {@code dense=0.82(w0.35) bm25=0.41(w0.30) coverage=0.67(w0.20) ...}
 * 之后，「是语义相似但词面不匹配」还是「词面匹配但语义偏题」一眼可见，
 * 调参也从「拍脑袋」变成「有依据」。
 *
 * <p>这道题在面试里是加分点：它证明候选人<b>理解 RAG 的瓶颈在排序而不在召回</b>，
 * 并且有把黑盒变成可观测组件的工程意识。
 *
 * @param candidate   候选
 * @param score       最终相关性分数（0~1，绝对刻度，可跨查询比较）
 * @param features    各特征的分项（原始值）与贡献值
 * @param explanation 人类可读的解释
 */
public record ScoredCandidate(Candidate candidate, double score, Map<String, Double> features, String explanation) {

    public ScoredCandidate {
        features = features == null ? Map.of() : Map.copyOf(features);
    }

    /** 特征名常量。 */
    public static final String FEATURE_DENSE = "dense";
    public static final String FEATURE_BM25 = "bm25";
    public static final String FEATURE_COVERAGE = "coverage";
    public static final String FEATURE_POSITION = "position";
    public static final String FEATURE_FRESHNESS = "freshness";
    public static final String FEATURE_MMR_PENALTY = "mmrPenalty";
    public static final String FEATURE_FINAL = "final";

    /** 某个特征的分项值。 */
    public double feature(String name) {
        return features.getOrDefault(name, 0.0);
    }

    /** 便捷访问：所属文档 id。 */
    public String docId() {
        return candidate.docId();
    }

    /** 便捷访问：片段 id。 */
    public String chunkId() {
        return candidate.chunkId();
    }

    /** 生成解释文本（带各特征的权重贡献）。 */
    static String explain(Map<String, Double> features, Map<String, Double> contributions, double score) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> entry : contributions.entrySet()) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            double raw = features.getOrDefault(entry.getKey(), 0.0);
            sb.append(String.format("%s=%.3f(×%.2f)", entry.getKey(), raw, entry.getValue()));
        }
        sb.append(String.format(" ⇒ %.3f", score));
        return sb.toString();
    }

    /** 构造一个带解释的结果。 */
    static ScoredCandidate of(Candidate candidate, double score,
                              Map<String, Double> features, Map<String, Double> contributions) {
        Map<String, Double> all = new LinkedHashMap<>(features);
        all.put(FEATURE_FINAL, score);
        return new ScoredCandidate(candidate, score, all, explain(features, contributions, score));
    }
}
