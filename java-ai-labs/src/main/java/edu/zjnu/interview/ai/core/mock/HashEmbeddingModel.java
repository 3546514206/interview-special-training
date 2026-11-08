package edu.zjnu.interview.ai.core.mock;

import edu.zjnu.interview.ai.core.EmbeddingModel;
import edu.zjnu.interview.ai.core.Text;

import java.nio.charset.StandardCharsets;

/**
 * 确定性哈希向量模型（离线可用的 embedding 替身）。
 *
 * <p>做法是「词项哈希 + 符号位（signed hashing trick）」：把文本切成词项，
 * 用词项哈希值决定落在哪一维、用另一段哈希决定正负号，最后 L2 归一化。
 * 这是经典的 hashing trick，词袋统计效果接近 TF-IDF。
 *
 * <p><b>它的定位必须说清楚</b>：这不是语义向量，语义泛化能力远弱于 bge-m3 这类真模型。
 * 实验用它是为了让「索引 → 召回 → 重排 → 评测」全链路在离线环境可跑、可回归。
 * 换成真实 embedding 模型时，<b>必须按新维度重建向量库集合</b>——
 * 这正是 RAG 工程里最常见的维度不匹配事故。
 */
public final class HashEmbeddingModel implements EmbeddingModel {

    /** 默认维度。 */
    public static final int DEFAULT_DIMENSIONS = 256;

    private final int dimensions;

    public HashEmbeddingModel() {
        this(DEFAULT_DIMENSIONS);
    }

    public HashEmbeddingModel(int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        this.dimensions = dimensions;
    }

    @Override
    public String name() {
        return "hash-embedding-" + dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimensions];
        for (String token : Text.tokenize(text)) {
            int h = token.hashCode();
            int index = Math.floorMod(h, dimensions);
            float sign = ((h >>> 16) & 1) == 0 ? 1f : -1f;
            vector[index] += sign * weight(token);
        }
        double norm = 0.0;
        for (float v : vector) {
            norm += v * v;
        }
        if (norm > 0) {
            float inv = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= inv;
            }
        }
        return vector;
    }

    /** 词项权重：拉丁词与中文二字组信息量更高，权重更大。 */
    private float weight(String token) {
        if (token.length() >= 3) {
            return 2.0f;
        }
        if (token.length() == 2) {
            return 1.6f;
        }
        return 1.0f;
    }

    /** 便于测试：文本指纹。 */
    public static String fingerprint(String text) {
        return Integer.toHexString(text.getBytes(StandardCharsets.UTF_8).length)
                + "-" + Integer.toHexString(text.hashCode());
    }
}
