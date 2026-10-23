package edu.zjnu.interview.kafka;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 示例配置读取：环境变量优先，其次仓库根目录的 .env，最后用默认值。
 *
 * .env 已在 .gitignore 中忽略，只放本机实验环境地址，不要提交。
 * 格式为每行 KEY=VALUE，忽略空行与 # 开头的注释。
 */
final class KafkaDemoEnv {

    private KafkaDemoEnv() {
    }

    static String get(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value != null && !value.trim().isEmpty()) {
            return value;
        }

        Path envFile = Paths.get(".env");
        if (Files.isRegularFile(envFile)) {
            try {
                List<String> lines = Files.readAllLines(envFile);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith(key + "=")) {
                        return trimmed.substring(key.length() + 1).trim();
                    }
                }
            } catch (IOException e) {
                System.err.println("读取 .env 失败: " + e.getMessage());
            }
        }
        return defaultValue;
    }
}
