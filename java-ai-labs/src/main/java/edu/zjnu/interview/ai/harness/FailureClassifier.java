package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.StopReason;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * 失败分类器：判断「这次失败该不该重试」。
 *
 * <p><b>分类顺序有讲究</b>：先看「失败方自己声明的信号」
 * （{@link RetryableFailure}），再看 JDK 的标准异常类型，
 * 最后才落到兜底。这样适配器可以精确表达「这是限流」，
 * 而没有被适配器包装的第三方异常仍然有合理的默认解释。
 */
public final class FailureClassifier {

    private static final FailureClassifier DEFAULT = new FailureClassifier();

    private FailureClassifier() {
    }

    public static FailureClassifier defaultClassifier() {
        return DEFAULT;
    }

    /** 分类。 */
    public FailureCategory categorize(Throwable error) {
        if (error == null) {
            return FailureCategory.UNKNOWN;
        }
        if (error instanceof RetryableFailure signal) {
            return signal.category();
        }
        if (error instanceof HttpTimeoutException || error instanceof HttpConnectTimeoutException
                || error instanceof SocketTimeoutException || error instanceof TimeoutException) {
            return FailureCategory.TIMEOUT;
        }
        if (error instanceof ConnectException || error instanceof UnknownHostException
                || error instanceof NoRouteToHostException) {
            return FailureCategory.NETWORK;
        }
        if (error instanceof InterruptedException) {
            // 线程被中断：通常意味着上层要停机，重试是错的
            return FailureCategory.UNKNOWN;
        }
        if (error instanceof IOException) {
            return FailureCategory.NETWORK;
        }
        return FailureCategory.UNKNOWN;
    }

    /** 是否可重试。 */
    public boolean isRetryable(Throwable error) {
        return categorize(error).transientFailure();
    }

    /**
     * 从「错误响应」里还原失败分类。
     *
     * <p>这是 SPI 契约下唯一能拿到失败信息的途径：
     * 模型实现返回 {@code stopReason=ERROR}，内容是 {@link ModelErrors} 定义的结构化载荷。
     */
    public FailureCategory categorize(ChatResponse response) {
        if (response == null || response.stopReason() != StopReason.ERROR) {
            return FailureCategory.UNKNOWN;
        }
        return ModelErrors.categoryOf(response.content());
    }

    /** 响应是否代表「可重试的失败」。 */
    public boolean isRetryable(ChatResponse response) {
        return categorize(response).transientFailure();
    }

    /** 状态的默认解释（适配器没有给出分类时，按 HTTP 状态码兜底）。 */
    public static FailureCategory fromStatusCode(int status) {
        if (status == 429) {
            return FailureCategory.RATE_LIMIT;
        }
        if (status >= 500) {
            return FailureCategory.SERVER_ERROR;
        }
        if (status >= 400) {
            return FailureCategory.CLIENT_ERROR;
        }
        if (status == 408) {
            return FailureCategory.TIMEOUT;
        }
        return FailureCategory.UNKNOWN;
    }
}
