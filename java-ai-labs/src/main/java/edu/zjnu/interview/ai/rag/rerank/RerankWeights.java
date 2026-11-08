package edu.zjnu.interview.ai.rag.rerank;

/**
 * 重排特征权重。
 *
 * <p>权重不是玄学，而是「这个特征在业务上有多可信」的量化表达：
 * <ul>
 *   <li>{@code dense} 0.35：语义相似度，主信号；</li>
 *   <li>{@code bm25} 0.30：词面精确匹配，对数字与专有名词不可替代；</li>
 *   <li>{@code coverage} 0.20：查询意图被覆盖程度，纠正「词面像但答非所问」；</li>
 *   <li>{@code freshness} 0.10：知识新鲜度，对于制度类文档「旧版本比错答案更危险」；</li>
 *   <li>{@code position} 0.05：文档内位置先验（开头通常是范围与定义），权重刻意压低。</li>
 * </ul>
 *
 * @param dense     向量相似度权重
 * @param bm25      BM25 权重
 * @param coverage  查询覆盖率权重
 * @param position  位置先验权重
 * @param freshness 新鲜度权重
 * @param mmrLambda MMR 相关性/多样性权衡（1 = 完全按相关性，0 = 完全按多样性）
 */
public record RerankWeights(double dense, double bm25, double coverage, double position,
                            double freshness, double mmrLambda) {

    /** 默认权重。 */
    public static RerankWeights defaults() {
        return new RerankWeights(0.35, 0.30, 0.20, 0.05, 0.10, 0.70);
    }

    /** 五个特征的权重和（不含 MMR）。 */
    public double featureWeightSum() {
        return dense + bm25 + coverage + position + freshness;
    }
}
