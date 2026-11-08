package edu.zjnu.interview.ai.core;

/**
 * 向量化端口。向量维度一旦确定，就写进了向量库的集合定义里——
 * 换 embedding 模型必须重建集合，这是 RAG 索引工程里最常见的「维度不匹配」事故来源。
 */
public interface EmbeddingModel {

    String name();

    /** 向量维度。 */
    int dimensions();

    /** 把文本编码成向量（实现需保证 L2 归一化，便于用余弦相似度）。 */
    float[] embed(String text);
}
