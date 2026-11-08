package edu.zjnu.interview.ai.core;

import java.util.Objects;

/**
 * 模型发起的一次工具调用。
 *
 * <p>{@code argumentsJson} 保持原始 JSON 字符串形态，而不是提前解析成 Map：
 * 真实模型返回的参数常常是不合法 JSON（缺引号、尾逗号、多余文本），
 * 「先原样透传、再由工具层做容错解析」是生产环境更稳的做法，
 * 同时也便于在 Trace 里保留模型的原始输出用于问题定位。
 *
 * @param id            调用 id，用于把工具结果回灌给对应的调用
 * @param name          工具名
 * @param argumentsJson 参数 JSON 字符串
 */
public record ToolCall(String id, String name, String argumentsJson) {

    public ToolCall {
        Objects.requireNonNull(name, "name");
        id = (id == null || id.isBlank()) ? "call_" + Integer.toHexString(name.hashCode() & 0xffffff) : id;
        argumentsJson = (argumentsJson == null || argumentsJson.isBlank()) ? "{}" : argumentsJson;
    }

    public static ToolCall of(String id, String name, String argumentsJson) {
        return new ToolCall(id, name, argumentsJson);
    }
}
