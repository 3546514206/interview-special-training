package edu.zjnu.interview.ai.rag.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.zjnu.interview.ai.core.VectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QdrantVectorStore 离线协议测试。
 *
 * <p>通过假 Transport 断言「我们到底给 Qdrant 发了什么、怎么解析响应」，
 * 不需要真实向量库就能锁住协议契约。真机联调只验证「服务端行为与假设一致」这一层。
 */
class QdrantVectorStoreOfflineTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 记录请求的假传输层。 */
    private static final class RecordingTransport implements QdrantTransport {
        record Call(String method, String path, String body) {
        }

        final List<Call> calls = new ArrayList<>();
        private final java.util.function.BiFunction<String, String, Response> responder;

        RecordingTransport(java.util.function.BiFunction<String, String, Response> responder) {
            this.responder = responder;
        }

        @Override
        public Response send(String method, String path, String body) {
            calls.add(new Call(method, path, body));
            return responder.apply(method, path);
        }
    }

    private static VectorStore.VectorRecord record(String id, int dim) {
        float[] vector = new float[dim];
        vector[0] = 0.5f;
        return new VectorStore.VectorRecord(id, vector,
                Map.of("chunkId", id, "docId", "doc-1", "category", "准入"));
    }

    @Test
    @DisplayName("ensureCollection 幂等：不存在才 PUT，已存在不重复建")
    void ensureCollectionIsIdempotent() {
        RecordingTransport transport = new RecordingTransport((method, path) ->
                path.endsWith("/collections/java_ai_labs_knowledge") && "GET".equals(method)
                        ? new QdrantTransport.Response(200,
                        "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":256,\"distance\":\"Cosine\"}}}}}")
                        : new QdrantTransport.Response(200, "{\"result\":\"ok\"}"));
        QdrantVectorStore store = new QdrantVectorStore(transport, "java_ai_labs_knowledge", "Cosine", 256);

        store.ensureCollection(256, "Cosine");
        store.ensureCollection(256, "Cosine");

        long puts = transport.calls.stream().filter(c -> "PUT".equals(c.method())).count();
        assertEquals(0, puts, "集合已存在时不应再发 PUT");
    }

    @Test
    @DisplayName("维度不匹配在客户端就被拦截，不把脏请求发给服务端")
    void dimensionMismatchRejectedBeforeNetwork() {
        RecordingTransport transport = new RecordingTransport((m, p) ->
                new QdrantTransport.Response(200, "{\"result\":\"ok\"}"));
        QdrantVectorStore store = new QdrantVectorStore(transport, "java_ai_labs_knowledge", "Cosine", 256);

        assertThrows(IllegalStateException.class, () -> store.upsert(List.of(record("c1", 3))));
        assertEquals(0, transport.calls.size(), "校验失败就不应发起任何网络请求");
    }

    @Test
    @DisplayName("upsert 请求体：points 数组 + wait=true，point id 是确定性 UUID（幂等覆盖的前提）")
    void upsertBodyShape() throws Exception {
        RecordingTransport transport = new RecordingTransport((m, p) -> new QdrantTransport.Response(200, "{\"result\":\"ok\"}"));
        QdrantVectorStore store = new QdrantVectorStore(transport, "java_ai_labs_knowledge", "Cosine", 256);

        store.upsert(List.of(record("chunk-a", 256), record("chunk-b", 256)));
        store.upsert(List.of(record("chunk-a", 256)));

        assertEquals(2, transport.calls.size(), "两次 upsert 各发一个批量请求");
        RecordingTransport.Call first = transport.calls.get(0);
        assertEquals("PUT", first.method());
        assertTrue(first.path().endsWith("/collections/java_ai_labs_knowledge/points?wait=true"));

        JsonNode body = mapper.readTree(first.body());
        assertEquals(2, body.get("points").size());
        JsonNode firstPoint = body.get("points").get(0);
        assertEquals("chunk-a", firstPoint.get("payload").get("chunkId").asText());

        // 确定性：同业务 id 两次映射得到同一 UUID → 服务端按主键覆盖而不是追加
        String uuidFirst = firstPoint.get("id").asText();
        String uuidSecond = mapper.readTree(transport.calls.get(1).body())
                .get("points").get(0).get("id").asText();
        assertEquals(uuidFirst, uuidSecond);
        assertTrue(uuidFirst.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "point id 必须是 UUID 形态: " + uuidFirst);
    }

    @Test
    @DisplayName("search 请求体：with_payload + 过滤翻译成 Qdrant must DSL；响应还原业务 id")
    void searchRequestAndResponseParsing() throws Exception {
        String responseBody = "{\"result\":[" +
                "{\"id\":\"11111111-1111-4111-8111-111111111111\",\"score\":0.93," +
                "\"payload\":{\"chunkId\":\"chunk-a\",\"docId\":\"doc-1\"}}," +
                "{\"id\":\"22222222-2222-4222-8222-222222222222\",\"score\":0.71,\"payload\":{}}]}";
        RecordingTransport transport = new RecordingTransport((m, p) -> new QdrantTransport.Response(200, responseBody));
        QdrantVectorStore store = new QdrantVectorStore(transport, "java_ai_labs_knowledge", "Cosine", 256);

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("category", "准入");
        List<VectorStore.ScoredVector> hits = store.search(new float[256], 5, filter);

        RecordingTransport.Call call = transport.calls.get(0);
        assertEquals("POST", call.method());
        assertTrue(call.path().endsWith("/points/search"));

        JsonNode body = mapper.readTree(call.body());
        assertEquals(5, body.get("limit").asInt());
        assertEquals(true, body.get("with_payload").asBoolean());

        // 过滤翻译：与 PayloadFilters 的 AND 语义一致
        Map<String, Object> translated = QdrantVectorStore.buildFilter(filter);
        JsonNode filterNode = mapper.readTree(edu.zjnu.interview.ai.core.Json.write(translated));
        assertEquals("category", filterNode.get("must").get(0).get("key").asText());
        assertEquals("准入", filterNode.get("must").get(0).get("match").get("value").asText());

        // 响应解析：payload 里有 chunkId 时优先用业务 id
        assertEquals(2, hits.size());
        assertEquals("chunk-a", hits.get(0).id());
        assertEquals(0.93, hits.get(0).score(), 1e-9);
    }

    @Test
    @DisplayName("集合名守卫：公共集合在构造期就被拒绝，杜绝误操作")
    void protectedCollectionRejected() {
        RecordingTransport transport = new RecordingTransport((m, p) -> new QdrantTransport.Response(200, "{}"));

        assertThrows(IllegalArgumentException.class,
                () -> new QdrantVectorStore(transport, "common_collection", "Cosine", 256));
        assertTrue(QdrantVectorStore.isProtectedCollection("common_collection"));
        assertTrue(QdrantVectorStore.isProtectedCollection("Common_Collection "));
        org.junit.jupiter.api.Assertions.assertFalse(QdrantVectorStore.isProtectedCollection("java_ai_labs_knowledge"));
    }

    @Test
    @DisplayName("count 解析 points_count")
    void countParsing() {
        RecordingTransport transport = new RecordingTransport((m, p) ->
                new QdrantTransport.Response(200, "{\"result\":{\"points_count\":42}}"));
        QdrantVectorStore store = new QdrantVectorStore(transport, "java_ai_labs_knowledge", "Cosine", 256);

        assertEquals(42L, store.count());
    }
}
