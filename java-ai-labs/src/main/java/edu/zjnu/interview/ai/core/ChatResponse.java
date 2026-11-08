package edu.zjnu.interview.ai.core;

import java.util.List;

/**
 * 一次模型调用的响应。
 *
 * @param content       文本内容
 * @param toolCalls     模型请求的工具调用
 * @param usage         token 用量
 * @param stopReason    终止原因
 * @param latencyMillis 调用耗时（毫秒），用于时延分布统计
 */
public record ChatResponse(String content,
                           List<ToolCall> toolCalls,
                           TokenUsage usage,
                           StopReason stopReason,
                           long latencyMillis) {

    public ChatResponse {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? TokenUsage.ZERO : usage;
        stopReason = stopReason == null ? StopReason.END : stopReason;
    }

    /** 纯文本回答。 */
    public static ChatResponse text(String content, TokenUsage usage) {
        return new ChatResponse(content, List.of(), usage, StopReason.END, 0L);
    }

    public static ChatResponse text(String content) {
        return text(content, TokenUsage.ZERO);
    }

    /** 模型请求调用工具（Agent Loop 需要继续迭代）。 */
    public static ChatResponse toolCalls(List<ToolCall> calls, TokenUsage usage) {
        return new ChatResponse("", calls, usage, StopReason.TOOL_CALLS, 0L);
    }

    public static ChatResponse toolCall(String callId, String toolName, String argumentsJson) {
        return toolCalls(List.of(ToolCall.of(callId, toolName, argumentsJson)), TokenUsage.ZERO);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** 是否可以收尾（模型给出了最终答案）。 */
    public boolean isFinal() {
        return stopReason == StopReason.END || stopReason == StopReason.LENGTH || stopReason == StopReason.CONTENT_FILTER;
    }

    public ChatResponse withLatency(long millis) {
        return new ChatResponse(content, toolCalls, usage, stopReason, millis);
    }
}
