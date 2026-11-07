package edu.zjnu.interview.database.env;

import edu.zjnu.interview.env.EnvFile;

import java.nio.file.Path;

/**
 * 读取子模块的 .env 文件,提供 DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD 字段.
 * <p>
 * .env 位于子模块根目录 {@code java-labs/.env}（模板见 {@code java-labs/.env.example}）,
 * 文件定位与解析统一交给 {@link EnvFile},本类只负责"取值语义"。
 * <p>
 * 取值顺序:先 .env 后 system env;都没找到 → {@link #getRequired(String)} 抛清晰报错,
 * 指引用户创建 .env.
 *
 * @author 杨海波 (SetsunaYang)
 */
public final class EnvLoader {

    private EnvLoader() {}

    /** 取值:先 .env 后 system env;都没返回 null. */
    public static String get(String key) {
        String v = EnvFile.values().get(key);
        if (v != null) {
            return v;
        }
        return System.getenv(key);
    }

    /** 取必填值,缺失抛清晰异常. */
    public static String getRequired(String key) {
        String v = get(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException(
                    "缺少必填环境变量: " + key + "\n"
                            + "请在子模块根目录创建 .env 文件 (参考 java-labs/.env.example),\n"
                            + " 或通过 export " + key + "=xxx 设置");
        }
        return v;
    }

    /** .env 实际所在路径(未找到返回 null),用于排查配置读取问题. */
    public static Path envFilePath() {
        return EnvFile.locate();
    }
}
