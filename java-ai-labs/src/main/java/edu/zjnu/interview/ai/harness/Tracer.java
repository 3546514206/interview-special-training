package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.Ids;
import edu.zjnu.interview.ai.core.Span;
import edu.zjnu.interview.ai.core.ToolContext;
import edu.zjnu.interview.ai.core.TraceSink;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 链路追踪器：生成 traceId、构造 span 树、计时，并把「当前 spanId」传给下游。
 *
 * <p><b>为什么需要它</b>：一次用户请求在 Agent 场景里会扇出成
 * 「N 次模型调用 + M 次工具调用 + K 次检索」。没有父子关系时，
 * 你只能看到一堆平铺的日志，无法回答「这次请求总共花了多少钱」「哪一步最慢」
 * 「工具失败率是多少」这三个决定上不上线的问题。
 *
 * <p><b>设计要点</b>：
 * <ol>
 *   <li><b>span 树靠 parentSpanId 表达</b>：打开子 span 时自动继承父 span 的 id，
 *       不需要调用方手工传递——手工传递早晚会漏。</li>
 *   <li><b>时间用 nanoTime 计时、epochMillis 记录</b>：耗时用单调时钟算（不受系统时间调整影响），
 *       落库的 start 时间用墙上时钟（便于和日志对齐）。</li>
 *   <li><b>Span 构造走 AutoCloseable</b>：try-with-resources 保证异常路径下也会落 span，
 *       这正是「错误率」这个指标能被算出来的前提——如果只在成功路径上报，
 *       错误率永远是 0。</li>
 *   <li><b>ThreadLocal 维护栈</b>：一个请求内可能并发调用工具（并行扇出），
 *       每个线程各自维护自己的父子栈，互不串扰。</li>
 * </ol>
 *
 * <p>本类不抛异常：Trace 是「旁路观测」，它挂掉绝不能影响主链路。
 */
public final class Tracer {

    private final String traceId;
    private final TraceSink sink;
    private final ThreadLocal<Deque<Scope>> stack = ThreadLocal.withInitial(ArrayDeque::new);
    private final AtomicLong sequence = new AtomicLong();

    public Tracer(TraceSink sink) {
        this(Ids.random(), sink);
    }

    public Tracer(String traceId, TraceSink sink) {
        this.traceId = (traceId == null || traceId.isBlank()) ? Ids.random() : traceId;
        this.sink = sink == null ? TraceSink.noop() : sink;
    }

    /** 这次端到端请求的 id。 */
    public String traceId() {
        return traceId;
    }

    /** Trace 输出端。 */
    public TraceSink sink() {
        return sink;
    }

    /** 打开一个 span（父 span = 当前栈顶）。 */
    public Scope span(String name, String kind) {
        return span(name, kind, Map.of());
    }

    /**
     * 打开一个 span。
     *
     * @param name       span 名，如 {@code llm.chat}
     * @param kind       span 类别，见 {@link Span#KIND_LLM} 等
     * @param attributes 初始属性
     */
    public Scope span(String name, String kind, Map<String, Object> attributes) {
        Deque<Scope> current = stack.get();
        Scope parent = current.peek();
        String parentSpanId = parent == null ? null : parent.spanId();
        long startMillis = System.currentTimeMillis();
        long startNanos = System.nanoTime();
        // spanId 必须「先于」子 span 生成：子 span 需要把自己的 parentSpanId 填成父 span 的 id，
        // 而父 span 的 id 又要依赖完整属性才能算出来 → 只能提前分配。
        // 用自增序号参与哈希，保证同一条 trace 内绝不重复（core.Span 的默认 builder 做不到这点）。
        long seq = sequence.incrementAndGet();
        String spanId = "span_" + Ids.shortHash(traceId + "|" + name + "|" + seq + "|" + startMillis);
        Scope scope = new Scope(name, kind, spanId, parentSpanId, startMillis, startNanos);
        if (attributes != null) {
            attributes.forEach(scope::attr);
        }
        current.push(scope);
        return scope;
    }

