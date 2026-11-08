package edu.zjnu.interview.ai.agent;

import edu.zjnu.interview.ai.core.Ids;
import edu.zjnu.interview.ai.core.Span;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Span 工厂：统一管理「一条链路里 span id 的分配与父子关系」。
 *
 * <p><b>为什么不用 core.Span.builder()</b>：core 的 builder 在 {@code build()} 内部生成 spanId，
 * 而 Agent Loop 有一个硬需求——<b>工具执行之前就要把 spanId 放进 {@link edu.zjnu.interview.ai.core.ToolContext}</b>
 * （spanId 向下传递），否则工具内部的日志、检索事件就无法挂到正确的父节点上。
 * builder 拿不到「预先分配的 id」，所以这里直接用 Span 的 record 构造器自己控制 id 分配。
 *
 * <p>id 分配规则：{@code traceId + "#" + 自增序号} 的哈希。
 * 用自增序号而不是随机数，是为了让同一条链路里的 span id 稳定可复现（便于测试断言与离线排查）。
 *
 * <p>同时保证 <b>start 时间单调不降</b>：同一毫秒内创建多个 span 时，
 * 若 start 相同，core 的 spanId 生成公式（traceId+name+start+attributes.size()）可能撞号，
 * 这里通过自增修正错开 start，避免父子关系错乱。
 */
public final class SpanFactory {

    private final String traceId;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong lastStart = new AtomicLong();

    public SpanFactory(String traceId) {
        this.traceId = traceId == null || traceId.isBlank() ? "trace_" + Ids.shortHash("agent") : traceId;
    }

    public String traceId() {
        return traceId;
    }

    /** 预分配一个 span id（用于在子对象创建前把 id 传下去）。 */
    public String reserveSpanId() {
        return "span_" + Ids.shortHash(traceId + "#" + sequence.incrementAndGet());
    }

    /** 创建一个 span（自动分配 id）。 */
    public Span emit(String name,
                     String kind,
                     String parentSpanId,
                     Map<String, Object> attributes,
                     long startMillis,
                     long durationMillis,
                     boolean error) {
        return emit(reserveSpanId(), name, kind, parentSpanId, attributes, startMillis, durationMillis, error);
    }

    /** 创建一个 span（使用预先分配的 id，保证父子关系稳定）。 */
    public Span emit(String spanId,
                     String name,
                     String kind,
                     String parentSpanId,
                     Map<String, Object> attributes,
                     long startMillis,
                     long durationMillis,
                     boolean error) {
        // core.Span 的紧凑构造器会做 Map.copyOf，而 Map.copyOf 不接受 null value。
        // 这里统一把 null 属性剔掉：属性是「可选信息」，缺了就该缺席，而不是让整个链路崩掉。
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (attributes != null) {
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    attrs.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return new Span(traceId, spanId, parentSpanId, name, kind, startMillis,
                Math.max(0L, durationMillis), attrs, error);
    }

    /** 单调不降的时间戳，保证同一毫秒内的多个 span 不会撞号。 */
    public long monotonicMillis() {
        long now = System.currentTimeMillis();
        return lastStart.updateAndGet(prev -> Math.max(now, prev + 1));
    }
}
