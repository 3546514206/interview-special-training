package edu.zjnu.interview.kafka;

import edu.zjnu.interview.env.EnvFile;

/**
 * 示例配置读取：环境变量优先，其次子模块根目录的 .env，最后用默认值。
 *
 * .env 位于 java-labs/.env（已在 .gitignore 中忽略），只放本机实验环境地址，不要提交。
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
        String fromFile = EnvFile.values().get(key);
        if (fromFile != null && !fromFile.trim().isEmpty()) {
            return fromFile.trim();
        }
        return defaultValue;
    }
}
