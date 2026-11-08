package edu.zjnu.interview.ai.harness;

/**
 * Span 属性键常量。
 *
 * <p>为什么要有这一层：可观测性的第一个坑不是「没有日志」，而是「日志字段各写各的」。
 * 同一个错误码在 A 模块叫 {@code error_code}、在 B 模块叫 {@code errCode}，
 * 离线分析时就得写一堆正则去兼容。属性键集中定义，是让 Trace 真正可聚合的前提。
 *
 * <p>命名约定：{@code snake_case}（与 OpenTelemetry 习惯一致），数值型属性用
 * {@code long}/{@code double}，不要塞字符串——否则聚合时还要再做一次类型转换。
 */
public final class SpanAttrs {

    /** 模型名。 */
    public static final String MODEL = "model";
    /** 输入 token 数。 */
    public static final String PROMPT_TOKENS = "prompt_tokens";
    /** 输出 token 数。 */
    public static final String COMPLETION_TOKENS = "completion_tokens";
    /** token 合计。 */
    public static final String TOTAL_TOKENS = "total_tokens";
    /** 本次调用产生的成本（美元）。 */
    public static final String COST_USD = "cost_usd";
    /** 终止原因。 */
    public static final String STOP_REASON = "stop_reason";
    /** 工具名。 */
    public static final String TOOL_NAME = "tool_name";
    /** 工具调用 id。 */
    public static final String TOOL_CALL_ID = "tool_call_id";
    /** 错误码。 */
    public static final String ERROR_CODE = "error_code";
    /** 错误信息。 */
    public static final String ERROR_MESSAGE = "error_message";
    /** 失败分类（网络 / 超时 / 限流 / 服务端错误……）。 */
    public static final String FAILURE_CATEGORY = "failure_category";
    /** 是否发生了降级。 */
    public static final String DEGRADED = "degraded";
    /** 降级前的模型。 */
    public static final String DEGRADED_FROM = "degraded_from";
    /** 降级到的模型。 */
    public static final String DEGRADED_TO = "degraded_to";
    /** 尝试次数（含首次）。 */
    public static final String ATTEMPTS = "attempts";
    /** 本次退避时长（毫秒）。 */
    public static final String BACKOFF_MILLIS = "backoff_millis";
    /** 熔断器状态。 */
    public static final String CIRCUIT_STATE = "circuit_state";
    /** 风险等级（注入检测等）。 */
    public static final String RISK = "risk";
    /** 处置动作。 */
    public static final String ACTION = "action";
    /** 预算动作。 */
    public static final String BUDGET_ACTION = "budget_action";
    /** 预算已花费（美元）。 */
    public static final String BUDGET_SPENT_USD = "budget_spent_usd";
    /** 预算上限（美元）。 */
    public static final String BUDGET_LIMIT_USD = "budget_limit_usd";
    /** 命中的注入模式。 */
    public static final String INJECTION_PATTERNS = "injection_patterns";
    /** 授权结果（allow / deny）。 */
    public static final String AUTHORIZATION = "authorization";
    /** 拒绝原因。 */
    public static final String DENY_REASON = "deny_reason";
    /** 检索命中条数。 */
    public static final String HIT_COUNT = "hit_count";

    private SpanAttrs() {
    }
}
