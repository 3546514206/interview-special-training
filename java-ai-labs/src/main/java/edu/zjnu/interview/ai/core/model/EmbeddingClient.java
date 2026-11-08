package edu.zjnu.interview.ai.core.model;

import edu.zjnu.interview.ai.core.EmbeddingModel;
import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.core.LabEnv;
import edu.zjnu.interview.ai.harness.FailureCategory;
import edu.zjnu.interview.ai.harness.FailureClassifier;
import edu.zjnu.interview.ai.harness.ModelCallException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的真实向量模型客户端（{@code /embeddings} 端点）。
 *
 * <p><b>为什么单独一个类而不是复用 chat 适配器</b>：
 * 向量模型的<b>生命周期与对话模型完全不同</b>——它决定的是向量库的集合定义。
 * 一旦维度变了，旧集合里的向量全部作废（这是 RAG 索引工程最常见的事故）。
 * 所以「换 embedding 模型 = 重建集合」这件事必须显式暴露在 API 上：
 * {@link #dimensions()} 是集合建库参数，而不是一个可以随手改的返回值。
 *
 * <p><b>向量做 L2 归一化</b>：归一化后余弦相似度等价于点积，
 * 检索时少一次开方，���大规模检索里是实打实的收益。
 *
 * <p><b>离线可跑</b>：本类只在配置了 {@code OPENAI_BASE_URL} 时才被使用；
 * 离线环境继续走 {@code core.mock.HashEmbeddingModel}。
 */
public final class EmbeddingClient implements EmbeddingModel {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int configuredDimensions;
    private final Duration timeout;
    private final HttpClient httpClient;
    private volatile int resolvedDimensions;

    public EmbeddingClient(String baseUrl, String apiKey, String model, int dimensions, Duration timeout) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.model = model == null || model.isBlank() ? "text-embedding-v3" : model;
        this.configuredDimensions = dimensions;
        this.resolvedDimensions = dimensions;
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    /** 从 {@link LabEnv} 构造；没有配置网关时返回 null。 */
    public static EmbeddingClient fromEnv() {
        String baseUrl = LabEnv.get("OPENAI_BASE_URL", null);
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String apiKey = LabEnv.get("OPENAI_API_KEY", "");
        String model = LabEnv.get("OPENAI_EMBEDDING_MODEL", "text-embedding-v3");
        int dimensions = LabEnv.getInt("OPENAI_EMBEDDING_DIMENSIONS", 0);
        int timeoutMs = LabEnv.getInt("OPENAI_TIMEOUT_MS", 30000);
        return new EmbeddingClient(baseUrl, apiKey, model, dimensions, Duration.ofMillis(timeoutMs));
    }

    @Override
    public String name() {
        return model;
    }

    @Override
    public int dimensions() {
        return resolvedDimensions;
    }

    /** {@code /embeddings} 完整地址。 */
    public URI endpoint() {
        return URI.create(baseUrl.endsWith("/embeddings") ? baseUrl : baseUrl + "/embeddings");
    }

    @Override
    public float[] embed(String text) {
        List<float[]> vectors = embedBatch(List.of(text == null ? "" : text));
        return vectors.isEmpty() ? new float[0] : vectors.get(0);
    }

    /**
     * 批量向量化。
     *
     * @throws ModelCallException 网络失败 / 超时 / 响应格式错误
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", texts);
        if (configuredDimensions > 0) {
            body.put("dimensions", configuredDimensions);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint())
                .timeout(timeout)
                .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(
                Json.write(body), StandardCharsets.UTF_8)).build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new ModelCallException("embedding 调用超时", FailureCategory.TIMEOUT, 0, e);
        } catch (IOException e) {
            throw new ModelCallException("embedding 调用网络异常: " + e.getMessage(),
                    FailureCategory.NETWORK, 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("embedding 调用被中断", FailureCategory.UNKNOWN, 0, e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ModelCallException("embedding 服务返回错误状态 " + response.statusCode(),
                    FailureClassifier.fromStatusCode(response.statusCode()), response.statusCode());
        }
        return parseVectors(response.body());
    }

    private List<float[]> parseVectors(String rawBody) {
        Map<String, Object> root = Json.readMap(rawBody);
        Object data = root.get("data");
        if (!(data instanceof List<?> items)) {
            throw new ModelCallException("embedding 响应格式异常：缺少 data 数组",
                    FailureCategory.UNKNOWN, 0);
        }
        List<float[]> vectors = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object embedding = map.get("embedding");
            if (!(embedding instanceof List<?> numbers)) {
                continue;
            }
            float[] vector = new float[numbers.size()];
            for (int i = 0; i < numbers.size(); i++) {
                Object value = numbers.get(i);
                vector[i] = value instanceof Number n ? n.floatValue() : 0f;
            }
            vectors.add(normalize(vector));
        }
        if (!vectors.isEmpty()) {
            resolvedDimensions = vectors.get(0).length;
        }
        return vectors;
    }

    /** L2 归一化：归一化后余弦相似度 = 点积。 */
    static float[] normalize(float[] vector) {
        double sum = 0.0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm <= 0.0) {
            return vector;
        }
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) (vector[i] / norm);
        }
        return out;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
