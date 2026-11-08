package edu.zjnu.interview.ai.rag.index;

import edu.zjnu.interview.ai.core.EmbeddingModel;
import edu.zjnu.interview.ai.core.KnowledgeBase;
import edu.zjnu.interview.ai.core.VectorStore;
import edu.zjnu.interview.ai.rag.Chunk;
import edu.zjnu.interview.ai.rag.store.Bm25Index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识索引：对外提供 dense / keyword / hybrid 三种检索能力。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>索引构建与检索分离</b>：{@link IndexBuilder} 负责「切分 → 向量化 → 落库」，
 *       本类只负责「查」。这样换检索策略（加一路稀疏检索、加一路 rerank）不需要重建索引；</li>
 *   <li><b>对外契约复用 core 的 {@link KnowledgeBase}</b>：Agent 侧只依赖这个接口，
 *       完全不知道底下是内存实现还是 Qdrant；</li>
 *   <li><b>片段内容在索引侧缓存</b>：向量库 payload 里存了一份内容，BM25 侧也存在一份，
 *       这里统一以 {@code chunkId} 为准做回填，保证两条检索通道返回的内容完全一致。</li>
 * </ul>
 */
public final class KnowledgeIndex implements KnowledgeBase {

    /** RRF 融合常数。 */
    public static final int RRF_K = 60;

    private final VectorStore vectorStore;
    private final Bm25Index bm25;
    private final EmbeddingModel embeddingModel;
    private final Map<String, Chunk> chunksById = new LinkedHashMap<>();

    public KnowledgeIndex(VectorStore vectorStore, Bm25Index bm25, EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.bm25 = bm25;
        this.embeddingModel = embeddingModel;
    }

    /** 注册片段（供检索时回填内容）。 */
    public void register(Chunk chunk) {
        chunksById.put(chunk.id(), chunk);
    }

    /** 已注册的片段数。 */
    public int chunkCount() {
        return chunksById.size();
    }

    /** 向量库（用于实验统计与后端切换）。 */
    public VectorStore vectorStore() {
        return vectorStore;
    }

    /** BM25 索引。 */
    public Bm25Index bm25() {
        return bm25;
    }

    /**
     * 向量检索（dense）。
     *
     * @param query  查询
     * @param topK   返回条数
     * @param filter payload 过滤
     */
    public List<Hit> denseSearch(String query, int topK, Map<String, Object> filter) {
        if (embeddingModel == null || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        float[] vector = embeddingModel.embed(query);
        List<VectorStore.ScoredVector> found = vectorStore.search(vector, topK, filter);
        List<Hit> hits = new ArrayList<>(found.size());
        for (VectorStore.ScoredVector scored : found) {
            hits.add(toHit(scored.id(), scored.score(), scored.payload()));
        }
        return hits;
    }

    /** 向量检索（不过滤）。 */
    public List<Hit> denseSearch(String query, int topK) {
        return denseSearch(query, topK, null);
    }

    /**
     * 词法检索（BM25）。
     *
     * @param query  查询
     * @param topK   返回条数
     * @param filter payload 过滤
     */
    public List<Hit> keywordSearch(String query, int topK, Map<String, Object> filter) {
        if (bm25 == null || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        List<Bm25Index.Scored> found = bm25.search(query, topK, filter);
        List<Hit> hits = new ArrayList<>(found.size());
        for (Bm25Index.Scored scored : found) {
            Chunk chunk = chunksById.get(scored.businessId());
            hits.add(new Hit(scored.businessId(),
                    chunk == null ? scored.docId() : chunk.title(),
                    chunk == null ? "" : chunk.content(),
                    scored.score(),
                    chunk == null ? Map.of() : chunk.metadata()));
        }
        return hits;
    }

    /** 词法检索（不过滤）。 */
    public List<Hit> keywordSearch(String query, int topK) {
        return keywordSearch(query, topK, null);
    }

    /**
     * 混合检索（RRF 融合）。
     *
     * <p>RRF（Reciprocal Rank Fusion）只吃<b>排名</b>不吃分数，这一点非常关键：
     * 余弦相似度在 [0,1]、BM25 分数在 [0,30]、不同模型的分数尺度完全不同，
     * 直接加权求和需要先做归一化，而归一化对「一批分数都挤在一起」的查询极不稳定。
     * 用排名融合则天然免疫尺度问题——这正是 RRF 在工业界被广泛使用的原因。
     */
    @Override
    public List<Hit> search(String query, int topK) {
        return hybridSearch(query, topK, null, 0.5, 0.5, RRF_K);
    }

    /**
     * 混合检索（带权 RRF）。
     *
     * @param query       查询
     * @param topK        返回条数
     * @param filter      payload 过滤
     * @param denseWeight dense 权重
     * @param sparseWeight 稀疏权重
     * @param rrfK        RRF 常数
     */
    public List<Hit> hybridSearch(String query, int topK, Map<String, Object> filter,
                                  double denseWeight, double sparseWeight, int rrfK) {
        List<Hit> dense = denseSearch(query, Math.max(topK, 10), filter);
        List<Hit> sparse = keywordSearch(query, Math.max(topK, 10), filter);
        double sum = denseWeight + sparseWeight;
        if (sum <= 0) {
            denseWeight = 0.5;
            sparseWeight = 0.5;
            sum = 1.0;
        }
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, Hit> hitById = new LinkedHashMap<>();
        accumulate(fused, hitById, dense, denseWeight / sum, rrfK);
        accumulate(fused, hitById, sparse, sparseWeight / sum, rrfK);

        List<Map.Entry<String, Double>> entries = new ArrayList<>(fused.entrySet());
        entries.sort((a, b) -> {
            int cmp = Double.compare(b.getValue(), a.getValue());
            return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
        });
        List<Hit> out = new ArrayList<>(Math.min(topK, entries.size()));
        for (Map.Entry<String, Double> entry : entries) {
            if (out.size() >= topK) {
                break;
            }
            Hit hit = hitById.get(entry.getKey());
            out.add(new Hit(hit.id(), hit.title(), hit.content(), entry.getValue(), hit.metadata()));
        }
        return out;
    }

    private static void accumulate(Map<String, Double> fused, Map<String, Hit> hitById,
                                   List<Hit> hits, double weight, int rrfK) {
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            int rank = i + 1;
            fused.merge(hit.id(), weight / (rrfK + rank), Double::sum);
            hitById.putIfAbsent(hit.id(), hit);
        }
    }

    private Hit toHit(String chunkId, double score, Map<String, Object> payload) {
        Chunk chunk = chunksById.get(chunkId);
        String title = chunk != null ? chunk.title() : String.valueOf(payload.getOrDefault("title", ""));
        String content = chunk != null ? chunk.content() : String.valueOf(payload.getOrDefault("content", ""));
        Map<String, Object> metadata = chunk != null ? chunk.metadata() : payload;
        return new Hit(chunkId, title, content, score, metadata);
    }
}
