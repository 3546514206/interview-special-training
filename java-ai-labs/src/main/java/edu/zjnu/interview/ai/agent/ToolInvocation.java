package edu.zjnu.interview.ai.agent;

/**
 * 一次工具调用的完整记录。
 *
 * <p>「模型发了什么参数」「工具返回了什么」「成没成功」三件事必须留痕：
 * 没有这份记录，工具出问题时只能靠猜模型是不是传错了参数。
 *
 * @param callId         模型给的调用 id（用于把结果回灌给对应的调用）
 * @param toolName       工具名
 * @param argumentsJson  原始参数 JSON（原样保留，便于排查模型的脏输出）
 * @param success        是否执行成功
 * @param errorCode      失败错误码
 * @param content        回灌给模型的内容（截断后）
 * @param durationMillis 耗时
 * @param spanId         该次调用对应的 span id
 */
public record ToolInvocation(String callId,
                              String toolName,
                              String argumentsJson,
                              boolean success,
                              String errorCode,
                              String content,
                              long durationMillis,
                              String spanId) {

    public ToolInvocation {
        argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
        content = content == null ? "" : content;
    }
}
