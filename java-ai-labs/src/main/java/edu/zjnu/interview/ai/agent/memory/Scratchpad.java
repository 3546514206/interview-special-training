package edu.zjnu.interview.ai.agent.memory;

import edu.zjnu.interview.ai.core.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 长期笔记板（Scratchpad）：JSONL 追加持久化。
 *
 * <p><b>为什么用 JSONL 而不是 JSON 数组</b>：
 * JSON 数组每次写入都要「读全量 → 反序列化 → 追加 → 写全量」，
 * 进程崩了就会丢最后一段；JSONL 是纯追加，<b>崩溃最多丢最后一行</b>，
 * 而且天然支持流式读取与 grep 式排查。Agent 的长期笔记恰恰是「随时可能崩」的运行时数据，
 * 所以选 JSONL。
 *
 * <p><b>幂等与目录创建</b>：文件与父目录都不存在时自动创建，
 * 避免「第一次运行就因为目录不存在而失败」这种低级问题。
 *
 * <p>时间戳用注入的 {@link Clock}，测试里用固定时钟即可得到确定性输出。
 */
public final class Scratchpad {

    private final Path file;
    private final Clock clock;

    public Scratchpad(Path file) {
        this(file, Clock.systemUTC());
    }

    public Scratchpad(Path file, Clock clock) {
        if (file == null) {
            throw new IllegalArgumentException("scratchpad 文件路径不能为空");
        }
        this.file = file;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Path file() {
        return file;
    }

    /** 追加一条纯文本笔记。 */
    public void append(String note) {
        append(null, null, note);
    }

    /** 追加一条键值笔记。 */
    public void append(String key, String value) {
        append(key, value, null);
    }

    /**
     * 追加一条记录（写 JSONL 一行）。
     *
     * @param key   可为空的键
     * @param value 可为空的值
     * @param note  可为空的自由文本
     */
    public void append(String key, String value, String note) {
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("ts", clock.instant().toString());
        if (key != null) {
            record.put("key", key);
        }
        if (value != null) {
            record.put("value", value);
        }
        if (note != null) {
            record.put("note", note);
        }
        String line = Json.write(record);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("写入 scratchpad 失败: " + file, e);
        }
    }

    /** 读取全部记录（跳过损坏行，保证「脏数据不阻断读取」）。 */
    public List<Entry> readAll() {
        List<Entry> entries = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return entries;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return entries;
        }
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            Map<String, Object> map = Json.readMap(line);
            if (map.isEmpty()) {
                continue;
            }
            String ts = map.get("ts") == null ? "" : String.valueOf(map.get("ts"));
            Instant instant;
            try {
                instant = ts.isEmpty() ? Instant.EPOCH : Instant.parse(ts);
            } catch (Exception e) {
                instant = Instant.EPOCH;
            }
            entries.add(new Entry(instant,
                    map.get("key") == null ? null : String.valueOf(map.get("key")),
                    map.get("value") == null ? null : String.valueOf(map.get("value")),
                    map.get("note") == null ? null : String.valueOf(map.get("note"))));
        }
        return entries;
    }

    /** 记录条数。 */
    public int size() {
        return readAll().size();
    }

    /**
     * 一条笔记。
     *
     * @param timestamp 写入时间
     * @param key       键（可空）
     * @param value     值（可空）
     * @param note      文本（可空）
     */
    public record Entry(Instant timestamp, String key, String value, String note) {
    }
}
