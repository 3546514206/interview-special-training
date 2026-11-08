package edu.zjnu.interview.ai.core;

import java.util.Map;

/**
 * 工具执行结果。
 *
 * <p>失败也要结构化返回而不是抛异常：工具失败是 Agent Loop 的正常分支，
 * 模型需要读到「失败原因」才能决定重试、换路还是收尾。把异常直接抛到模型层，
 * 会让 Agent 失去自我纠错能力。
 *
 * @param success   是否成功
 * @param content   面向模型的文本描述（会被回灌进对话）
 * @param data      结构化数据，供程序使用（不回灌给模型，避免 token 浪费）
 * @param errorCode 错误码（失败时），便于统计失败类型
 */
public record ToolResult(boolean success, String content, Map<String, Object> data, String errorCode) {

    public ToolResult {
        content = content == null ? "" : content;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static ToolResult ok(String content) {
        return new ToolResult(true, content, Map.of(), null);
    }

    public static ToolResult ok(String content, Map<String, Object> data) {
        return new ToolResult(true, content, data, null);
    }

    public static ToolResult error(String errorCode, String message) {
        return new ToolResult(false, message, Map.of(), errorCode);
    }
}
