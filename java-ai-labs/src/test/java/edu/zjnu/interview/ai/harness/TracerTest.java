package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.Span;
import edu.zjnu.interview.ai.core.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可观测性回归：span 树、错误落盘、JSONL 回放、报告聚合。
 */
class TracerTest {

    @Test
    @DisplayName("父子 span 通过 parentSpanId 组成调用树")
    void spanTreeIsBuiltWithParentLinkage() {
        InMemoryTraceSink sink = new InMemoryTraceSink();
        Tracer tracer = new Tracer("trace-001", sink);

        try (Tracer.Scope root = tracer.span("agent.request", Span.KIND_AGENT_STEP)) {
            root.attr("request_id", "req-1");
            try (Tracer.Scope child = tracer.span("llm.chat", Span.KIND_LLM)) {
                child.attr(SpanAttrs.MODEL, "qwen-plus");
            }
        }

        List<Span> spans = sink.spans();
        assertEquals(2, spans.size(), "两个 span 都应落盘");
        Span child = spans.get(0);
        Span root = spans.get(1);

        assertEquals(root.spanId(), child.parentSpanId(), "子 span 的 parent 必须是父 span");
        assertNull(root.parentSpanId(), "根 span 没有父");
        assertEquals("trace-001", child.traceId());
        assertEquals("llm.chat", child.name());
        assertEquals("qwen-plus", child.attributes().get(SpanAttrs.MODEL));
        assertNotEquals(root.spanId(), child.spanId(), "spanId 必须唯一");
    }

    @Test
    @DisplayName("异常路径也会落 span，并标记为错误（否则错误率永远是 0）")
    void errorSpanIsEmittedOnException() {
        InMemoryTraceSink sink = new InMemoryTraceSink();
        Tracer tracer = new Tracer(sink);

        assertThrowsIllegal(() -> {
            try (Tracer.Scope scope = tracer.span("tool.exec", Span.KIND_TOOL)) {
                scope.markError("BOOM", "模拟失败");
                throw new IllegalStateException("模拟失败");
            }
        });

        assertEquals(1, sink.count());
        Span span = sink.spans().get(0);
        assertTrue(span.error());
        assertEquals("BOOM", span.attributes().get(SpanAttrs.ERROR_CODE));
    }

    @Test
    @DisplayName("JSONL：每行一个 span，可回放")
    void jsonlSinkSupportsReplay() throws IOException {
        Path file = Files.createTempFile("trace-test", ".jsonl");
        try {
            try (JsonlTraceSink sink = JsonlTraceSink.open(file)) {
                Tracer tracer = new Tracer("trace-jsonl", sink);
                try (Tracer.Scope scope = tracer.span("llm.chat", Span.KIND_LLM)) {
                    scope.attr(SpanAttrs.PROMPT_TOKENS, 12);
                    scope.attr(SpanAttrs.COMPLETION_TOKENS, 8);
                    scope.attr(SpanAttrs.COST_USD, 0.0012);
                }
            }
            List<String> lines = Files.readAllLines(file);
            assertEquals(1, lines.size(), "JSONL 一行一个 span");
            assertTrue(lines.get(0).startsWith("{"));

            List<Span> replayed = JsonlTraceSink.replay(file);
            assertEquals(1, replayed.size());
            Span span = replayed.get(0);
            assertEquals("trace-jsonl", span.traceId());
            assertEquals("llm.chat", span.name());
            // JSON 回读后数值类型由 Jackson 决定（小整数是 Integer），因此按数字取值再比较
            assertEquals(12, ((Number) span.attributes().get(SpanAttrs.PROMPT_TOKENS)).intValue());
            assertEquals(8, ((Number) span.attributes().get(SpanAttrs.COMPLETION_TOKENS)).intValue());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("TraceReport 汇总次数 / 平均耗时 / 错误率 / token / 成本")
    void traceReportAggregatesMetrics() {
        InMemoryTraceSink sink = new InMemoryTraceSink();
        Tracer tracer = new Tracer(sink);

        for (int i = 0; i < 3; i++) {
            try (Tracer.Scope scope = tracer.span("llm.chat", Span.KIND_LLM)) {
                scope.attr(SpanAttrs.PROMPT_TOKENS, 100);
                scope.attr(SpanAttrs.COMPLETION_TOKENS, 50);
                scope.attr(SpanAttrs.COST_USD, 0.01);
                if (i == 2) {
                    scope.markError("E", "第三次失败");
                }
            }
        }

        TraceReport report = TraceReport.from(sink);
        TraceReport.SpanStats stats = report.stats("llm.chat");
        assertEquals(3, stats.count());
        assertEquals(1, stats.errorCount());
        assertEquals(1.0 / 3.0, stats.errorRate(), 1e-9);
        assertEquals(300, stats.tokens().promptTokens());
        assertEquals(150, stats.tokens().completionTokens());
        assertEquals(0.03, stats.costUsd(), 1e-9);
        assertEquals(0.03, report.totalCostUsd(), 1e-9);
        assertEquals(450, report.totalTokens().totalTokens());
        assertTrue(stats.avgDurationMillis() >= 0.0);
        assertFalse(report.toText().isEmpty());
    }

    @Test
    @DisplayName("span 树渲染能看出父子层级")
    void treeRenderingShowsHierarchy() {
        InMemoryTraceSink sink = new InMemoryTraceSink();
        Tracer tracer = new Tracer(sink);
        try (Tracer.Scope root = tracer.span("agent.request", Span.KIND_AGENT_STEP)) {
            try (Tracer.Scope child = tracer.span("llm.chat", Span.KIND_LLM)) {
                child.attr(SpanAttrs.PROMPT_TOKENS, 1);
            }
        }
        String tree = TraceReport.renderTree(sink.spans());
        assertTrue(tree.contains("agent.request"));
        assertTrue(tree.contains("llm.chat"));
        assertTrue(tree.indexOf("agent.request") < tree.indexOf("llm.chat"), "父节点应先出现");
    }

    private static void assertThrowsIllegal(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("期望抛出 IllegalStateException");
    }
}
