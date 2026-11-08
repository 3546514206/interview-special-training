package edu.zjnu.interview.ai.rag.store;

import edu.zjnu.interview.ai.core.Ids;
import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.core.LabEnv;
import edu.zjnu.interview.ai.core.VectorStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Qdrant 向量库实现（REST 直连，不引入 gRPC 依赖）。
 *
 * <p>协议（已在真实 Qdrant 上逐条验证）：
 * <pre>
 *   PUT    /collections/{name}                 建集合 {"vectors":{"size":256,"distance":"Cosine"}}
 *   GET    /collections/{name}                 查集合信息（result.points_count / config.params.vectors.size）
 *   PUT    /collections/{name}/points?wait=true 批量 upsert
 *   POST   /collections/{name}/points/search   检索
 *   DELETE /collections/{name}                 删集合
 * </pre>
 *
 * <p><b>三条工程纪律</b>：
 * <ol>
 *   <li><b>只操作自己的集合</b>：集合名来自 {@code QDRANT_COLLECTION}，默认 {@code java_ai_labs_knowledge}，
 *       并且显式拒绝 {@code common_*} 这类「公共集合」前缀——实验代码误删生产集合是不可接受的事故；</li>
 *   <li><b>ensureCollection 幂等</b>：先 GET 判存在，已存在就不 PUT（重复 PUT 会拿到冲突错误），
 *       并且顺手校验已存在集合的维度——维度对不上直接报错，绝不把维度错误的向量写进集合；</li>
 *   <li><b>upsert 前校验维度</b>：本地就拦掉长度不等于集合维度的向量，
 *       避免把「维度不匹配」这种脏数据写进服务端再回头排查。</li>
 * </ol>
 */
public final class QdrantVectorStore implements VectorStore {

    /** 实验集合默认名（绝不能与服务器上已有的 common_collection 混用）。 */
    public static final String DEFAULT_COLLECTION = "java_ai_labs_knowledge";

    /** point id 命名空间（与内存实现一致，保证两套后端的 id 空间对齐）。 */
    public static final String POINT_NAMESPACE = "rag-point";

    /** 禁止操作的集合名前缀（公共集合不是实验的玩具）。 */
    private static final List<String> FORBIDDEN_PREFIXES = List.of("common_", "common");

    private final QdrantTransport transport;
    private final String collection;
    private final String distance;
    private final Integer configuredDimension;
    /** 实际生效的集合维度（ensureCollection 之后才有值），用于 upsert 前的本地校验。 */
    private int activeDimension = -1;

    public QdrantVectorStore(QdrantTransport transport, String collection, String distance, Integer configuredDimension) {
        this.transport = transport;
        this.collection = collection == null || collection.isBlank() ? DEFAULT_COLLECTION : collection.trim();
        this.distance = distance == null || distance.isBlank() ? "Cosine" : distance.trim();
        this.configuredDimension = configuredDimension;
        guardCollectionName(this.collection);
    }

    /**
     * 从环境变量创建（{@code QDRANT_BASE_URL} / {@code QDRANT_TOKEN} /
     * {@code QDRANT_COLLECTION} / {@code QDRANT_DISTANCE} / {@code QDRANT_DIM}）。
     */
    public static QdrantVectorStore fromEnv() {
        return fromEnv(new HttpQdrantTransport(
                LabEnv.get("QDRANT_BASE_URL"), LabEnv.get("QDRANT_TOKEN")));
    }

    /** 从环境变量创建，但传输层可替换（测试用）。 */
    public static QdrantVectorStore fromEnv(QdrantTransport transport) {
        return new QdrantVectorStore(transport,
                LabEnv.get("QDRANT_COLLECTION", DEFAULT_COLLECTION),
                LabEnv.get("QDRANT_DISTANCE", "Cosine"),
                LabEnv.getInt("QDRANT_DIM", 256));
    }

    /** 集合名守卫：拒绝操作公共集合。 */
    private static void guardCollectionName(String name) {
        if (isProtectedCollection(name)) {
            throw new IllegalArgumentException("拒绝操作公共集合 " + name
                    + "：实验只能使用自己的集合（例如 " + DEFAULT_COLLECTION + "）");
        }
    }

