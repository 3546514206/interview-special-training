package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.Span;
import edu.zjnu.interview.ai.core.TokenUsage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trace 汇总报告：每类 span 的调用次数 / 平均耗时 / 错误率 / token 与成本。
 *
 * <p><b>为什么报告要「按名字」聚合而不是只按 kind</b>：
 * {@code llm.chat} 和 {@code llm.embed} 都属于 llm，但它们的成本量级完全不同；
 * 只按 kind 聚合会把「嵌入调用太贵」这种问题稀释掉。
 * 所以这里按 span 名分组，同时保留 kind 级别的汇总作为总览。
 *
 * <p><b>成本与 token 从 span 属性里取</b>，而不是另建一套账本：
 * 账本和 trace 是两套数据时必然对不上（重试算了几次？降级算了几次？）。
 * 让「计量发生在调用路径上」并且「同一份数字同时进 trace 和进账本」，
 * 是成本可审计的唯一做法。
 */
public final class TraceReport {

    /**
     * 一类 span 的聚合指标。
     *
     * @param name              span 名
     * @param kind              span 类别
     * @param count             调用次数
     * @param avgDurationMillis 平均耗时
     * @param maxDurationMillis 最大耗时
     * @param totalDurationMillis 总耗时
     * @param errorCount        错误次数
     * @param errorRate         错误率（0~1）
     * @param tokens            token 合计
     * @param costUsd           成本合计（美元）
     */
    public record SpanStats(String name,
                            String kind,
                            long count,
                            double avgDurationMillis,
                            long maxDurationMillis,
                            long totalDurationMillis,
                            long errorCount,
                            double errorRate,
                            TokenUsage tokens,
                            double costUsd) {
    }

    private final List<Span> spans;
    private final List<SpanStats> byName;
    private final Map<String, SpanStats> byKind;
    private final TokenUsage totalTokens;
    private final double totalCostUsd;
    private final long errorCount;

    private TraceReport(List<Span> spans) {
        this.spans = List.copyOf(spans);
        Map<String, SpanStats> nameMap = new LinkedHashMap<>();
        Map<String, SpanStats> kindMap = new LinkedHashMap<>();
        TokenUsage tokens = TokenUsage.ZERO;
        double cost = 0.0;
        long errors = 0L;

        for (Span span : this.spans) {
            SpanStats nameStats = nameMap.computeIfAbsent(span.name(), k -> new SpanStats(k, span.kind(), 0, 0, 0, 0, 0, 0, TokenUsage.ZERO, 0));
            SpanStats kindStats = kindMap.computeIfAbsent(span.kind(), k -> new SpanStats(k, span.kind(), 0, 0, 0, 0, 0, 0, TokenUsage.ZERO, 0));
            TokenUsage spanTokens = tokensOf(span);
            double spanCost = costOf(span);

            nameMap.put(span.name(), merge(nameStats, span, spanTokens, spanCost));
            kindMap.put(span.kind(), merge(kindStats, span, spanTokens, spanCost));
            tokens = tokens.plus(spanTokens);
            cost += spanCost;
            if (span.error()) {
                errors++;
            }
        }
        this.byName = new ArrayList<>(nameMap.values());
        this.byKind = kindMap;
        this.totalTokens = tokens;
        this.totalCostUsd = cost;
        this.errorCount = errors;
    }

    /** 从 span 列表汇总。 */
    public static TraceReport of(List<Span> spans) {
        return new TraceReport(spans == null ? List.of() : spans);
    }

    /** 从内存 sink 汇总。 */
    public static TraceReport from(InMemoryTraceSink sink) {
        return new TraceReport(sink.spans());
    }

    /** 从 JSONL 文件回放后汇总（离线分析主路径）。 */
    public static TraceReport fromJsonl(Path file) throws IOException {
        return new TraceReport(JsonlTraceSink.replay(file));
    }

    /** 按 span 名的聚合结果。 */
    public List<SpanStats> byName() {
        return List.copyOf(byName);
    }

    /** 按 span 类别的聚合结果。 */
    public Map<String, SpanStats> byKind() {
        return Map.copyOf(byKind);
    }

    /** 全部 span。 */
    public List<Span> spans() {
        return spans;
    }

    /** token 合计。 */
    public TokenUsage totalTokens() {
        return totalTokens;
    }

    /** 成本合计（美元）。 */
    public double totalCostUsd() {
        return totalCostUsd;
    }

    /** 错误 span 数。 */
    public long errorCount() {
        return errorCount;
    }

    /** 总错误率。 */
    public double errorRate() {
        return spans.isEmpty() ? 0.0 : (double) errorCount / spans.size();
    }

    /** 取某一类 span 的统计（没有则返回 null）。 */
    public SpanStats stats(String name) {
        for (SpanStats stats : byName) {
            if (stats.name().equals(name)) {
                return stats;
            }
        }
        return null;
    }

