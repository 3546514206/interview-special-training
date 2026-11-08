package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.StopReason;
import edu.zjnu.interview.ai.core.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试策略回归：错误分类、退避序列、重试次数。
 *
 * <p>等待通过 {@link Sleeper} 注入，因此本测试类<b>不 sleep</b>，跑得飞快且完全确定。
 */
class RetryPolicyTest {

    @Test
    @DisplayName("可重试分类：网络 / 超时 / 限流 / 5xx 可重试，4xx 不可重试")
    void classifiesRetryableErrors() {
        FailureClassifier classifier = FailureClassifier.defaultClassifier();

        assertEquals(FailureCategory.TIMEOUT, classifier.categorize(new HttpTimeoutException("timeout")));
        assertEquals(FailureCategory.TIMEOUT, classifier.categorize(new TimeoutException("timeout")));
        assertEquals(FailureCategory.NETWORK, classifier.categorize(new ConnectException("refused")));
        assertEquals(FailureCategory.NETWORK, classifier.categorize(new IOException("io")));
        assertEquals(FailureCategory.RATE_LIMIT,
                classifier.categorize(new ModelCallException("限流", FailureCategory.RATE_LIMIT, 429)));
        assertEquals(FailureCategory.CLIENT_ERROR,
                classifier.categorize(new ModelCallException("参数错", FailureCategory.CLIENT_ERROR, 400)));

        assertTrue(classifier.isRetryable(new HttpTimeoutException("t")));
        assertTrue(classifier.isRetryable(new ModelCallException("限流", FailureCategory.RATE_LIMIT, 429)));
        assertFalse(classifier.isRetryable(new ModelCallException("参数错", FailureCategory.CLIENT_ERROR, 400)),
                "4xx 是永久性错误，重试只会浪费配额");
        assertFalse(classifier.isRetryable(new IllegalStateException("未知")));
    }

    @Test
    @DisplayName("退避是指数增长且不超过上限；抖动只会让等待变短")
    void backoffIsExponentialAndBounded() {
        RetryPolicy policy = RetryPolicy.builder()
                .initialDelay(Duration.ofMillis(100))
                .multiplier(2.0)
                .maxDelay(Duration.ofMillis(500))
                .jitterRatio(0.2)
                .jitterSource(() -> 0.0)
                .build();

        assertEquals(100, policy.backoffFor(1).toMillis());
        assertEquals(200, policy.backoffFor(2).toMillis());
        assertEquals(400, policy.backoffFor(3).toMillis());
        assertEquals(500, policy.backoffFor(4).toMillis(), "必须被 maxDelay 截断");
        assertEquals(500, policy.backoffFor(10).toMillis());

        RetryPolicy maxJitter = RetryPolicy.builder()
                .initialDelay(Duration.ofMillis(100))
                .multiplier(2.0)
                .maxDelay(Duration.ofSeconds(10))
                .jitterRatio(0.2)
                .jitterSource(() -> 1.0)
                .build();
        assertEquals(80, maxJitter.backoffFor(1).toMillis(), "抖动最大只能削掉 20%");
    }

    @Test
    @DisplayName("重试到成功为止，且退避次数 = 失败次数")
    void retriesUntilSuccess() throws Exception {
        List<Duration> sleeps = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialDelay(Duration.ofMillis(50))
                .jitterRatio(0.0)
                .sleeper(sleeps::add)
                .build();

        String result = policy.execute(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ModelCallException("限流", FailureCategory.RATE_LIMIT, 429);
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(3, attempts.get());
        assertEquals(2, sleeps.size(), "两次失败 → 两次退避");
        assertEquals(50, sleeps.get(0).toMillis());
        assertEquals(100, sleeps.get(1).toMillis(), "退避指数增长");
    }

    @Test
    @DisplayName("不可重试的错误立刻抛出，不做退避")
    void nonRetryableErrorFailsFast() {
        AtomicInteger attempts = new AtomicInteger();
        List<Duration> sleeps = new ArrayList<>();
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(5)
                .sleeper(sleeps::add)
                .build();

        assertThrows(ModelCallException.class, () -> policy.execute(() -> {
            attempts.incrementAndGet();
            throw new ModelCallException("参数错", FailureCategory.CLIENT_ERROR, 400);
        }));
        assertEquals(1, attempts.get());
        assertTrue(sleeps.isEmpty(), "不可重试错误不应退避");
    }

    @Test
    @DisplayName("重试次数耗尽后抛出最后一次异常")
    void exhaustsAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .sleeper(duration -> {
                })
                .build();

        assertThrows(ModelCallException.class, () -> policy.execute(() -> {
            attempts.incrementAndGet();
            throw new ModelCallException("服务不可用", FailureCategory.SERVER_ERROR, 500);
        }));
        assertEquals(3, attempts.get());
    }

    @Test
    @DisplayName("RetryingChatModel：按策略重试错误响应（SPI 契约下失败也是响应）")
    void retryingChatModelRetriesErrorResponses() {
        FlakyChatModel flaky = new FlakyChatModel("qwen-plus", 2, FailureCategory.RATE_LIMIT,
                ChatResponse.text("成功"));
        List<Duration> sleeps = new ArrayList<>();
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialDelay(Duration.ofMillis(10))
                .jitterRatio(0.0)
                .sleeper(sleeps::add)
                .build();

        RetryingChatModel model = new RetryingChatModel(flaky, policy);
        ChatResponse response = model.chat(ChatRequest.of(List.of(ChatMessage.user("你好"))));

        assertEquals("成功", response.content());
        assertEquals(3, flaky.callCount());
        assertEquals(2, sleeps.size());
        assertEquals(StopReason.END, response.stopReason());
    }

    @Test
    @DisplayName("4xx 错误不重试")
    void clientErrorIsNotRetried() {
        FlakyChatModel flaky = new FlakyChatModel("qwen-plus", 5, FailureCategory.CLIENT_ERROR,
                ChatResponse.text("成功"));
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .sleeper(duration -> {
                })
                .build();

        RetryingChatModel model = new RetryingChatModel(flaky, policy);
        ChatResponse response = model.chat(ChatRequest.of(List.of(ChatMessage.user("你好"))));

        assertEquals(StopReason.ERROR, response.stopReason());
        assertEquals(1, flaky.callCount(), "4xx 只调用一次");
        assertEquals(TokenUsage.ZERO, response.usage());
    }
}
