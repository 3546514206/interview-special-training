package edu.zjnu.interview.ai.harness;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 熔断器：CLOSED / OPEN / HALF_OPEN 状态机。
 *
 * <p><b>为什么模型调用需要熔断</b>：
 * 重试能扛住偶发抖动，但扛不住「服务持续不可用」。
 * 如果供应商挂了，重试策略会让每个请求都重试 3 次，
 * 结果是：流量没变，<b>后端压力翻了 3 倍</b>，故障被放大而不是缓解。
 * 熔断器的价值是——<b>当发现某下游持续失败时，直接快速失败，把资源留给还能成功的下游</b>。
 *
 * <p><b>三个状态</b>：
 * <ul>
 *   <li>{@code CLOSED}：正常放行。连续失败达到阈值 → 打开。</li>
 *   <li>{@code OPEN}：直接拒绝（快速失败），不再打下游。冷却时间到 → 半开。</li>
 *   <li>{@code HALF_OPEN}：放<b>一个</b>试探请求过去。成功达到阈值 → 关闭；失败 → 立刻重新打开。</li>
 * </ul>
 *
 * <p><b>为什么半开只放一个请求</b>：半开期的目的是「探路」，不是「放量」。
 * 如果半开期放 100 个请求，下游还没恢复就会被再次打垮，
 * 于是状态在 OPEN/HALF_OPEN 之间来回抖动（flapping）。
 *
 * <p><b>时钟可注入</b>：冷却时间的判断依赖「现在几点」，
 * 测试里必须能<b>瞬间</b>推进时间，否则就只能 sleep（而 sleep 会让测试变慢且不稳定）。
 */
public final class CircuitBreaker {

    /** 熔断器状态。 */
    public enum State {
        /** 正常放行。 */
        CLOSED,
        /** 已熔断：快速失败，不再请求下游。 */
        OPEN,
        /** 半开：放一个试探请求。 */
        HALF_OPEN
    }

    private final int failureThreshold;
    private final int successThreshold;
    private final long openDurationMillis;
    private final LongSupplier clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private int halfOpenSuccesses;
    private boolean halfOpenInFlight;
    private long openedAtMillis;
    private long rejectedCount;
    private long transitionCount;

    private CircuitBreaker(int failureThreshold, int successThreshold, long openDurationMillis, LongSupplier clock) {
        this.failureThreshold = failureThreshold;
        this.successThreshold = successThreshold;
        this.openDurationMillis = openDurationMillis;
        this.clock = clock;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 当前状态。
     *
     * <p>冷却时间到了之后，即使还没真正试探，状态也对外显示为 {@code HALF_OPEN}——
     * 这样监控面板上能看到「即将恢复」，而不是一直显示 OPEN。
     */
    public synchronized State state() {
        if (state == State.OPEN && cooledDown()) {
            return State.HALF_OPEN;
        }
        return state;
    }

    /** 是否处于熔断（快速失败）状态。 */
    public boolean isOpen() {
        return state() == State.OPEN;
    }

    /**
     * 申请一次调用许可。
     *
     * @return true 允许调用；false 表示应当快速失败
     */
    public synchronized boolean tryAcquire() {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.OPEN) {
            if (!cooledDown()) {
                rejectedCount++;
                return false;
            }
            // 冷却结束：进入半开
            state = State.HALF_OPEN;
            halfOpenSuccesses = 0;
            halfOpenInFlight = false;
            transitionCount++;
        }
        // 半开期只允许一个试探请求在途，其余快速失败
        if (halfOpenInFlight) {
            rejectedCount++;
            return false;
        }
        halfOpenInFlight = true;
        return true;
    }

    /** 记录一次成功。 */
    public synchronized void recordSuccess() {
        switch (state) {
            case CLOSED:
                consecutiveFailures = 0;
                break;
            case HALF_OPEN:
                halfOpenInFlight = false;
                halfOpenSuccesses++;
                if (halfOpenSuccesses >= successThreshold) {
                    state = State.CLOSED;
                    consecutiveFailures = 0;
                    halfOpenSuccesses = 0;
                    openedAtMillis = 0L;
                    transitionCount++;
                }
                break;
            case OPEN:
            default:
                break;
        }
    }

    /** 记录一次失败。 */
    public synchronized void recordFailure() {
        switch (state) {
            case CLOSED:
                consecutiveFailures++;
                if (consecutiveFailures >= failureThreshold) {
                    open();
                }
                break;
            case HALF_OPEN:
                // 试探失败：立刻重新打开
                halfOpenInFlight = false;
                open();
                break;
            case OPEN:
            default:
                break;
        }
    }

    /**
     * 用熔断器包一次调用：许可 -> 执行 -> 记录成功/失败。
     *
     * @param action 实际动作
     * @param onRejected 被熔断拒绝时的替代动作（返回结构化错误）
     */
    public <T> T call(Supplier<T> action, Supplier<T> onRejected) {
        if (!tryAcquire()) {
            return onRejected.get();
        }
        try {
            T result = action.get();
            recordSuccess();
            return result;
        } catch (RuntimeException e) {
            recordFailure();
            throw e;
        }
    }

    private void open() {
        state = State.OPEN;
        openedAtMillis = clock.getAsLong();
        halfOpenInFlight = false;
        halfOpenSuccesses = 0;
        transitionCount++;
    }

    private boolean cooledDown() {
        return clock.getAsLong() - openedAtMillis >= openDurationMillis;
    }

    /** 被拒绝的次数（快速失败计数）。 */
    public synchronized long rejectedCount() {
        return rejectedCount;
    }

    /** 状态切换次数。 */
    public synchronized long transitionCount() {
        return transitionCount;
    }

    /** 连续失败次数。 */
    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    /** 构造器。 */
    public static final class Builder {
        private int failureThreshold = 3;
        private int successThreshold = 1;
        private Duration openDuration = Duration.ofSeconds(30);
        private LongSupplier clock = System::currentTimeMillis;

        /** 连续失败多少次后打开熔断。 */
        public Builder failureThreshold(int value) {
            this.failureThreshold = Math.max(1, value);
            return this;
        }

        /** 半开期需要连续成功多少次才关闭熔断。 */
        public Builder successThreshold(int value) {
            this.successThreshold = Math.max(1, value);
            return this;
        }

        /** 熔断后的冷却时间。 */
        public Builder openDuration(Duration value) {
            this.openDuration = value == null ? Duration.ofSeconds(30) : value;
            return this;
        }

        /** 时钟（测试注入可推进的假时钟）。 */
        public Builder clock(LongSupplier value) {
            this.clock = value == null ? System::currentTimeMillis : value;
            return this;
        }

        public CircuitBreaker build() {
            return new CircuitBreaker(failureThreshold, successThreshold, openDuration.toMillis(), clock);
        }
    }
}
