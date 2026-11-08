package edu.zjnu.interview.ai.harness;

/**
 * 失败分类。
 *
 * <p><b>为什么要分类而不是「一律重试」</b>：
 * 重试只对「瞬时故障」有意义。
 * <ul>
 *   <li>{@link #NETWORK} / {@link #TIMEOUT}：瞬时，重试大概率能成功；</li>
 *   <li>{@link #RATE_LIMIT}：瞬时，但必须<b>退避</b>，否则会加重限流；</li>
 *   <li>{@link #SERVER_ERROR}：通常瞬时（5xx），可重试但要控制次数；</li>
 *   <li>{@link #CLIENT_ERROR}：<b>永久性</b>（4xx 参数错、鉴权错），重试只会浪费配额和时间；</li>
 *   <li>{@link #UNKNOWN}：默认不重试——「不确定是否可重试」时，保守比激进安全。</li>
 * </ul>
 */
public enum FailureCategory {

    /** 网络不可达 / 连接被拒 / DNS 失败。 */
    NETWORK,
    /** 超时（连接超时、读超时、整体 deadline 超时）。 */
    TIMEOUT,
    /** 限流（HTTP 429 或供应商返回的配额提示）。 */
    RATE_LIMIT,
    /** 服务端错误（HTTP 5xx）。 */
    SERVER_ERROR,
    /** 客户端错误（HTTP 4xx）：参数错、鉴权错、模型名不存在。 */
    CLIENT_ERROR,
    /** 未知故障。 */
    UNKNOWN;

    /** 是否属于「瞬时故障」（值得重试）。 */
    public boolean transientFailure() {
        return this == NETWORK || this == TIMEOUT || this == RATE_LIMIT || this == SERVER_ERROR;
    }

    /** 由字符串解析（未知值落到 {@link #UNKNOWN}）。 */
    public static FailureCategory parse(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
