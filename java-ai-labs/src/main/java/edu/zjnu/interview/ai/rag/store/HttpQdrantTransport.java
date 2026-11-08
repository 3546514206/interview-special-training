package edu.zjnu.interview.ai.rag.store;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 基于 JDK 11+ {@link HttpClient} 的 Qdrant REST 实现。
 *
 * <p><b>为什么不引 gRPC 客户端？</b>
 * 模块的唯一运行时依赖是 Jackson（硬约束）。Qdrant 的 gRPC 客户端会引入 protobuf + grpc 两大坨传递依赖，
 * 而 REST 接口完全够用：建集合、批量 upsert、检索、查点数、删集合五个动作都是简单的 JSON 请求。
 * <b>依赖越少，工程越可控</b>——面试里这条比「用 gRPC 更快」更有说服力，因为运维成本、版本冲突、
 * 容器体积都是真实成本。
 *
 * <p>鉴权：Qdrant Cloud 用 {@code api-key} 头；自建实例可不带。
 */
public final class HttpQdrantTransport implements QdrantTransport {

    /** 默认超时：向量库的 P99 应该在百毫秒级，超过 5 秒基本可以判定为故障。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client;
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;

    public HttpQdrantTransport(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, DEFAULT_TIMEOUT);
    }

    public HttpQdrantTransport(String baseUrl, String apiKey, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Qdrant base url 不能为空");
        }
        String trimmed = baseUrl.trim();
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        this.apiKey = (apiKey == null || apiKey.isBlank()) ? null : apiKey.trim();
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public Response send(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout);
        if (apiKey != null) {
            builder.header("api-key", apiKey);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QdrantException("Qdrant 请求被中断: " + method + " " + path, e);
        } catch (IOException e) {
            throw new QdrantException("Qdrant 请求失败: " + method + " " + path + " (" + baseUrl + ")", e);
        }
    }
}
