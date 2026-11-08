package edu.zjnu.interview.ai.rag.assemble;

import edu.zjnu.interview.ai.core.PromptBlocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组装好的上下文。
 *
 * @param text           最终送进 prompt 的文本（已用 {@code <context>} 包裹）
 * @param evidences      证据列表（引用编号从 1 开始）
 * @param estimatedTokens token 估算值
 * @param truncated      被裁剪掉的候选及原因
 * @param sourceCounts   各来源（文档）入选的片段数
 */
public record AssembledContext(String text,
                               List<PromptBlocks.Evidence> evidences,
                               int estimatedTokens,
                               List<TruncatedItem> truncated,
                               Map<String, Integer> sourceCounts) {

    public AssembledContext {
        evidences = evidences == null ? List.of() : List.copyOf(evidences);
        truncated = truncated == null ? List.of() : List.copyOf(truncated);
        sourceCounts = sourceCounts == null ? Map.of() : Map.copyOf(sourceCounts);
    }

    /**
     * 被裁剪的一条候选。
     *
     * @param chunkId 片段 id
     * @param docId   文档 id
     * @param reason  裁剪原因（budget / source-limit / duplicate）
     * @param score   该候选的分数
     */
    public record TruncatedItem(String chunkId, String docId, String reason, double score) {
    }

    /** 裁剪原因：超出 token 预算。 */
    public static final String REASON_BUDGET = "budget";
    /** 裁剪原因：超过单来源上限。 */
    public static final String REASON_SOURCE_LIMIT = "source-limit";
    /** 裁剪原因：内容重复。 */
    public static final String REASON_DUPLICATE = "duplicate";

    /** 是否因超预算而裁剪过内容。 */
    public boolean budgetExceeded() {
        return truncated.stream().anyMatch(t -> REASON_BUDGET.equals(t.reason()));
    }

    /** 入选的文档 id（按引用编号顺序）。 */
    public List<String> citedDocIds() {
        List<String> ids = new ArrayList<>(evidences.size());
        for (PromptBlocks.Evidence evidence : evidences) {
            ids.add(evidence.id());
        }
        return ids;
    }

    /** 按原因分组的裁剪统计。 */
    public Map<String, Integer> truncatedByReason() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TruncatedItem item : truncated) {
            counts.merge(item.reason(), 1, Integer::sum);
        }
        return counts;
    }
}
