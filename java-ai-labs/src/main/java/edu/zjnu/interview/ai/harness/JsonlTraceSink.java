package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.core.Span;
import edu.zjnu.interview.ai.core.TraceSink;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSONL 落盘的 Trace 输出端：每行一个 span。
 *
 * <p><b>为什么用 JSONL 而不是 JSON 数组</b>：
 * <ul>
 *   <li>写是 O(1) 追加的：进程被 kill 时最多丢最后一行，不会像 JSON 数组那样「整个文件都废了」；</li>
 *   <li>读可以流式：离线分析时按行过滤（如 {@code grep '"kind":"tool"'}）不需要把整个文件读进内存；</li>
 *   <li>天然兼容日志采集器：Fluentd / Vector / Loki 都是按行读的。</li>
 * </ul>
 *
 * <p><b>replay 的意义</b>：有了 JSONL，一次线上排障就变成「把现场文件拷回本地，
 * 离线重放、聚合、算成本」，不需要连着数据库复现故障。
 * 这也是「可回归」的前提——CI 里可以拿历史 trace 做断言。
 *
 * <p><b>容错约定</b>：读回时单行解析失败只跳过该行并计数，不让脏数据毁掉整份回放。
 */
public final class JsonlTraceSink implements TraceSink, AutoCloseable {

    private final Path file;
    private final BufferedWriter writer;
    private final boolean autoFlush;

    private JsonlTraceSink(Path file, BufferedWriter writer, boolean autoFlush) {
        this.file = file;
        this.writer = writer;
        this.autoFlush = autoFlush;
    }

    /** 打开（覆盖写）一个 JSONL 文件。 */
    public static JsonlTraceSink open(Path file) throws IOException {
        return open(file, true);
    }

    /**
     * 打开一个 JSONL 文件。
     *
     * @param autoFlush 是否每行立即落盘。实验/测试环境建议 true：
     *                  进程异常退出时最多丢最后一行，而不是丢一整段缓冲。
     */
    public static JsonlTraceSink open(Path file, boolean autoFlush) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        return new JsonlTraceSink(file, writer, autoFlush);
    }

    /** 追加模式（多次运行写同一个文件时用）。 */
    public static JsonlTraceSink append(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        return new JsonlTraceSink(file, writer, true);
    }

    public Path file() {
        return file;
    }

    @Override
    public synchronized void emit(Span span) {
        if (span == null) {
            return;
        }
        try {
            writer.write(Json.write(span));
            writer.newLine();
            if (autoFlush) {
                writer.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写 trace 文件失败: " + file, e);
        }
    }

    /** 强制落盘。 */
    public synchronized void flush() {
        try {
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("flush trace 文件失败: " + file, e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException("关闭 trace 文件失败: " + file, e);
        }
    }

    /**
     * 回放：把 JSONL 文件读回 span 列表（离线分析用）。
     *
     * <p>解析采用「逐行手工取字段」而不是直接反序列化成 {@link Span}：
     * 手工解析对字段缺失、类型漂移更宽容——历史 trace 文件不应该因为新版本加了字段就读不出来。
     */
    public static List<Span> replay(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return replay(reader);
        }
    }

    /** 回放（从任意 Reader）。 */
    public static List<Span> replay(BufferedReader reader) throws IOException {
        List<Span> spans = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Span span = parseLine(line);
            if (span != null) {
                spans.add(span);
            }
        }
        return spans;
    }

    /** 解析单行；失败返回 null（跳过脏行，不影响整体回放）。 */
    static Span parseLine(String line) {
        Map<String, Object> map = Json.readMap(line);
        if (map.isEmpty()) {
            return null;
        }
        String traceId = str(map.get("traceId"));
        String spanId = str(map.get("spanId"));
        String name = str(map.get("name"));
        if (name == null) {
            return null;
        }
        String parentSpanId = str(map.get("parentSpanId"));
        String kind = str(map.get("kind"));
        long start = num(map.get("startEpochMillis"));
        long duration = num(map.get("durationMillis"));
        boolean error = Boolean.TRUE.equals(map.get("error"));
        Map<String, Object> attributes = new LinkedHashMap<>();
        Object attrs = map.get("attributes");
        if (attrs instanceof Map<?, ?> attrMap) {
            attrMap.forEach((k, v) -> {
                if (k != null && v != null) {
                    attributes.put(String.valueOf(k), v);
                }
            });
        }
        return new Span(traceId, spanId, parentSpanId, name, kind == null ? "unknown" : kind,
                start, duration, attributes, error);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long num(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }
}
