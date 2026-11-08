package edu.zjnu.interview.ai.rag.rerank;

import edu.zjnu.interview.ai.rag.retrieve.Candidate;

import java.util.List;

/**
 * 重排器端口。
 *
 * <p>重排（rerank）是召回之后、上下文组装之前的最后一道排序关卡：
 * 召回追求「不漏」（Recall@k 高），重排追求「准」（把真正能回答问题的片段排到最前）。
 * 两者的优化目标不同，所以必须是两个阶段、两个指标。
 */
public interface Reranker {

    /**
     * 重排。
     *
     * @param query      查询
     * @param candidates 召回候选
     * @param topK       保留条数
     * @return 按分数降序的结果（带可解释分项）
     */
    List<ScoredCandidate> rerank(String query, List<Candidate> candidates, int topK);
}
