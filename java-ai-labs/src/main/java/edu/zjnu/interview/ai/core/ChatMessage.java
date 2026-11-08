package edu.zjnu.interview.ai.core;

import java.util.List;
import java.util.Objects;

/**
 * 一条对话消息。
 *
 * <p>用不可变 record 承载 LLM 交互的最小单元：{@code SYSTEM / USER / ASSISTANT / TOOL} 四种角色，
 * 其中 {@code TOOL} 消息通过 {@link #toolCallId()} 与模型发起的 {@link ToolCall} 一一对应，
 * 这条链路正是 Agent Loop 中「模型 → 工具 → 结果回灌」的闭环基础。
 *
 * @param role       消息角色
 * @param content    文本内容（可能为空，例如 assistant 只发起工具调用时）
 * @param toolCalls  assistant 发起的工具调用列表
 * @param toolCallId 当 role=TOOL 时，指向被回应的 {@link ToolCall#id()}
 * @param name       工具名（role=TOOL 时有值，便于多工具并行时区分来源）
 */
public record ChatMessage(Role role,
                          String content,
                          List<ToolCall> toolCalls,
                          String toolCallId,
                          String name) {

    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, List.of(), null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, List.of(), null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, List.of(), null, null);
    }

    /** assistant 发起一次或多次工具调用（content 通常为空）。 */
    public static ChatMessage assistantToolCalls(List<ToolCall> calls) {
        return new ChatMessage(Role.ASSISTANT, "", calls, null, null);
    }

    /** 工具执行结果回灌给模型。 */
    public static ChatMessage toolResult(String toolCallId, String toolName, String content) {
        return new ChatMessage(Role.TOOL, content, List.of(), toolCallId, toolName);
    }

    /** 是否携带工具调用（模型「想干活」而非「要收尾」）。 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
