package edu.zjnu.interview.ai.rag.store;

import edu.zjnu.interview.ai.core.Ids;
import edu.zjnu.interview.ai.core.Text;
import edu.zjnu.interview.ai.core.VectorStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存向量库（暴力余弦检索）。
 *
 * <p><b>定位</b>：默认后端。让「索引 → 召回 → 重排 → 评测」在没有向量库、没有 API Key、
 * 没有外网的环境下完整跑通且结果可复现。Qdrant 只是<b>同一个端口的另一种实现</b>。
 *
 * <p><b>为什么用暴力检索？</b>
 * 几百条片段的规模下，向量库索引（HNSW/IVF）的召回率优势还没体现出来，
 * 而暴力检索是<b>精确</b>的：同样的输入必然得到同样的排序，不存在近似检索的抖动。
 * 评测要能断言「Recall@5 = 0.8」这种精确值，就必须有一个确定性的基线。
 *
 * <p><b>point id 语义</b>：与 Qdrant 实现保持一致——业务 id（chunkId）在内部被映射成
 * {@code Ids.deterministicUuid(ns, businessId)} 作为 point 主键，
 * 这样 upsert 才能幂等覆盖，且两套实现的 id 空间完全对齐。
 */
public final class InMemoryVectorStore implements VectorStore {

    /** point id 命名空间（与 Qdrant 实现保持一致）。 */
    public static final String POINT_NAMESPACE = "rag-point";

    private final String name;
    private final Map<String, Entry> points = new LinkedHashMap<>();
    private int dimension = -1;
    private String distance = "Cosine";

    public InMemoryVectorStore(String name) {
        this.name = name == null || name.isBlank() ? "in-memory" : name;
    }

    public InMemoryVectorStore() {
        this("in-memory");
    }

    @Override
    public String name() {
        return name;
    }

    /** 当前集合的向量维度（未建集合时为 -1）。 */
    public int dimension() {
        return dimension;
    }

    @Override
    public void ensureCollection(int dimension, String distance) {
        if (this.dimension > 0 && this.dimension != dimension && !points.isEmpty()) {
            // 这是 RAG 工程最常见的事故：换了 embedding 模型却没重建集合。
            // 这里宁可启动失败，也不要让「维度不同的向量被塞进同一个集合」这种脏数据悄悄流下去。
            throw new IllegalStateException("集合 " + name + " 已存在且维度为 " + this.dimension
                    + "，无法改为 " + dimension + "：换 embedding 模型必须重建集合（先 deleteCollection）");
        }
        this.dimension = dimension;
        this.distance = (distance == null || distance.isBlank()) ? "Cosine" : distance;
    }

    @Override
    public void upsert(List<VectorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        if (dimension <= 0) {
            throw new IllegalStateException("集合尚未初始化：请先调用 ensureCollection");
        }
        for (VectorRecord record : records) {
            if (record.vector() == null || record.vector().length != dimension) {
                throw new IllegalStateException("向量维度不匹配：集合 " + name + " 维度为 " + dimension
                        + "，实际写入 " + (record.vector() == null ? 0 : record.vector().length));
            }
            String pointId = Ids.deterministicUuid(POINT_NAMESPACE, record.id());
            points.put(pointId, new Entry(record.id(), record.vector().clone(), record.payload()));
        }
    }

    @Override
    public List<ScoredVector> search(float[] queryVector, int topK, Map<String, Object> filter) {
        if (queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        List<ScoredVector> scored = new ArrayList<>(points.size());
        for (Entry entry : points.values()) {
            if (!PayloadFilters.matches(entry.payload(), filter)) {
                continue;
            }
            scored.add(new ScoredVector(entry.businessId(), similarity(queryVector, entry.vector()), entry.payload()));
        }
        scored.sort(Comparator.comparingDouble(ScoredVector::score).reversed()
                .thenComparing(ScoredVector::id));
        return scored.size() <= topK ? scored : new ArrayList<>(scored.subList(0, topK));
    }

    /** 距离度量到「越大越相关」的得分。 */
    private double similarity(float[] query, float[] vector) {
        String metric = distance.toLowerCase(java.util.Locale.ROOT);
        return switch (metric) {
            case "dot", "dotproduct", "ip" -> dot(query, vector);
            case "euclid", "euclidean", "l2" -> -euclidean(query, vector);
            default -> Text.cosine(query, vector);
        };
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private static double euclidean(float[] a, float[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    @Override
    public void deleteCollection() {
        points.clear();
        dimension = -1;
    }

    @Override
    public long count() {
        return points.size();
    }

    /** 集合内的一条向量。 */
    private record Entry(String businessId, float[] vector, Map<String, Object> payload) {
    }
}
