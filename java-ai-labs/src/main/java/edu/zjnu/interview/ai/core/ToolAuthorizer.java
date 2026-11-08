package edu.zjnu.interview.ai.core;

/**
 * 工具调用授权（权限控制端口）。
 *
 * <p>JD 里明确要求「权限控制」。这里的模型是：
 * <b>授权发生在执行之前，且拒绝理由必须回灌给模型</b>——
 * 这样模型才知道「这条路走不通」，可以换工具、换路径或如实告诉用户做不到，
 * 而不是直接崩掉或者偷偷绕过。
 *
 * <p>返回值约定：{@code null} 表示放行；非 null 表示拒绝理由（会作为工具结果回灌给模型）。
 */
@FunctionalInterface
public interface ToolAuthorizer {

    /**
     * @return 放行返回 null；拒绝返回原因描述
     */
    String authorize(ToolCall call, ToolContext ctx);

    static ToolAuthorizer allowAll() {
        return (call, ctx) -> null;
    }

    static ToolAuthorizer denyAll(String reason) {
        return (call, ctx) -> reason;
    }
}
