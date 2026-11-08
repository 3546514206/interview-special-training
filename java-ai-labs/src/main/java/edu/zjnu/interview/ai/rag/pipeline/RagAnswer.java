package edu.zjnu.interview.ai.rag.pipeline;

import edu.zjnu.interview.ai.rag.assemble.AssembledContext;
import edu.zjnu.interview.ai.rag.rerank.ScoredCandidate;

import java.util.List;

/**
 * 一次问答的完整结果（端到端可观测的最小单元）。
 *
 * @param question         用户问题
 * @param queries          实际用于检索的查询（含改写）
 * @param reranked         重排后的候选（带分数拆解）
 * @param context          组装好的上下文
 * @param response         最终回答文本
 * @param refused          是否拒答（检索到的资料不足以回答）
 * @param elapsedMillis    端到端耗时
 */
public record RagAnswer(String question,
                         List<String> queries,
                         List<ScoredCandidate> reranked,
                         AssembledContext context,
                         String response,
                         boolean refused,
                         long elapsedMillis) {

    public RagAnswer {
        queries = queries == null ? List.of() : List.copyOf(queries);
        reranked = reranked == null ? List.of() : List.copyOf(reranked);
    }

    /** 重排后最高分（用于阈值判断与调试）。 */
    public double topScore() {
        return reranked.stream().mapToDouble(ScoredCandidate::score).max().orElse(0.0);
    }

    /** 引用到的文档 id（按引用编号顺序，去重）。 */
    public List<String> citedDocIds() {
        return context.citedDocIds();
    }
}
