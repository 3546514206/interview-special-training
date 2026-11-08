package edu.zjnu.interview.ai.core;

/**
 * 工具（Tool）端口。
 *
 * <p>工具是 Agent 能力边界的外化：模型只能通过工具触达真实世界。
 * 因此工具契约要满足三条：
 * <ol>
 *   <li>入参可校验（见 {@link ToolSpec#parameters()}）；</li>
 *   <li>失败结构化返回（见 {@link ToolResult#error(String, String)}）；</li>
 *   <li>副作用等级显式声明（见 {@link ToolSpec.SideEffect}），供权限策略裁决。</li>
 * </ol>
 */
public interface Tool {

    /** 工具声明，会原样暴露给模型。 */
    ToolSpec spec();

    /**
     * 执行工具。
     *
     * @param call 模型发起的调用（含原始参数 JSON）
     * @param ctx  执行上下文（工作目录、超时、trace）
     * @return 结构化结果；实现应捕获内部异常并返回 {@link ToolResult#error}，
     *         而不是把异常抛给模型层
     */
    ToolResult execute(ToolCall call, ToolContext ctx);
}
