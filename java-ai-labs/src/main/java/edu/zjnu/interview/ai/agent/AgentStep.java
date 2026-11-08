package edu.zjnu.interview.ai.agent;

import java.util.List;

/**
 * 一次 Agent 迭代的明细。
 *
 * <p>Agent Loop 的每一步都会产生「模型调用 + N 次工具调用」，
 * 把这些明细显式记录下来，才能回答「这次为什么慢」「哪一步在烧钱」「模型到底调了什么」。
 *
 * @param index           步序号（从 0 开始）
 * @param kind            步骤类型（model / tool）
 * @param name            步骤名称（如 {@code llm.chat} / {@code tool.exec.calculator}）
 * @param durationMillis  耗时
 * @param promptTokens    本步消耗的输入 token
 * @param completionTokens 本步消耗的输出 token
 * @param toolCalls       涉及的工具调用（模型步为模型发起的调用；工具步为单次调用）
 * @param summary         人类可读的一句话摘要
 * @param error           是否出错
 */
public record AgentStep(int index,
                        String kind,
                        String name,
                        long durationMillis,
                        int promptTokens,
                        int completionTokens,
                        List<String> toolCalls,
                        String summary,
                        boolean error) {

    public AgentStep {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 模型步。 */
    public static AgentStep model(int index, long durationMillis, int promptTokens, int completionTokens,
                                  List<String> toolCalls, String summary, boolean error) {
        return new AgentStep(index, "model", "llm.chat", durationMillis, promptTokens, completionTokens,
                toolCalls, summary, error);
    }

    /** 工具步。 */
    public static AgentStep tool(int index, long durationMillis, String toolName, String summary, boolean error) {
        return new AgentStep(index, "tool", "tool.exec." + toolName, durationMillis, 0, 0,
                List.of(toolName), summary, error);
    }
}