    /** 文本报告（演示与面试讲解用）。 */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Trace 汇总 ===\n");
        sb.append(String.format("span 总数: %d, 错误数: %d, 错误率: %.2f%%%n",
                spans.size(), errorCount, errorRate() * 100));
        sb.append(String.format("token 合计: prompt=%d, completion=%d, total=%d%n",
                totalTokens.promptTokens(), totalTokens.completionTokens(), totalTokens.totalTokens()));
        sb.append(String.format("成本合计: $%.6f%n", totalCostUsd));
        sb.append(String.format("%-24s %-14s %6s %10s %8s %10s %12s%n",
                "SPAN", "KIND", "次数", "平均耗时(ms)", "错误率", "token", "成本(USD)"));
        for (SpanStats stats : byName) {
            sb.append(String.format("%-24s %-14s %6d %10.1f %7.2f%% %10d %12.6f%n",
                    stats.name(), stats.kind(), stats.count(), stats.avgDurationMillis(),
                    stats.errorRate() * 100, stats.tokens().totalTokens(), stats.costUsd()));
        }
        return sb.toString();
    }

    /**
     * 渲染 span 树（父子关系可视化）。
     *
     * <p>树形输出比平铺日志强的地方在于：一眼能看出「哪一层在扇出」、
     * 「耗时是不是集中在某个子分支」。
     */
    public static String renderTree(List<Span> spans) {
        if (spans == null || spans.isEmpty()) {
            return "(空 trace)\n";
        }
        Map<String, Span> byId = new LinkedHashMap<>();
        for (Span span : spans) {
            if (span.spanId() != null) {
                byId.put(span.spanId(), span);
            }
        }
        Map<String, List<Span>> children = new LinkedHashMap<>();
        List<Span> roots = new ArrayList<>();
        for (Span span : spans) {
            String parent = span.parentSpanId();
            if (parent != null && byId.containsKey(parent)) {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(span);
            } else {
                roots.add(span);
            }
        }
        Comparator<Span> byStart = Comparator.comparingLong(Span::startEpochMillis);
        roots.sort(byStart);
        children.values().forEach(list -> list.sort(byStart));

        StringBuilder sb = new StringBuilder();
        for (Span root : roots) {
            renderNode(sb, root, children, 0);
        }
        return sb.toString();
    }

    private static void renderNode(StringBuilder sb, Span span, Map<String, List<Span>> children, int depth) {
        sb.append("  ".repeat(depth));
        sb.append(span.name());
        sb.append(" [").append(span.kind()).append("]");
        sb.append(" ").append(span.durationMillis()).append("ms");
        if (span.error()) {
            sb.append(" ERROR");
            Object msg = span.attributes().get(SpanAttrs.ERROR_MESSAGE);
            if (msg != null) {
                sb.append(": ").append(msg);
            }
        }
        Long prompt = longAttr(span, SpanAttrs.PROMPT_TOKENS);
        Long completion = longAttr(span, SpanAttrs.COMPLETION_TOKENS);
        if (prompt != null || completion != null) {
            sb.append(" tokens=").append(prompt == null ? 0 : prompt).append("/")
                    .append(completion == null ? 0 : completion);
        }
        Object cost = span.attributes().get(SpanAttrs.COST_USD);
        if (cost instanceof Number n) {
            sb.append(String.format(" cost=$%.6f", n.doubleValue()));
        }
        sb.append('\n');
        for (Span child : children.getOrDefault(span.spanId(), List.of())) {
            renderNode(sb, child, children, depth + 1);
        }
    }

    private static Long longAttr(Span span, String key) {
        Object value = span.attributes().get(key);
        return value instanceof Number n ? n.longValue() : null;
    }

    static TokenUsage tokensOf(Span span) {
        Long prompt = longAttr(span, SpanAttrs.PROMPT_TOKENS);
        Long completion = longAttr(span, SpanAttrs.COMPLETION_TOKENS);
        if (prompt == null && completion == null) {
            return TokenUsage.ZERO;
        }
        return TokenUsage.of(prompt == null ? 0 : prompt.intValue(), completion == null ? 0 : completion.intValue());
    }

    static double costOf(Span span) {
        Object value = span.attributes().get(SpanAttrs.COST_USD);
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static SpanStats merge(SpanStats old, Span span, TokenUsage spanTokens, double spanCost) {
        long count = old.count() + 1;
        long totalDuration = old.totalDurationMillis() + span.durationMillis();
        long maxDuration = Math.max(old.maxDurationMillis(), span.durationMillis());
        long errors = old.errorCount() + (span.error() ? 1 : 0);
        return new SpanStats(old.name(), old.kind(), count,
                count == 0 ? 0 : (double) totalDuration / count,
                maxDuration, totalDuration, errors,
                count == 0 ? 0 : (double) errors / count,
                old.tokens().plus(spanTokens),
                old.costUsd() + spanCost);
    }
}
