package edu.zjnu.interview.ai.rag.retrieve;

import edu.zjnu.interview.ai.core.KnowledgeBase;
import edu.zjnu.interview.ai.rag.index.KnowledgeIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量召回器（dense）。
 *
 * <p>走 {@link KnowledgeIndex#denseSearch}，得分是余弦相似度。
 * 优点是能召回「字面不重合但意思相近」的片段；缺点是对专有名词与数字不敏感。
 */
public final class DenseRetriever implements Retriever {

    private final KnowledgeIndex index;

    public DenseRetriever(KnowledgeIndex index) {
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
        List<KnowledgeBase.Hit> hits = index.denseSearch(query, topK, filter);
        List<Candidate> candidates = new ArrayList<>(hits.size());
        for (KnowledgeBase.Hit hit : hits) {
            candidates.add(Candidate.fromHit(Candidate.CHANNEL_DENSE, hit.score(), hit));
        }
        return candidates;
    }
}
