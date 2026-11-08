package edu.zjnu.interview.ai.harness;

/**
 * 失败信号：由「产生失败的一方」声明失败性质，由「运行时」决定处置策略。
 *
 * <p><b>为什么要这样分</b>：只有模型适配器知道「这是 429 还是 500」「这是超时还是连接失败」，
 * 而只有运行时知道「这个场景允许多少次重试、退避多久、要不要熔断」。
 * 把「事实」和「策略」分开，适配器不需要知道任何业务策略，
 * 策略也不必硬编码某一家厂商的错误码。
 */
public interface RetryableFailure {

    /** 失败分类。 */
    FailureCategory category();

    /** HTTP 状态码（没有则 0）。 */
    default int statusCode() {
        return 0;
    }
}