    /** 当前 span id；没有打开的 span 时返回 null。 */
    public String currentSpanId() {
        Scope scope = stack.get().peek();
        return scope == null ? null : scope.spanId();
    }

    /** 当前 span 的父 id。 */
    public String currentParentSpanId() {
        Scope scope = stack.get().peek();
        return scope == null ? null : scope.parentSpanId();
    }

    /**
     * 把当前 trace / span 上下文交给下游工具。
     *
     * <p>这是「把当前 spanId 传给下游」的落点：工具拿到的 {@link ToolContext}
     * 里带着 spanId，工具内部再开子 span 时就能自动挂到正确的父节点上。
     */
    public ToolContext currentToolContext(Path workingDirectory) {
        return ToolContext.of(traceId, workingDirectory).withSpanId(currentSpanId());
    }

    /** 以 span 包裹一段逻辑，异常时自动标记错误。 */
    public <T> T traced(String name, String kind, Supplier<T> body) {
        try (Scope scope = span(name, kind)) {
            try {
                return body.get();
            } catch (RuntimeException | Error e) {
                scope.markError(e.getMessage());
                throw e;
            }
        }
    }

    /**
     * 一个正在计时的 span。
     *
     * <p>实现为 {@link AutoCloseable}：正常返回与抛异常都会关闭，
     * 关闭时把 span 推给 {@link TraceSink}。
     */
    public final class Scope implements AutoCloseable {

        private final String name;
        private final String kind;
        private final String spanId;
        private final String parentSpanId;
        private final long startMillis;
        private final long startNanos;
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private boolean error;
        private String errorMessage;
        private boolean closed;

        private Scope(String name, String kind, String spanId, String parentSpanId,
                      long startMillis, long startNanos) {
            this.name = name;
            this.kind = kind;
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
            this.startMillis = startMillis;
            this.startNanos = startNanos;
        }

        /** 当前 span id（下游用它做 parentSpanId）。 */
        public String spanId() {
            return spanId;
        }

        public String parentSpanId() {
            return parentSpanId;
        }

        public String name() {
            return name;
        }

        public String kind() {
            return kind;
        }

        /**
         * 追加属性。
         *
         * <p>忽略 null 值：{@code Span} 的构造器会做 {@code Map.copyOf}，
         * 而它对 null 值会直接抛 NPE——观测代码不应该有能力搞挂主链路。
         */
        public Scope attr(String key, Object value) {
            if (key == null || value == null) {
                return this;
            }
            attributes.put(key, value);
            return this;
        }

        public Scope attr(String key, long value) {
            attributes.put(key, value);
            return this;
        }

        public Scope attr(String key, double value) {
            attributes.put(key, value);
            return this;
        }

        /** 标记为错误 span。 */
        public Scope markError(String message) {
            this.error = true;
            this.errorMessage = message;
            if (message != null) {
                attributes.put(SpanAttrs.ERROR_MESSAGE, message);
            }
            return this;
        }

        /** 标记错误并附错误码。 */
        public Scope markError(String code, String message) {
            markError(message);
            if (code != null) {
                attributes.put(SpanAttrs.ERROR_CODE, code);
            }
            return this;
        }

        /** 当前 span 的属性快照。 */
        public Map<String, Object> attributes() {
            return Map.copyOf(attributes);
        }

        public boolean isError() {
            return error;
        }

        public String errorMessage() {
            return errorMessage;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Deque<Scope> current = stack.get();
            if (!current.isEmpty() && current.peek() == this) {
                current.pop();
            } else {
                // 乱序关闭（理论上不该发生）时也要把自身摘掉，避免栈错位。
                current.remove(this);
            }
            long duration = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
            try {
                sink.emit(new Span(traceId, spanId, parentSpanId, name, kind, startMillis, duration,
                        attributes, error));
            } catch (RuntimeException ignored) {
                // 观测链路不允许影响业务链路：sink 落盘失败只丢事件，不抛。
            }
        }
    }
}
