package edu.zjnu.interview.ai.core;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 工具执行上下文：把「这一次调用发生在什么环境里」显式传给工具。
 *
 * <p>Harness Engineering 的核心之一：工具不应依赖静态配置或全局单例，
 * 而应从上下文里拿到工作目录、超时、trace 标识。这样才能在测试里注入临时目录，
 * 在生产里注入受控的工作目录（沙箱），在多租户里注入租户维度。
 *
 * @param traceId          链路 id
 * @param spanId           当前 span id
 * @param workingDirectory 允许访问的工作目录（沙箱根）
 * @param deadline         截止时间（超时控制）
 * @param attributes       其它上下文属性
 */
public record ToolContext(String traceId,
                          String spanId,
                          Path workingDirectory,
                          Instant deadline,
                          Map<String, Object> attributes) {

    public ToolContext {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static ToolContext of(String traceId, Path workingDirectory) {
        return new ToolContext(traceId, null, workingDirectory, null, Map.of());
    }

    /** 是否已超时。工具应在长耗时循环里主动检查它。 */
    public boolean expired() {
        return deadline != null && Instant.now().isAfter(deadline);
    }

    /** 剩余时间；无 deadline 时返回 null。 */
    public Duration remaining() {
        if (deadline == null) {
            return null;
        }
        Duration left = Duration.between(Instant.now(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    public ToolContext withDeadline(Duration timeout) {
        return new ToolContext(traceId, spanId, workingDirectory, Instant.now().plus(timeout), attributes);
    }

    public ToolContext withSpanId(String newSpanId) {
        return new ToolContext(traceId, newSpanId, workingDirectory, deadline, attributes);
    }

    public ToolContext withAttribute(String key, Object value) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>(attributes);
        merged.put(key, value);
        return new ToolContext(traceId, spanId, workingDirectory, deadline, merged);
    }
}
