package edu.zjnu.interview.ai.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次可观测事件（Span）。
 *
 * <p>Agent 系统的可观测性不能只靠日志：一次用户请求会扇出成
 * 「N 次模型调用 + M 次工具调用 + K 次检索」，成本与稳定性问题都藏在扇出里。
 * 用统一的 Span 结构把这条链路记录下来，才能回答：
 * 这次请求花了多少钱、慢在哪一步、哪一步的失败率最高、Prompt 到底长什么样。
 *
 * @param traceId         一次端到端请求的 id
 * @param spanId          当前 span id
 * @param parentSpanId    父 span id（构成调用树）
 * @param name            名称，如 {@code llm.chat} / {@code tool.exec}
 * @param kind            类别：llm / tool / retrieval / agent_step / workflow_node
 * @param startEpochMillis 开始时间
 * @param durationMillis  耗时
 * @param attributes      结构化属性（token 数、工具名、命中数……）
 * @param error           是否出错
 */
public record Span(String traceId,
                   String spanId,
                   String parentSpanId,
                   String name,
                   String kind,
                   long startEpochMillis,
                   long durationMillis,
                   Map<String, Object> attributes,
                   boolean error) {

    public static final String KIND_LLM = "llm";
    public static final String KIND_TOOL = "tool";
    public static final String KIND_RETRIEVAL = "retrieval";
    public static final String KIND_AGENT_STEP = "agent_step";
    public static final String KIND_WORKFLOW_NODE = "workflow_node";
    public static final String KIND_RAG_PIPELINE = "rag_pipeline";

    public Span {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static Builder builder(String name, String kind) {
        return new Builder(name, kind);
    }

    /** Span 构造器。 */
    public static final class Builder {
        private final String name;
        private final String kind;
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private String traceId;
        private String parentSpanId;
        private long start = System.currentTimeMillis();
        private long duration;
        private boolean error;

        private Builder(String name, String kind) {
            this.name = name;
            this.kind = kind;
        }

        public Builder traceId(String value) {
            this.traceId = value;
            return this;
        }

        public Builder parentSpanId(String value) {
            this.parentSpanId = value;
            return this;
        }

        public Builder attribute(String key, Object value) {
            this.attributes.put(key, value);
            return this;
        }

        public Builder start(long epochMillis) {
            this.start = epochMillis;
            return this;
        }

        public Builder duration(long millis) {
            this.duration = millis;
            return this;
        }

        public Builder error(boolean value) {
            this.error = value;
            return this;
        }

        public Span build() {
            String tid = traceId == null ? "trace_" + Ids.shortHash(name + start) : traceId;
            String sid = "span_" + Ids.shortHash(tid + name + start + attributes.size());
            return new Span(tid, sid, parentSpanId, name, kind, start, duration, attributes, error);
        }
    }
}
