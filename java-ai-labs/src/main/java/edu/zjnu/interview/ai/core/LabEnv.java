package edu.zjnu.interview.ai.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地配置加载器（约定优于配置）。
 *
 * <p>取值优先级：<b>模块目录下的 {@code .env} &gt; 进程环境变量 &gt; 代码内默认值</b>。
 * 真实凭据只落在本机 {@code .env}（已被仓库根 {@code .gitignore} 忽略），
 * 仓库里只保留 {@code .env.example} 模板。
 *
 * <p><b>为什么 .env 优先于进程环境变量（与常见 12-factor 约定相反）？</b>
 * 这是一次真实的配置冲突教训：本机 shell 全局导出了 {@code QDRANT_COLLECTION=common_collection}
 * （属于另一个工具的配置），把本模块 `.env` 里自己的集合名劫持了，
 * 实验差点写进别人的集合。模块 `.env` 的语义是「作者为本模块明确挑选的私有配置」，
 * 它应当压过 shell 里属于<b>其它工具</b>的同名变量；
 * `.env` 没写的键仍然回落到进程环境变量，所以 CI / 临时覆盖依然可用。
 *
 * <p>搜索路径：当前工作目录、其上级目录、以及 {@code java-ai-labs/} 子目录，
 * 这样无论从仓库根还是模块目录启动实验，都能读到同一份配置。
 */
public final class LabEnv {

    private static final Map<String, String> FILE_VALUES = loadDotEnv();

    private LabEnv() {
    }

    /** 取配置值；没有则返回默认值。 */
    public static String get(String key, String defaultValue) {
        String fromFile = FILE_VALUES.get(key);
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile;
        }
        String fromProcess = System.getenv(key);
        if (fromProcess != null && !fromProcess.isBlank()) {
            return fromProcess;
        }
        return defaultValue;
    }

    public static String get(String key) {
        return get(key, null);
    }

    public static boolean has(String key) {
        return get(key, null) != null;
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String v = get(key, null);
        return v == null ? defaultValue : Boolean.parseBoolean(v.trim());
    }

    public static int getInt(String key, int defaultValue) {
        String v = get(key, null);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static double getDouble(String key, double defaultValue) {
        String v = get(key, null);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 全部文件级配置（值已脱敏，供日志打印）。 */
    public static Map<String, String> describe() {
        Map<String, String> out = new LinkedHashMap<>();
        FILE_VALUES.forEach((k, v) -> out.put(k, isSensitive(k) ? "***" : v));
        return out;
    }

    private static boolean isSensitive(String key) {
        String upper = key.toUpperCase();
        return upper.contains("KEY") || upper.contains("TOKEN") || upper.contains("SECRET")
                || upper.contains("PASSWORD") || upper.contains("CREDENTIAL");
    }

    private static Map<String, String> loadDotEnv() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Path candidate : dotEnvCandidates()) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(candidate, StandardCharsets.UTF_8)) {
                    parseLine(line, values);
                }
                break;
            } catch (IOException e) {
                // 读不到就当没有，不影响实验启动
            }
        }
        return values;
    }

    private static java.util.List<Path> dotEnvCandidates() {
        java.util.List<Path> candidates = new java.util.ArrayList<>();
        String explicit = System.getProperty("lab.env");
        if (explicit != null && !explicit.isBlank()) {
            candidates.add(Paths.get(explicit));
        }
        Path cwd = Paths.get("").toAbsolutePath();
        candidates.add(cwd.resolve(".env"));
        if (cwd.getParent() != null) {
            candidates.add(cwd.getParent().resolve(".env"));
        }
        candidates.add(cwd.resolve("java-ai-labs").resolve(".env"));
        return candidates;
    }

    private static void parseLine(String rawLine, Map<String, String> values) {
        String line = rawLine.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        if (line.startsWith("export ")) {
            line = line.substring("export ".length()).strip();
        }
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String key = line.substring(0, eq).strip();
        String value = line.substring(eq + 1).strip();
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        values.put(key, value);
    }
}
