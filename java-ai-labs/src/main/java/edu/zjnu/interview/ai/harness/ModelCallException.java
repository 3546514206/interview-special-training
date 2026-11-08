package edu.zjnu.interview.ai.harness;

/**
 * 模型调用异常：携带失败分类，供重试 / 降级 / 熔断策略消费。
 *
 * <p><b>注意与 {@code ChatModel} SPI 契约的关系</b>：
 * SPI 约定「模型实现不抛异常，失败时返回 {@code stopReason=ERROR} 的响应」。
 * 那重试策略怎么知道「这次该重试」？
 * <ul>
 *   <li>方式一（异常路径）：适配器额外提供 {@code chatOrThrow}，抛出带分类的异常；</li>
 *   <li>方式二（响应路径）：错误响应的内容是结构化错误载荷，
 *       {@link FailureClassifier} 从中还原分类。</li>
 * </ul>
 * 两条路径都支持，是为了让「上层装饰器」既能包 SPI 模型（只能拿到响应），
 * 也能包具体适配器（能拿到异常）。
 */
public class ModelCallException extends RuntimeException implements RetryableFailure {

    private static final long serialVersionUID = 1L;

    private final FailureCategory category;
    private final int statusCode;

    public ModelCallException(String message, FailureCategory category, int statusCode) {
        this(message, category, statusCode, null);
    }

    public ModelCallException(String message, FailureCategory category, int statusCode, Throwable cause) {
        super(message, cause);
        this.category = category == null ? FailureCategory.UNKNOWN : category;
        this.statusCode = statusCode;
    }

    @Override
    public FailureCategory category() {
        return category;
    }

    @Override
    public int statusCode() {
        return statusCode;
    }
}
