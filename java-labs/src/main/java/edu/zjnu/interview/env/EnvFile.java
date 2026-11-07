package edu.zjnu.interview.env;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 定位并解析聚合工程下的 {@code .env} 文件。
 * <p>
 * 仓库是 Maven 聚合工程，各子模块各自持有一份 {@code .env}（当前为 {@code java-labs/.env}），
 * 所以查找顺序是：
 * <ol>
 *   <li>当前工作目录下的 {@code .env}（在子模块目录里运行时命中）</li>
 *   <li>当前工作目录的<b>直接子目录</b>下的 {@code .env}（在聚合工程根目录运行时命中
 *       {@code java-labs/.env}，后续新增子模块同样自动生效）</li>
 *   <li>逐级向上最多 3 级，每级重复上面两步（适配 IDE 从仓库根启动）</li>
 * </ol>
 * 都没找到 → 退化为空表，由调用方回退到系统环境变量 / 默认值。
 * <p>
 * 不引入 dotenv 第三方库，自己写一个简单 parser（15 行就够）。
 *
 * @author 杨海波 (SetsunaYang)
 */
public final class EnvFile {

    private static final String ENV_FILE = ".env";
    private static final int MAX_PARENT_LEVELS = 3;

    private static volatile Map<String, String> cache;

    private EnvFile() {
    }

    /** 取 .env 解析结果；文件不存在或不可读时返回空表（不抛异常）。 */
    public static Map<String, String> values() {
        Map<String, String> local = cache;
        if (local != null) {
            return local;
        }
        synchronized (EnvFile.class) {
            if (cache != null) {
                return cache;
            }
            Map<String, String> parsed = new HashMap<>();
            Path envPath = locate();
            if (envPath != null) {
                try {
                    for (String line : Files.readAllLines(envPath)) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                            continue;
                        }
                        int eq = trimmed.indexOf('=');
                        if (eq < 0) {
                            continue;
                        }
                        String k = trimmed.substring(0, eq).trim();
                        String v = trimmed.substring(eq + 1).trim();
                        if ((v.startsWith("\"") && v.endsWith("\"")) ||
                                (v.startsWith("'") && v.endsWith("'"))) {
                            v = v.substring(1, v.length() - 1);
                        }
                        parsed.put(k, v);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "读取 " + envPath + " 失败: " + e.getMessage()
                                    + "\n请确认 .env 文件存在且可读", e);
                }
            }
            cache = Collections.unmodifiableMap(parsed);
            return cache;
        }
    }

    /** .env 实际所在路径；未找到返回 null。 */
    public static Path locate() {
        Path base = Paths.get("").toAbsolutePath();
        for (int level = 0; level <= MAX_PARENT_LEVELS && base != null; level++) {
            Path direct = base.resolve(ENV_FILE);
            if (Files.isRegularFile(direct)) {
                return direct;
            }
            Path nested = locateInChildren(base);
            if (nested != null) {
                return nested;
            }
            base = base.getParent();
        }
        return null;
    }

    /** 在 base 的直接子目录里找 .env（跳过隐藏目录，如 .git/.idea）。 */
    private static Path locateInChildren(Path base) {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(base)) {
            for (Path child : children) {
                Path name = child.getFileName();
                if (name != null && name.toString().startsWith(".")) {
                    continue;
                }
                if (Files.isDirectory(child)) {
                    Path candidate = child.resolve(ENV_FILE);
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                }
            }
        } catch (IOException ignored) {
            // 目录不可读时静默跳过，继续向上找
        }
        return null;
    }
}
