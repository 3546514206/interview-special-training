package edu.zjnu.interview.ai.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熔断状态机回归：CLOSED → OPEN → HALF_OPEN → CLOSED。
 *
 * <p>时钟是注入的假时钟，因此测试<b>不 sleep</b>，且完全确定性。
 */
class CircuitBreakerTest {

    @Test
    @DisplayName("连续失败达到阈值 → 打开熔断")
    void opensAfterFailureThreshold() {
        AtomicLong clock = new AtomicLong(0L);
        CircuitBreaker breaker = CircuitBreaker.builder()
                .failureThreshold(3)
                .openDuration(Duration.ofSeconds(30))
                .clock(clock::get)
                .build();

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.tryAcquire());
        breaker.recordFailure();
        assertTrue(breaker.tryAcquire());
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(), "未达阈值仍然放行");

        assertTrue(breaker.tryAcquire());
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertTrue(breaker.isOpen());
    }

    @Test
    @DisplayName("熔断期间快速失败，不再打下游")
    void rejectsWhileOpen() {
        AtomicLong clock = new AtomicLong(0L);
        CircuitBreaker breaker = CircuitBreaker.builder()
                .failureThreshold(1)
                .openDuration(Duration.ofSeconds(30))
                .clock(clock::get)
                .build();

        breaker.tryAcquire();
        breaker.recordFailure();
        assertFalse(breaker.tryAcquire(), "熔断期间应当拒绝");
        assertEquals(1, breaker.rejectedCount());
        assertEquals(1, breaker.transitionCount(), "只应发生一次状态切换（打开）");
    }

    @Test
    @DisplayName("冷却结束 → 半开；试探成功 → 关闭")
    void halfOpenThenClosesOnSuccess() {
        AtomicLong clock = new AtomicLong(0L);
        CircuitBreaker breaker = CircuitBreaker.builder()
                .failureThreshold(2)
                .successThreshold(1)
                .openDuration(Duration.ofSeconds(30))
                .clock(clock::get)
                .build();

        breaker.tryAcquire();
        breaker.recordFailure();
        breaker.tryAcquire();
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());

        // 冷却未结束
        clock.set(10_000L);
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquire());

        // 冷却结束
        clock.set(31_000L);
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        assertTrue(breaker.tryAcquire(), "半开期放一个试探请求");
        assertFalse(breaker.tryAcquire(), "半开期只允许一个试探请求在途");
        breaker.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        assertEquals(0, breaker.consecutiveFailures());
    }

    @Test
    @DisplayName("半开期试探失败 → 立刻重新打开")
    void halfOpenFailureReopensImmediately() {
        AtomicLong clock = new AtomicLong(0L);
        CircuitBreaker breaker = CircuitBreaker.builder()
                .failureThreshold(1)
                .openDuration(Duration.ofSeconds(10))
                .clock(clock::get)
                .build();

        breaker.tryAcquire();
        breaker.recordFailure();
        clock.set(11_000L);
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        assertTrue(breaker.tryAcquire());
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(), "试探失败必须立刻重新打开");
    }

    @Test
    @DisplayName("连续成功会清零失败计数")
    void successResetsFailureCounter() {
        AtomicLong clock = new AtomicLong(0L);
        CircuitBreaker breaker = CircuitBreaker.builder()
                .failureThreshold(3)
                .clock(clock::get)
                .build();

        breaker.tryAcquire();
        breaker.recordFailure();
        breaker.tryAcquire();
        breaker.recordFailure();
        assertEquals(2, breaker.consecutiveFailures());
        breaker.tryAcquire();
        breaker.recordSuccess();
        assertEquals(0, breaker.consecutiveFailures());
    }
}
