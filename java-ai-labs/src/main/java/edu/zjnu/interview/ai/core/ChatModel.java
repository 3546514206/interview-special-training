package edu.zjnu.interview.ai.core;

/**
 * 模型端口（Port）。
 *
 * <p>整个实验工程只依赖这个接口，不依赖任何具体厂商 SDK。
 * 这带来三件事：
 * <ol>
 *   <li>没有 API Key 也能跑通全链路（离线 Mock 实现）；</li>
 *   <li>单元测试可完全确定性回归；</li>
 *   <li>换模型 / 加降级链路时不需要改业务代码（见 harness 包里的降级实现）。</li>
 * </ol>
 *
 * <p>真实实现见 {@code core.model} 包（OpenAI 兼容协议），Mock 实现见 {@code core.mock} 包。
 */
public interface ChatModel {

    /** 模型标识，用于 Trace、成本核算与降级路由。 */
    String name();

    /** 执行一次对话补全。实现应当保证不抛异常，失败时返回 {@code stopReason=ERROR} 的响应。 */
    ChatResponse chat(ChatRequest request);

    /** 模型上下文窗口（token），用于上下文组装时的预算裁剪。 */
    default int contextWindowTokens() {
        return 8192;
    }

    /** 单次调用允许的最大输出 token。 */
    default int maxOutputTokens() {
        return 4096;
    }
}
