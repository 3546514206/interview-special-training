package edu.zjnu.interview.ai.rag.eval;

import java.util.List;
import java.util.Locale;

/**
 * RAG 评测指标汇总。
 *
 * <p><b>口径统一说明</b>（所有检索类指标都是<b>文档粒度</b>）：
 * <ul>
 *   <li>一次检索返回的是片段，但相关性标注在文档上，因此先把 top-k 片段按出现顺序
 *       去重成「文档序列」，再在文档序列上计算指标；</li>
 *   <li>不可回答用例不参与召回类指标的平均（它们的正确行为是<b>不召回并拒答</b>），
 *       单独用拒答率衡量。</li>
 * </ul>
 *
 * @param recallAtK        召回率：命中的期望文档数 / 期望文档总数
 * @param precisionAtK     精确率：命中的期望文档数 / k
 * @param mrrAt10          平均倒数排名（首个正确文档排名的倒数）
 * @param ndcgAt10         归一化折损累计增益
 * @param hitRate          命中率：至少命中一个期望文档的问题占比
 * @param citationAccuracy 引用正确率：正确引用数 / 总引用数（按文档粒度判定）
 * @param citationRate     引用覆盖率：产生了至少一个引用的问题占比
 * @param refusalRate      拒答率（不可答问题中正确拒答的占比）
 * @param falseRefusalRate 误拒答率（可答问题中错误拒答的占比）
 * @param avgContextTokens 平均上下文 token
 * @param avgLatencyMillis 平均耗时
 * @param caseCount        用例总数
 * @param answerableCount  可回答用例数
 * @param unanswerableCount 不可回答用例数
 * @param topK             评测使用的 k
 * @param groups           分组指标（易例 / 难例 / 不可答）
 * @param cases            逐条明细
 */
public record RagMetrics(double recallAtK,
                         double precisionAtK,
                         double mrrAt10,
                         double ndcgAt10,
                         double hitRate,
                         double citationAccuracy,
                         double citationRate,
                         double refusalRate,
                         double falseRefusalRate,
                         double avgContextTokens,
                         double avgLatencyMillis,
                         int caseCount,
                         int answerableCount,
                         int unanswerableCount,
                         int topK,
                         List<GroupMetrics> groups,
                         List<CaseResult> cases) {

    public RagMetrics {
        groups = groups == null ? List.of() : List.copyOf(groups);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    /**
     * 分组指标。
     *
     * <p><b>为什么要分组报</b>：只报一个总分会掩盖「检索链路到底在哪一档问题上会失手」。
     * 易例（词面高度重合）度量的是<b>链路是否正确</b>；难例（低词面重叠、跨文档组合）
     * 度量的是<b>语义泛化能力</b>；不可答用例度量的是<b>拒答纪律</b>。
     * 三者混在一起求平均，一个满分一个零分，总分看起来仍然不错——这就是「自己给自己放水」的温床。
     *
     * @param group            组名（易例 / 难例 / 不可答）
     * @param caseCount        用例数
     * @param answerableCount  可回答用例数
     * @param unanswerableCount 不可回答用例数
     * @param recallAtK        召回率
     * @param precisionAtK     精确率
     * @param mrrAt10          平均倒数排名
     * @param ndcgAt10         nDCG
     * @param hitRate          命中率
     * @param citationAccuracy 引用正确率
     * @param refusalRate      拒答率（不可答用例）
     * @param falseRefusalRate 误拒答率（可答用例）
     * @param avgTopScore      平均最高分
     * @param minTopScore      最低最高分（可答用例，用于看拒答阈值的安全边界）
     * @param maxTopScore      最高最高分（不可答用例，用于看拒答阈值的安全边界）
     */
    public record GroupMetrics(String group,
                               int caseCount,
                               int answerableCount,
                               int unanswerableCount,
                               double recallAtK,
                               double precisionAtK,
                               double mrrAt10,
                               double ndcgAt10,
                               double hitRate,
                               double citationAccuracy,
                               double refusalRate,
                               double falseRefusalRate,
                               double avgTopScore,
                               double minTopScore,
                               double maxTopScore) {

        /** 打印成一行。「分界」列给出该组的阈值边界：可答组取最低分，不可答组取最高分。 */
        public String toRow() {
            double boundary = answerableCount > 0 ? minTopScore : maxTopScore;
            return String.format(Locale.ROOT,
                    "%-8s %-6d %-8.3f %-8.3f %-8.3f %-8.3f %-8.3f %-8.3f %-8.3f %-9.3f %-9.3f %-9.3f",
                    group, caseCount, recallAtK, precisionAtK, mrrAt10, ndcgAt10, hitRate,
                    citationAccuracy, refusalRate, falseRefusalRate, avgTopScore, boundary);
        }

        /** 表头。 */
        public static String header() {
            return String.format(Locale.ROOT, "%-8s %-6s %-8s %-8s %-8s %-8s %-8s %-8s %-8s %-9s %-9s %-9s",
                    "分组", "用例", "Recall", "Prec", "MRR@10", "nDCG", "hit", "引用", "拒答", "误拒答", "均分", "分界");
        }
    }

    /**
     * 单条用例的明细。
     *
     * @param id                用例 id
     * @param question          问题
     * @param type              用例类型
     * @param difficulty        难度（EASY / HARD）
     * @param answerable        是否可回答
     * @param retrievedDocIds   top-k 内检索到的文档（去重、保持顺序）
     * @param relevantCount     命中的期望文档数
     * @param recall            该用例的召回率
     * @param reciprocalRank    该用例的倒数排名（无命中为 0）
     * @param ndcg              该用例的 nDCG
     * @param hit               是否命中
     * @param citedIndexes      回答里出现的引用编号
     * @param citedDocIds       引用指向的文档 id
     * @param correctCitations  正确的引用数
     * @param citationAccuracy  该用例的引用正确率
     * @param refused           是否拒答
     * @param contextTokens     上下文 token
     * @param latencyMillis     耗时
     * @param topScore          重排最高分
     */
    public record CaseResult(String id,
                             String question,
                             String type,
                             String difficulty,
                             boolean answerable,
                             List<String> retrievedDocIds,
                             int relevantCount,
                             double recall,
                             double reciprocalRank,
                             double ndcg,
                             boolean hit,
                             List<Integer> citedIndexes,
                             List<String> citedDocIds,
                             int correctCitations,
                             double citationAccuracy,
                             boolean refused,
                             int contextTokens,
                             long latencyMillis,
                             double topScore) {

        public CaseResult {
            retrievedDocIds = retrievedDocIds == null ? List.of() : List.copyOf(retrievedDocIds);
            citedIndexes = citedIndexes == null ? List.of() : List.copyOf(citedIndexes);
            citedDocIds = citedDocIds == null ? List.of() : List.copyOf(citedDocIds);
        }
    }

    /** 打印成一行摘要。 */
    public String summary() {
        return String.format(Locale.ROOT,
                "Recall@%d=%.3f Precision@%d=%.3f MRR@10=%.3f nDCG@10=%.3f hit=%.3f 引用正确率=%.3f 拒答率=%.3f 上下文token=%.1f 耗时=%.1fms",
                topK, recallAtK, topK, precisionAtK, mrrAt10, ndcgAt10, hitRate,
                citationAccuracy, refusalRate, avgContextTokens, avgLatencyMillis);
    }
}
