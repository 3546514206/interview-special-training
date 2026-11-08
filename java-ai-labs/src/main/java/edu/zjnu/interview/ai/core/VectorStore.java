package edu.zjnu.interview.ai.core;

import java.util.List;
import java.util.Map;

/**
 * 向量存储端口。
 *
 * <p>抽象出这一层的意义：索引构建（chunk → embed → upsert）与检索（query → search）
 * 是一份代码，向量库可以是内存实现、可以是 Qdrant、可以是 Milvus / Elasticsearch。
 * 实验工程默认用内存实现跑离线回归，同时提供 Qdrant 实现连真实向量库。
 *
 * @see edu.zjnu.interview.ai.rag.store.InMemoryVectorStore
 */
public interface VectorStore {

    /** 存储标识（集合名）。 */
    String name();

    /**
     * 确保集合存在并配置好维度与距离度量。
     *
     * @param dimension 向量维度（与 embedding 模型强绑定，不可随意变更）
     * @param distance   距离度量（cosine / dot / euclid）
     */
    void ensureCollection(int dimension, String distance);

    /** 写入/覆盖向量点。 */
    void upsert(List<VectorRecord> records);

    /**
     * 向量检索。
     *
     * @param queryVector 查询向量
     * @param topK        返回条数
     * @param filter      payload 过滤条件（等值匹配），无过滤传 {@code null}
     * @return 按相似度降序的命中
     */
    List<ScoredVector> search(float[] queryVector, int topK, Map<String, Object> filter);

    /** 删除集合（实验清理用；生产实现应要求显式开关）。 */
    void deleteCollection();

    /** 集合中的向量条数。 */
    long count();

    /**
     * 一条向量记录。
     *
     * @param id      业务 id（Qdrant 要求 UUID，这里做确定性映射）
     * @param vector  向量
     * @param payload 业务字段（用于过滤与回显）
     */
    record VectorRecord(String id, float[] vector, Map<String, Object> payload) {
        public VectorRecord {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    /**
     * 一条检索命中。
     *
     * @param id      业务 id
     * @param score   相似度得分
     * @param payload 业务字段
     */
    record ScoredVector(String id, double score, Map<String, Object> payload) {
        public ScoredVector {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }
}
