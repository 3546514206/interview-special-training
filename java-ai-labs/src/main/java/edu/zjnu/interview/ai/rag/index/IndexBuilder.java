package edu.zjnu.interview.ai.rag.index;

import edu.zjnu.interview.ai.core.EmbeddingModel;
import edu.zjnu.interview.ai.core.VectorStore;
import edu.zjnu.interview.ai.rag.Chunk;
import edu.zjnu.interview.ai.rag.Chunker;
import edu.zjnu.interview.ai.rag.Document;
import edu.zjnu.interview.ai.rag.store.Bm25Index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引构建器：文档 → 切分 → 向量化 → 落库 → 建 BM25 倒排。
 *
 * <p>这是 RAG 工程里最「重」的一步（要调用 embedding 模型），所以刻意把它和检索彻底分开：
 * 构建一次、检索无数次。索引是可缓存的产物（生产环境一般离线构建 + 定时刷新），
 * 而检索是在线链路，两者混在一起会让「重建索引」变成一次用户请求。
 *
 * <p><b>向量化文本 = 标题 + 正文</b>：标题往往是文档最凝练的语义摘要，
 * 把标题拼进向量输入，能显著提升「问题用词接近标题」这类查询的召回。
 *
 * <p><b>payload 里存什么</b>：除了用于过滤的字段（docId / category / source），
 * 还存了 content 与 title——这样检索侧不需要回查业务库就能拿到正文，
 * 也让「向量库自包含」成为可能（换后端时不用再补一套主数据查询）。
 */
public final class IndexBuilder {

    private final Chunker chunker;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final String distance;

    public IndexBuilder(Chunker chunker, EmbeddingModel embeddingModel, VectorStore vectorStore, String distance) {
        this.chunker = chunker == null ? new Chunker() : chunker;
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.distance = distance == null || distance.isBlank() ? "Cosine" : distance;
    }

    /** 构建索引。 */
    public KnowledgeIndex build(List<Document> documents) {
        if (embeddingModel == null || vectorStore == null) {
            throw new IllegalArgumentException("embedding 模型与向量库不能为空");
        }
        vectorStore.ensureCollection(embeddingModel.dimensions(), distance);

        List<Chunk> chunks = chunker.chunkAll(documents);
        List<VectorStore.VectorRecord> records = new ArrayList<>(chunks.size());
        Bm25Index bm25 = new Bm25Index();

        for (Chunk chunk : chunks) {
            String embeddingText = embeddingText(chunk);
            float[] vector = embeddingModel.embed(embeddingText);
            records.add(new VectorStore.VectorRecord(chunk.id(), vector, payload(chunk)));
            // BM25 索引标题 + 正文：标题里的专有名词往往正是用户提问时的用词
            bm25.add(chunk.id(), chunk.docId(), chunk.title(),
                    chunk.title() + "\n" + chunk.content(), chunk.metadata());
        }
        vectorStore.upsert(records);

        KnowledgeIndex index = new KnowledgeIndex(vectorStore, bm25, embeddingModel);
        for (Chunk chunk : chunks) {
            index.register(chunk);
        }
        return index;
    }

    /** 向量化输入：标题 + 正文。 */
    private String embeddingText(Chunk chunk) {
        return chunk.title() + "\n" + chunk.content();
    }

    /** payload：过滤字段 + 回显字段。 */
    private Map<String, Object> payload(Chunk chunk) {
        Map<String, Object> payload = new LinkedHashMap<>(chunk.metadata());
        payload.put("chunkId", chunk.id());
        payload.put("docId", chunk.docId());
        payload.put("title", chunk.title());
        payload.put("content", chunk.content());
        payload.put("ordinal", chunk.ordinal());
        return payload;
    }
}