    /**
     * 判断集合名是否属于「公共集合」（其它用途的集合，实验不得读写删除）。
     *
     * @param name 集合名
     */
    public static boolean isProtectedCollection(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        for (String prefix : FORBIDDEN_PREFIXES) {
            if (lower.equals(prefix) || lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String name() {
        return collection;
    }

    @Override
    public void ensureCollection(int dimension, String distance) {
        if (configuredDimension != null && configuredDimension != dimension) {
            throw new IllegalStateException("维度不匹配：QDRANT_DIM 配置为 " + configuredDimension
                    + "，但 embedding 模型产出 " + dimension + " 维向量。换 embedding 模型必须换集合（并同步改 QDRANT_DIM）");
        }
        // 幂等：先 GET 判存在
        QdrantTransport.Response existing = transport.send("GET", collectionPath(), null);
        if (existing.ok()) {
            int size = parseVectorSize(existing.body());
            if (size > 0 && size != dimension) {
                throw new IllegalStateException("集合 " + collection + " 已存在且维度为 " + size
                        + "，与本次使用的 " + dimension + " 维不匹配：必须换集合或先删除重建");
            }
            activeDimension = size > 0 ? size : dimension;
            return;
        }
        if (!existing.notFound()) {
            throw new QdrantException("查询集合失败: " + collection, existing.status(), existing.body());
        }
        Map<String, Object> body = Map.of(
                "vectors", Map.of("size", dimension, "distance", distance));
        QdrantTransport.Response created = transport.send("PUT", collectionPath(), Json.write(body));
        // 409 = 已存在（并发场景），视为成功
        if (!created.ok() && created.status() != 409) {
            throw new QdrantException("创建集合失败: " + collection, created.status(), created.body());
        }
        activeDimension = dimension;
    }

    @Override
    public void upsert(List<VectorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Object> points = new ArrayList<>(records.size());
        for (VectorRecord record : records) {
            if (record.vector() == null) {
                throw new IllegalStateException("向量为空: " + record.id());
            }
            int expected = expectedDimension();
            if (expected > 0 && record.vector().length != expected) {
                throw new IllegalStateException("向量维度不匹配：" + record.id() + " 期望 "
                        + expected + " 维，实际 " + record.vector().length + " 维");
            }            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", Ids.deterministicUuid(POINT_NAMESPACE, record.id()));
            point.put("vector", record.vector());
            point.put("payload", record.payload());
            points.add(point);
        }
        Map<String, Object> body = Map.of("points", points);
        QdrantTransport.Response response = transport.send("PUT", collectionPath() + "/points?wait=true",
                Json.write(body));
        if (!response.ok()) {
            throw new QdrantException("批量 upsert 失败: " + collection, response.status(), response.body());
        }
    }

    @Override
    public List<ScoredVector> search(float[] queryVector, int topK, Map<String, Object> filter) {
        if (queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", queryVector);
        body.put("limit", topK);
        body.put("with_payload", true);
        body.put("with_vector", false);
        Map<String, Object> qdrantFilter = buildFilter(filter);
        if (qdrantFilter != null) {
            body.put("filter", qdrantFilter);
        }
        QdrantTransport.Response response = transport.send("POST", collectionPath() + "/points/search",
                Json.write(body));
        if (!response.ok()) {
            throw new QdrantException("检索失败: " + collection, response.status(), response.body());
        }
        Map<String, Object> parsed = Json.readMap(response.body());
        Object result = parsed.get("result");
        if (!(result instanceof List<?> list)) {
            return List.of();
        }
        List<ScoredVector> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String pointId = String.valueOf(map.get("id"));
            Object score = map.get("score");
            Map<String, Object> payload = map.get("payload") instanceof Map<?, ?> p
                    ? toStringKeyMap(p) : Map.of();
            Object businessId = payload.get("chunkId");
            out.add(new ScoredVector(businessId == null ? pointId : String.valueOf(businessId),
                    score instanceof Number n ? n.doubleValue() : 0.0, payload));
        }
        return out;
    }

    @Override
    public void deleteCollection() {
        QdrantTransport.Response response = transport.send("DELETE", collectionPath(), null);
        if (!response.ok() && !response.notFound()) {
            throw new QdrantException("删除集合失败: " + collection, response.status(), response.body());
        }
    }

    @Override
    public long count() {
        QdrantTransport.Response response = transport.send("GET", collectionPath(), null);
        if (!response.ok()) {
            throw new QdrantException("查询集合信息失败: " + collection, response.status(), response.body());
        }
        Map<String, Object> parsed = Json.readMap(response.body());
        Object result = parsed.get("result");
        if (result instanceof Map<?, ?> map && map.get("points_count") instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    /**
     * 把等值过滤条件翻译成 Qdrant filter DSL。
     *
     * <p>格式：{@code {"must":[{"key":"category","match":{"value":"准入"}}]}}。
     * 「must」是 AND 语义，与 {@link PayloadFilters#matches} 的语义一致。
     *
     * @param filter 过滤条件；null 或空返回 null（表示不过滤）
     */
    public static Map<String, Object> buildFilter(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return null;
        }
        List<Object> must = new ArrayList<>(filter.size());
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            must.add(Map.of("key", entry.getKey(),
                    "match", Map.of("value", entry.getValue() == null ? "" : entry.getValue())));
        }
        return Map.of("must", must);
    }

    /** 集合维度：优先用已确认的集合维度，其次用配置值（未初始化时返回 -1 表示不校验）。 */
    private int expectedDimension() {
        if (activeDimension > 0) {
            return activeDimension;
        }
        return configuredDimension == null ? -1 : configuredDimension;
    }

    private String collectionPath() {
        return "/collections/" + collection;
    }

    /** 从集合信息里解析向量维度。 */
    private static int parseVectorSize(String body) {
        Map<String, Object> parsed = Json.readMap(body);
        Object result = parsed.get("result");
        if (!(result instanceof Map<?, ?> resultMap)) {
            return -1;
        }
        Object config = resultMap.get("config");
        if (!(config instanceof Map<?, ?> configMap)) {
            return -1;
        }
        Object params = configMap.get("params");
        if (!(params instanceof Map<?, ?> paramsMap)) {
            return -1;
        }
        Object vectors = paramsMap.get("vectors");
        if (vectors instanceof Map<?, ?> vectorsMap && vectorsMap.get("size") instanceof Number n) {
            return n.intValue();
        }
        return -1;
    }

    private static Map<String, Object> toStringKeyMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
