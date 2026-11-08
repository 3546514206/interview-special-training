package edu.zjnu.interview.ai.harness;

import java.time.Duration;

/**
 * 可注入的睡眠器。
 *
 * <p><b>为什么要抽象它</b>：退避（backoff）本质上就是 sleep，
 * 但测试里 sleep 是灾难——单元测试变慢、偶发失败、CI 上不稳定。
 * 所以把「等待」抽象成一个端口，生产注入真实实现，测试注入一个
 * 「只记录不真等」的实现，既能断言退避时长，又不花掉真实时间。
 *
 * <p>这和 {@code Thread.sleep} 的区别不是「更优雅」，而是<b>让等待变成可观测、可断言的数据</b>。
 */
@FunctionalInterface
public interface Sleeper {

    /** 等待指定时长。 */
    void sleep(Duration duration) throws InterruptedException;

    /** 真实等待（生产用）。 */
    static Sleeper real() {
        return duration -> {
            if (duration != null && !duration.isZero() && !duration.isNegative()) {
                Thread.sleep(duration.toMillis());
            }
        };
    }

    /** 不等待（测试用）：只把时长记录下来由调用方断言。 */
    static Sleeper noop() {
        return duration -> {
        };
    }
}
