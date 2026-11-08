package edu.zjnu.interview.ai.core;

import java.util.List;
import java.util.Objects;

/**
 * 一次模型调用请求。
 *
 * @param messages    上下文消息（已按 Agent Loop 的状态与记忆组装完毕）
 * @param tools       允许模型调用的工具声明（工具名 + 参数 schema）
 * @param temperature 采样温度
 * @param maxTokens   最大输出 token
 * @param model       目标模型标识（用于成本核算与降级路由）
 */
public record ChatRequest(List<ChatMessage> messages,
                          List<ToolSpec> tools,
                          double temperature,
                          int maxTokens,
                          String model) {

    public ChatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static ChatRequest of(List<ChatMessage> messages) {
        return new ChatRequest(messages, List.of(), 0.2, 1024, "mock");
    }

    public ChatRequest withTools(List<ToolSpec> newTools) {
        return new ChatRequest(messages, newTools, temperature, maxTokens, model);
    }

    public ChatRequest withModel(String newModel) {
        return new ChatRequest(messages, tools, temperature, maxTokens, newModel);
    }

    public ChatRequest withMaxTokens(int newMaxTokens) {
        return new ChatRequest(messages, tools, temperature, newMaxTokens, model);
    }

    /** 估算本次请求的输入 token 数（用于预算预检与成本预估）。 */
    public int estimatedPromptTokens() {
        return TokenCounter.estimateMessages(messages);
    }

    /** 最后一条消息，Agent Loop 常用的探针位置。 */
    public ChatMessage lastMessage() {
        Objects.requireNonNull(messages, "messages");
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }
}
