package edu.zjnu.interview.ai.rag.retrieve;

import edu.zjnu.interview.ai.core.KnowledgeBase;
import edu.zjnu.interview.ai.rag.index.KnowledgeIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 词法召回器（BM25）。
 *
 * <p>对「实缴资本 1000 万元」「R3 产品 6%」这类必须字面命中的事实，
 * 它的表现往往比向量检索更稳——语义相近但数值不同的邻居，正是向量检索最容易踩的坑。
 */
public final class SparseRetriever implements Retriever {

    private final KnowledgeIndex index;

    public SparseRetriever(KnowledgeIndex index) {
        this.index = index;
    }

    @Override
    public List<Candidate> retrieve(String query, int topK) {
        return retrieve(query, topK, null);
    }

    @Override
    public List<Candidate> retrieve(String query, int topK, Map<String, Object> filter) {
        if (index == null || topK <= 0) {
            return List.of();
        }
        List<KnowledgeBase.Hit> hits = index.keywordSearch(query, topK, filter);
        List<Candidate> candidates = new ArrayList<>(hits.size());
        for (KnowledgeBase.Hit hit : hits) {
            candidates.add(Candidate.fromHit(Candidate.CHANNEL_BM25, hit.score(), hit));
        }
        return candidates;
    }
}
