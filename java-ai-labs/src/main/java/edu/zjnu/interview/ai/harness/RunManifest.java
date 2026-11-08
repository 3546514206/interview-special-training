package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 运行清单：把「一次运行是怎么跑出来的」固化成一个可归档、可比对的 JSON 文件。
 *
 * <p><b>为什么 AI 系统特别需要它</b>：
 * 传统软件里，「同样的输入 + 同样的代码 = 同样的输出」；
 * 但 LLM 系统里还多了三个变量：<b>模型版本、Prompt 模板、语料/代码快照</b>。
 * 任何一个变了，结果就可能变。所以「效果不错」这句话如果没有附带这三样东西，
 * 就<b>无法复现、无法归因</b>——出了问题也不知道是模型换了、Prompt 改了，还是数据污染了。
 *
 * <p><b>清单里记什么</b>：
 * <ul>
 *   <li><b>模型</b>：名字 + 关键参数（模型版本比模型名更重要，模型名可能一直不变但权重在变）；</li>
 *   <li><b>关键配置</b>：温度、top_p、超时、重试次数、预算阈值……；</li>
 *   <li><b>语料 / 代码指纹</b>：对目录内容做 SHA-256（路径 + 内容），一串 16 进制就能定位到具体快照；</li>
 *   <li><b>随机种子</b>：温度 > 0 时采样本身带随机性，固定种子才谈得上「回归」。</li>
 * </ul>
 *
 * <p>用法：每次跑实验先写一份 manifest，再跑；出问题把 manifest 一起捞出来。
 */
public record RunManifest(String runId,
                          String createdAt,
                          Map<String, Object> models,
                          Map<String, Object> config,
                          Map<String, String> fingerprints,
                          long randomSeed,
                          String notes) {

    public RunManifest {
        models = models == null ? Map.of() : Map.copyOf(models);
        config = config == null ? Map.of() : Map.copyOf(config);
        fingerprints = fingerprints == null ? Map.of() : Map.copyOf(fingerprints);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 写到 JSON 文件（自动建目录）。 */
    public Path writeTo(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, Json.writePretty(this) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    /** 从 JSON 文件读回。 */
    public static RunManifest read(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, Object> map = Json.readMap(json);
        if (map.isEmpty()) {
            throw new IOException("运行清单解析失败: " + file);
        }
        Map<String, Object> models = subMap(map.get("models"));
        Map<String, Object> config = subMap(map.get("config"));
        Map<String, String> fingerprints = new LinkedHashMap<>();
        subMap(map.get("fingerprints")).forEach((k, v) -> fingerprints.put(k, String.valueOf(v)));
        long seed = 0L;
        Object seedValue = map.get("randomSeed");
        if (seedValue instanceof Number n) {
            seed = n.longValue();
        }
        return new RunManifest(stringValue(map.get("runId")), stringValue(map.get("createdAt")),
                models, config, fingerprints, seed, stringValue(map.get("notes")));
    }

    /**
     * 计算目录指纹：对「相对路径 + 文件内容」排序后做 SHA-256。
     *
     * <p>为什么不是「文件修改时间」：时间戳会被复制、checkout、压缩破坏；
     * 内容哈希才真正对应「这批代码/语料是什么」。
     */
    public static String fingerprintOf(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return "";
        }
        MessageDigest digest = sha256();
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(root)) {
            files.add(root);
        } else {
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(files::add);
            }
        }
        files.sort(Comparator.comparing(Path::toString));
        for (Path file : files) {
            Path relative = Files.isRegularFile(root) ? root.getFileName() : root.relativize(file);
            digest.update(relative.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(file));
            digest.update((byte) 0);
        }
        return hex(digest.digest());
    }

    /** 计算单个文件的指纹。 */
    public static String fingerprintOfFile(Path file) throws IOException {
        return fingerprintOf(file);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> subMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 构造器。 */
    public static final class Builder {
        private String runId;
        private String createdAt;
        private final Map<String, Object> models = new LinkedHashMap<>();
        private final Map<String, Object> config = new LinkedHashMap<>();
        private final Map<String, String> fingerprints = new LinkedHashMap<>();
        private long randomSeed;
        private String notes;

        public Builder runId(String value) {
            this.runId = value;
            return this;
        }

        public Builder createdAt(String value) {
            this.createdAt = value;
            return this;
        }

        /** 记录模型信息。 */
        public Builder model(String name, String version, Map<String, Object> params) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            if (version != null) {
                entry.put("version", version);
            }
            if (params != null) {
                entry.putAll(params);
            }
            models.put(name, entry);
            return this;
        }

        /** 记录关键配置。 */
        public Builder config(String key, Object value) {
            if (key != null && value != null) {
                config.put(key, value);
            }
            return this;
        }

        /** 记录指纹。 */
        public Builder fingerprint(String name, String value) {
            if (name != null && value != null) {
                fingerprints.put(name, value);
            }
            return this;
        }

        /** 记录目录 / 文件指纹。 */
        public Builder fingerprintOf(String name, Path path) throws IOException {
            return fingerprint(name, RunManifest.fingerprintOf(path));
        }

        public Builder randomSeed(long value) {
            this.randomSeed = value;
            return this;
        }

        public Builder notes(String value) {
            this.notes = value;
            return this;
        }

        public RunManifest build() {
            return new RunManifest(
                    runId == null ? edu.zjnu.interview.ai.core.Ids.random() : runId,
                    createdAt == null ? Instant.now().toString() : createdAt,
                    models, config, fingerprints, randomSeed, notes);
        }
    }
}
