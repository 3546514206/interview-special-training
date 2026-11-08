package edu.zjnu.interview.ai.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * id 工具。
 *
 * <p>实验工程里刻意使用「确定性 id」而不是随机 UUID：
 * 同样的语料 + 同样的配置，跑两次必须产生同样的 point id，
 * 这样向量库里的数据可以被幂等覆盖，评测结果也可复现。
 */
public final class Ids {

    private Ids() {
    }

    /** 短哈希（8 位十六进制），用于 span id / 集合 id。 */
    public static String shortHash(String input) {
        return sha256(input).substring(0, 8);
    }

    /**
     * 确定性 UUID（v5 风格），用于向量库的主键。
     *
     * <p>Qdrant 的 point id 只接受 UUID 或无符号整数，
     * 所以业务 id 要先映射成确定性 UUID——这步做对，upsert 才能幂等。
     */
    public static String deterministicUuid(String namespace, String businessId) {
        String hex = sha256(namespace + "|" + businessId).substring(0, 32);
        // 强制为 UUID v4 形态的版本/变位，保证各向量库都能接受
        String versioned = hex.substring(0, 12) + "4" + hex.substring(13, 16)
                + (Integer.parseInt(hex.substring(16, 17), 16) % 4 + 8)
                + hex.substring(17);
        return versioned.substring(0, 8) + "-" + versioned.substring(8, 12) + "-"
                + versioned.substring(12, 16) + "-" + versioned.substring(16, 20) + "-"
                + versioned.substring(20, 32);
    }

    /** 随机 id（trace id 等无需复现的场景）。 */
    public static String random() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
