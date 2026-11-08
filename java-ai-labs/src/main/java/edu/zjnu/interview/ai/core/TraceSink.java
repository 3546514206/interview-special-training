package edu.zjnu.interview.ai.core;

/**
 * Trace 输出端。默认实现把 Span 落到 JSONL 文件（见 harness 包），
 * 也可以换成 OTLP / Jaeger / 自研平台，Agent 运行时本身不关心落地方式。
 */
@FunctionalInterface
public interface TraceSink {

    void emit(Span span);

    /** 丢弃所有事件（单测里用）。 */
    static TraceSink noop() {
        return span -> {
        };
    }

    /** 直接打到标准输出（演示用）。 */
    static TraceSink console() {
        return span -> System.out.println(Json.write(span));
    }
}
