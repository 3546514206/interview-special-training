package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.Span;
import edu.zjnu.interview.ai.core.TraceSink;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 内存版 Trace 输出端，供单元测试断言使用。
 *
 * <p><b>为什么测试不用 JSONL</b>：落盘会引入「文件系统状态」「编码」「行序」等噪声，
 * 让断言从「语义是否正确」退化成「文件内容是否逐字节相同」。
 * 内存 sink 让测试只关心业务语义；JSONL 的序列化正确性由专门的回放测试来覆盖。
 *
 * <p>用 {@link CopyOnWriteArrayList}：Trace 写入是低频的（一次请求几十条），
 * 而读取（测试断言、报告聚合）更频繁，COW 的读优势正好合适。
 */
public final class InMemoryTraceSink implements TraceSink {

    private final List<Span> spans = new CopyOnWriteArrayList<>();

    @Override
    public void emit(Span span) {
        if (span != null) {
            spans.add(span);
        }
    }

    /** 全部 span（按写入顺序）。 */
    public List<Span> spans() {
        return List.copyOf(spans);
    }

    /** 某个类别的 span。 */
    public List<Span> spansOfKind(String kind) {
        return spans.stream().filter(s -> kind.equals(s.kind())).collect(Collectors.toList());
    }

    /** 按名字过滤。 */
    public List<Span> spansOfName(String name) {
        return spans.stream().filter(s -> name.equals(s.name())).collect(Collectors.toList());
    }

    /** 某个 trace 下的全部 span。 */
    public List<Span> spansOfTrace(String traceId) {
        return spans.stream().filter(s -> traceId.equals(s.traceId())).collect(Collectors.toList());
    }

    /** 错误 span。 */
    public List<Span> errorSpans() {
        return spans.stream().filter(Span::error).collect(Collectors.toList());
    }

    /** span 总数。 */
    public int count() {
        return spans.size();
    }

    /** 是否存在满足条件的 span。 */
    public boolean anyMatch(java.util.function.Predicate<Span> predicate) {
        return spans.stream().anyMatch(predicate);
    }

    /** 第一个满足条件的 span。 */
    public Span first(java.util.function.Predicate<Span> predicate) {
        return spans.stream().filter(predicate).findFirst().orElse(null);
    }

    /** 按开始时间排序的 span 列表（构造调用树时用）。 */
    public List<Span> chronological() {
        List<Span> copy = new ArrayList<>(spans);
        copy.sort(Comparator.comparingLong(Span::startEpochMillis));
        return copy;
    }

    /** 清空。 */
    public void clear() {
        spans.clear();
    }
}
