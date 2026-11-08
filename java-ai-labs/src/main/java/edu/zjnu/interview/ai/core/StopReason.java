package edu.zjnu.interview.ai.core;

/**
 * 一次模型调用的终止原因。Agent Loop 依赖它决定「继续迭代」还是「收尾」。
 */
public enum StopReason {
    /** 模型给出最终答案，循环可以结束。 */
    END,
    /** 模型请求调用工具，循环必须继续（把工具结果回灌后再问一次）。 */
    TOOL_CALLS,
    /** 触发 max_tokens 截断。 */
    LENGTH,
    /** 调用异常或被安全策略拦截。 */
    ERROR,
    /** 内容安全过滤。 */
    CONTENT_FILTER
}
