package edu.zjnu.interview.database.env;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 读取项目根 .env 文件,提供 DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD 字段.
 * <p>
 * 不引入 dotenv 第三方库,自己写一个简单 parser (15 行就够).
 * <p>
 * 查找顺序:
 *   1. 当前工作目录的 .env
 *   2. 向上 3 级目录找 .env (适配 IDE 从 project root 启动)
 *   3. 都没找到 → 回退到系统环境变量
 * <p>
 * 找不到关键 key 时 {@link #getRequired(String)} 会抛清晰报错,指引用户创建 .env.
 *
 * @author 杨海波 (SetsunaYang)
 */
public final class EnvLoader {

    private static final String ENV_FILE = ".env";
    private static volatile Map<String, String> cache;

    private EnvLoader() {}

    private static Map<String, String> load() {
        if (cache != null) return cache;
        synchronized (EnvLoader.class) {
            if (cache != null) return cache;
            Map<String, String> m = new HashMap<>();

            Path envPath = findEnvFile();
            if (envPath != null) {
                try {
                    for (String line : Files.readAllLines(envPath)) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                        int eq = trimmed.indexOf('=');
                        if (eq < 0) continue;
                        String k = trimmed.substring(0, eq).trim();
                        String v = trimmed.substring(eq + 1).trim();
                        if ((v.startsWith("\"") && v.endsWith("\"")) ||
                            (v.startsWith("'")  && v.endsWith("'"))) {
                            v = v.substring(1, v.length() - 1);
                        }
                        m.put(k, v);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "读取 " + envPath + " 失败: " + e.getMessage()
                                    + "\n请确认 .env 文件存在且可读", e);
                }
            }
            cache = m;
            return cache;
        }
    }

    private static Path findEnvFile() {
        Path p = Paths.get(ENV_FILE);
        if (Files.exists(p)) return p.toAbsolutePath();
        // 向上 3 级找,适配 IDE / mvn 多 cwd
        Path cur = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 3; i++) {
            cur = cur.getParent();
            if (cur == null) break;
            Path candidate = cur.resolve(ENV_FILE);
            if (Files.exists(candidate)) return candidate;
        }
        return null;
    }

    /** 取值:先 .env 后 system env;都没返回 null. */
    public static String get(String key) {
        String v = load().get(key);
        if (v != null) return v;
        return System.getenv(key);
    }

    /** 取必填值,缺失抛清晰异常. */
    public static String getRequired(String key) {
        String v = get(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException(
                    "缺少必填环境变量: " + key + "\n"
                            + "请在项目根创建 .env 文件 (参考 .env.example),"
                            + " 或通过 export " + key + "=xxx 设置");
        }
        return v;
    }
}