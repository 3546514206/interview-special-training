package edu.zjnu.interview.ai.rag.retrieve;

import edu.zjnu.interview.ai.core.KnowledgeBase;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 召回候选（一个片段在各条检索通道上的得分）。
 *
 * <p><b>为什么要保留「分通道得分」而不是只留一个总分？</b>
 * 因为重排要做特征加权：dense 相似度、BM25 分数是<b>不同尺度</b>的信号
 * （余弦在 [0,1]、BM25 可以到几十），必须分开保存才能各自归一化后加权。
 * 如果在召回阶段就把它们揉成一个分数，重排层就退化成了「重新排一次序」，失去了特征工程的空间。
 *
 * @param chunkId       片段 id
 * @param docId         所属文档 id（黄金集按文档标注相关，相关性判定以它为准）
 * @param title         文档标题
 * @param content       片段正文
 * @param source        来源
 * @param ordinal       文档内序号
 * @param channelScores 分通道得分（dense / bm25 / fusion）
 * @param metadata      元数据
 */
public record Candidate(String chunkId,
                        String docId,
                        String title,
                        String content,
                        String source,
                        int ordinal,
                        Map<String, Double> channelScores,
                        Map<String, Object> metadata) {

    public static final String CHANNEL_DENSE = "dense";
    public static final String CHANNEL_BM25 = "bm25";
    public static final String CHANNEL_FUSION = "fusion";

    public Candidate {
        channelScores = channelScores == null ? Map.of() : Map.copyOf(channelScores);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 从 core 的 {@link KnowledgeBase.Hit} 构造候选。
     *
     * <p>core 的 Hit 把 docId / source / ordinal 放在 metadata 里，
     * 这里还原成强类型字段，避免下游到处做字符串取值与类型转换。
     */
    public static Candidate fromHit(String channel, double score, KnowledgeBase.Hit hit) {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put(channel, score);
        Map<String, Object> metadata = hit.metadata();
        Object docId = metadata.get("docId");
        Object source = metadata.get("source");
        Object ordinal = metadata.get("ordinal");
        return new Candidate(hit.id(),
                docId == null ? hit.id() : String.valueOf(docId),
                hit.title(),
                hit.content(),
                source == null ? "" : String.valueOf(source),
                ordinal instanceof Number n ? n.intValue() : 0,
                scores,
                metadata);
    }

    /** dense 相似度（未召回该通道时为 0）。 */
    public double denseScore() {
        return channelScores.getOrDefault(CHANNEL_DENSE, 0.0);
    }

    /** BM25 分数（未召回该通道时为 0）。 */
    public double bm25Score() {
        return channelScores.getOrDefault(CHANNEL_BM25, 0.0);
    }

    /** 融合分数（RRF 之后）。 */
    public double fusionScore() {
        return channelScores.getOrDefault(CHANNEL_FUSION, 0.0);
    }

    /** 当前排序用的分数：有融合分用融合分，否则取各通道最大值。 */
    public double score() {
        if (channelScores.containsKey(CHANNEL_FUSION)) {
            return fusionScore();
        }
        return channelScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
    }

    /** 浅拷贝（替换分通道得分）。 */
    public Candidate withScores(Map<String, Double> scores) {
        return new Candidate(chunkId, docId, title, content, source, ordinal, scores, metadata);
    }
}
