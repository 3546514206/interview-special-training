package edu.zjnu.interview.ai.harness;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * 重试策略：可重试错误分类 + 指数退避 + 抖动 + 最大次数。
 *
 * <p><b>退避公式</b>：
 * <pre>
 * base    = min(maxDelay, initialDelay * multiplier^(attempt-1))
 * actual  = base - jitterRatio * base * random()      // random ∈ [0,1)
 * </pre>
 * 用「减法式抖动」而不是「加法式抖动」：抖动只能让等待变短、不能变长，
 * 因此最大等待时间始终有上界（{@code maxDelay}），不会因为抖动把请求挂死。
 *
 * <p><b>为什么要抖动</b>：没有抖动时，所有并发请求会在同一时刻集体重试，
 * 形成「惊群」——刚恢复的服务又被瞬间打垮。抖动把重试时刻打散，
 * 让服务有机会真正恢复。抖动比例默认 20%，既能打散又不至于把退避削没。
 *
 * <p><b>可测试性</b>：等待通过 {@link Sleeper} 注入，抖动通过 {@link DoubleSupplier} 注入。
 * 因此「退避次数、退避时长序列」都是可以精确断言的，而测试<b>不花真实时间</b>。
 */
public final class RetryPolicy {

    /** 重试回调（用于把重试事件写进 trace）。 */
    @FunctionalInterface
    public interface RetryListener {
        /**
         * @param attempt 已失败的第几次尝试（1 起）
         * @param error   失败原因
         * @param delay   本次退避时长
         */
        void onRetry(int attempt, Throwable error, Duration delay);
    }

    private final int maxAttempts;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double multiplier;
    private final double jitterRatio;
    private final DoubleSupplier jitterSource;
    private final Sleeper sleeper;
    private final FailureClassifier classifier;
    private final RetryListener listener;

    private RetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.initialDelay = builder.initialDelay;
        this.maxDelay = builder.maxDelay;
        this.multiplier = builder.multiplier;
        this.jitterRatio = builder.jitterRatio;
        this.jitterSource = builder.jitterSource;
        this.sleeper = builder.sleeper;
        this.classifier = builder.classifier;
        this.listener = builder.listener;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 一个可直接使用的默认策略：最多 3 次，200ms 起，指数退避 + 20% 抖动。 */
    public static RetryPolicy defaults() {
        return builder().build();
    }

    /** 最大尝试次数（含首次）。 */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** 是否可重试。 */
    public boolean isRetryable(Throwable error) {
        return classifier.isRetryable(error);
    }

    /**
     * 计算第 {@code attempt} 次失败之后、下一次尝试之前的退避时长。
     *
     * @param attempt 已失败的第几次尝试（1 起）
     */
    public Duration backoffFor(int attempt) {
        int exp = Math.max(0, attempt - 1);
        double base = initialDelay.toMillis() * Math.pow(multiplier, exp);
        double capped = Math.min(base, maxDelay.toMillis());
        double jitter = jitterRatio * capped * Math.min(1.0, Math.max(0.0, jitterSource.getAsDouble()));
        long millis = (long) Math.max(0.0, capped - jitter);
        return Duration.ofMillis(millis);
    }

    /**
     * 执行一段可能失败的动作，按策略重试。
     *
     * @param action 待执行动作
     * @return 动作结果
     * @throws Exception 重试耗尽或遇到不可重试错误时，抛出最后一次异常
     */
    public <T> T execute(Callable<T> action) throws Exception {
        int attempt = 1;
        while (true) {
            try {
                return action.call();
            } catch (Exception e) {
                if (attempt >= maxAttempts || !isRetryable(e)) {
                    throw e;
                }
                Duration delay = backoffFor(attempt);
                if (listener != null) {
                    listener.onRetry(attempt, e, delay);
                }
                pause(delay);
                attempt++;
            }
        }
    }

    /**
     * 按策略等待（走注入的 {@link Sleeper}）。
     *
     * <p>供「自己实现重试循环」的装饰器复用同一套等待实现，
     * 避免出现「一处真 sleep、一处假 sleep」的割裂。
     */
    public void pause(Duration delay) {
        try {
            sleeper.sleep(delay == null ? Duration.ZERO : delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 构造器。 */
    public static final class Builder {
        private int maxAttempts = 3;
        private Duration initialDelay = Duration.ofMillis(200);
        private Duration maxDelay = Duration.ofSeconds(10);
        private double multiplier = 2.0;
        private double jitterRatio = 0.2;
        private DoubleSupplier jitterSource = () -> ThreadLocalRandom.current().nextDouble();
        private Sleeper sleeper = Sleeper.real();
        private FailureClassifier classifier = FailureClassifier.defaultClassifier();
        private RetryListener listener;

        /** 最大尝试次数（含首次）。至少为 1。 */
        public Builder maxAttempts(int value) {
            this.maxAttempts = Math.max(1, value);
            return this;
        }

        /** 首次退避时长。 */
        public Builder initialDelay(Duration value) {
            this.initialDelay = value == null ? Duration.ZERO : value;
            return this;
        }

        /** 退避时长上限。 */
        public Builder maxDelay(Duration value) {
            this.maxDelay = value == null ? Duration.ZERO : value;
            return this;
        }

        /** 指数底数。 */
        public Builder multiplier(double value) {
            this.multiplier = value <= 0 ? 1.0 : value;
            return this;
        }

        /** 抖动比例（0~1）。 */
        public Builder jitterRatio(double value) {
            this.jitterRatio = Math.min(1.0, Math.max(0.0, value));
            return this;
        }

        /** 抖动随机源（测试注入固定值即可得到确定性退避序列）。 */
        public Builder jitterSource(DoubleSupplier value) {
            this.jitterSource = value == null ? () -> ThreadLocalRandom.current().nextDouble() : value;
            return this;
        }

        /** 等待实现（测试注入 {@link Sleeper#noop()}）。 */
        public Builder sleeper(Sleeper value) {
            this.sleeper = value == null ? Sleeper.real() : value;
            return this;
        }

        /** 失败分类器。 */
        public Builder classifier(FailureClassifier value) {
            this.classifier = value == null ? FailureClassifier.defaultClassifier() : value;
            return this;
        }

        /** 重试回调。 */
        public Builder listener(RetryListener value) {
            this.listener = value;
            return this;
        }

        public RetryPolicy build() {
            return new RetryPolicy(this);
        }
    }
}
