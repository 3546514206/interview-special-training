package edu.zjnu.interview.ai.rag.eval;

import edu.zjnu.interview.ai.core.PromptBlocks;
import edu.zjnu.interview.ai.rag.pipeline.RagAnswer;
import edu.zjnu.interview.ai.rag.pipeline.RagPipeline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 评测器。
 *
 * <p><b>指标定义与口径</b>（全部文档粒度）：
 * <ul>
 *   <li><b>Recall@k</b> = top-k 命中的期望文档数 / 期望文档总数。多跳问题需要两个文档都命中才算满召回，
 *       这正是它比 hit-rate 更严格的地方；</li>
 *   <li><b>Precision@k</b> = top-k 命中的期望文档数 / k。分母固定为 k（不是实际返回条数），
 *       否则「只返回 1 条且正好命中」会得到 1.0 的荒谬精确率；</li>
 *   <li><b>MRR@10</b> = 首个正确文档排名的倒数（文档粒度、去重后序列）；</li>
 *   <li><b>nDCG@10</b> = DCG/IDCG，二值相关性。IDCG 按 min(期望文档数, 10) 计算，
 *       因此多跳问题的满分需要两个文档都排进前列；</li>
 *   <li><b>hit-rate</b> = 至少命中一个期望文档的问题占比；</li>
 *   <li><b>引用正确率</b> = 正确引用数 / 总引用数。引用编号 → 证据 → 文档 id，
 *       落在期望文档集合里才算正确；没有产生引用的用例不进入这个比率的分母
 *       （否则「一句话都不引用」会被算成 0 分，但它其实是另一个问题：引用覆盖率）；</li>
 *   <li><b>拒答率</b> = 不可答问题中被正确拒答的比例；<b>误拒答率</b> = 可答问题中被错误拒答的比例。</li>
 * </ul>
 */
public final class RagEvaluator {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)\\]");

    private final int topK;

    public RagEvaluator(int topK) {
        this.topK = topK;
    }

    /**
     * 评测一条流水线。
     *
     * @param pipeline 流水线
     * @param cases    黄金集
     * @return 指标汇总
     */
    public RagMetrics evaluate(RagPipeline pipeline, List<GoldenCase> cases) {
        List<RagMetrics.CaseResult> results = new ArrayList<>();
        double recallSum = 0.0;
        double precisionSum = 0.0;
        double mrrSum = 0.0;
        double ndcgSum = 0.0;
        int hits = 0;
        int answerable = 0;
        int unanswerable = 0;
        int correctCitations = 0;
        int totalCitations = 0;
        int citedCases = 0;
        int refusedUnanswerable = 0;
        int refusedAnswerable = 0;
        double contextTokenSum = 0.0;
        double latencySum = 0.0;

        for (GoldenCase goldenCase : cases) {
            RagAnswer answer = pipeline.answer(goldenCase.question());
            List<String> retrieved = distinctDocIds(answer, topK);
            Set<String> expected = new LinkedHashSet<>(goldenCase.expectedDocIds());
            int relevantCount = 0;
            for (String docId : retrieved) {
                if (expected.contains(docId)) {
                    relevantCount++;
                }
            }
            double recall = expected.isEmpty() ? 0.0 : (double) relevantCount / expected.size();
            double precision = (double) relevantCount / topK;
            double reciprocalRank = reciprocalRank(retrieved, expected);
            double ndcg = ndcg(retrieved, expected, 10);
            boolean hit = relevantCount > 0;

            List<Integer> citedIndexes = parseCitations(answer.response());
            List<String> citedDocIds = resolveCitedDocIds(answer, citedIndexes);
            int caseCorrect = 0;
            for (String docId : citedDocIds) {
                if (expected.contains(docId)) {
                    caseCorrect++;
                }
            }
            double caseCitationAccuracy = citedDocIds.isEmpty() ? 0.0 : (double) caseCorrect / citedDocIds.size();

            boolean refused = answer.refused();
            if (goldenCase.answerable()) {
                answerable++;
                recallSum += recall;
                precisionSum += precision;
                mrrSum += reciprocalRank;
                ndcgSum += ndcg;
                if (hit) {
                    hits++;
                }
                correctCitations += caseCorrect;
                totalCitations += citedDocIds.size();
                if (!citedDocIds.isEmpty()) {
                    citedCases++;
                }
                if (refused) {
                    refusedAnswerable++;
                }
            } else {
                unanswerable++;
                if (refused) {
                    refusedUnanswerable++;
                }
            }
            contextTokenSum += answer.context().estimatedTokens();
            latencySum += answer.elapsedMillis();

            results.add(new RagMetrics.CaseResult(goldenCase.id(), goldenCase.question(), goldenCase.type(),
                    goldenCase.difficulty(), goldenCase.answerable(), retrieved, relevantCount, recall,
                    reciprocalRank, ndcg, hit, citedIndexes, citedDocIds, caseCorrect, caseCitationAccuracy,
                    refused, answer.context().estimatedTokens(), answer.elapsedMillis(), answer.topScore()));
        }

        int total = cases.size();
        return new RagMetrics(
                answerable == 0 ? 0.0 : recallSum / answerable,
                answerable == 0 ? 0.0 : precisionSum / answerable,
                answerable == 0 ? 0.0 : mrrSum / answerable,
                answerable == 0 ? 0.0 : ndcgSum / answerable,
                answerable == 0 ? 0.0 : (double) hits / answerable,
                totalCitations == 0 ? 0.0 : (double) correctCitations / totalCitations,
                answerable == 0 ? 0.0 : (double) citedCases / answerable,
                unanswerable == 0 ? 0.0 : (double) refusedUnanswerable / unanswerable,
                answerable == 0 ? 0.0 : (double) refusedAnswerable / answerable,
                total == 0 ? 0.0 : contextTokenSum / total,
                total == 0 ? 0.0 : latencySum / total,
                total, answerable, unanswerable, topK, groupMetrics(results, topK), results);
    }

    /**
     * 分组指标：易例 / 难例 / 不可答。
     *
     * <p>分组的意义在于<b>暴露链路在哪一档失手</b>：
     * 易例满分、难例掉分，说明问题出在语义泛化而不是链路正确性；
     * 不可答组拒答率低，说明问题出在拒答纪律而不是检索质量。
     */
    static List<RagMetrics.GroupMetrics> groupMetrics(List<RagMetrics.CaseResult> results, int topK) {
        List<RagMetrics.CaseResult> easy = new ArrayList<>();
        List<RagMetrics.CaseResult> hard = new ArrayList<>();
        List<RagMetrics.CaseResult> unanswerable = new ArrayList<>();
        for (RagMetrics.CaseResult result : results) {
            if (!result.answerable()) {
                unanswerable.add(result);
            } else if (GoldenCase.DIFFICULTY_HARD.equals(result.difficulty())) {
                hard.add(result);
            } else {
                easy.add(result);
            }
        }
        List<RagMetrics.GroupMetrics> groups = new ArrayList<>(3);
        groups.add(aggregate("易例", easy, topK));
        groups.add(aggregate("难例", hard, topK));
        groups.add(aggregate("不可答", unanswerable, topK));
        return groups;
    }

    /** 汇总一组用例的指标。 */
    static RagMetrics.GroupMetrics aggregate(String group, List<RagMetrics.CaseResult> results, int topK) {
        if (results.isEmpty()) {
            return new RagMetrics.GroupMetrics(group, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        int answerable = 0;
        int unanswerable = 0;
        int hits = 0;
        double recallSum = 0.0;
        double precisionSum = 0.0;
        double mrrSum = 0.0;
        double ndcgSum = 0.0;
        double topScoreSum = 0.0;
        int correctCitations = 0;
        int totalCitations = 0;
        int refused = 0;
        double minTop = Double.MAX_VALUE;
        double maxTop = -Double.MAX_VALUE;
        for (RagMetrics.CaseResult result : results) {
            topScoreSum += result.topScore();
            minTop = Math.min(minTop, result.topScore());
            maxTop = Math.max(maxTop, result.topScore());
            if (result.refused()) {
                refused++;
            }
            if (!result.answerable()) {
                unanswerable++;
                continue;
            }
            answerable++;
            recallSum += result.recall();
            precisionSum += (double) result.relevantCount() / Math.max(1, topK);
            mrrSum += result.reciprocalRank();
            ndcgSum += result.ndcg();
            if (result.hit()) {
                hits++;
            }
            correctCitations += result.correctCitations();
            totalCitations += result.citedDocIds().size();
        }
        return new RagMetrics.GroupMetrics(group, results.size(), answerable, unanswerable,
                answerable == 0 ? 0.0 : recallSum / answerable,
                answerable == 0 ? 0.0 : precisionSum / answerable,
                answerable == 0 ? 0.0 : mrrSum / answerable,
                answerable == 0 ? 0.0 : ndcgSum / answerable,
                answerable == 0 ? 0.0 : (double) hits / answerable,
                totalCitations == 0 ? 0.0 : (double) correctCitations / totalCitations,
                unanswerable == 0 ? 0.0 : (double) refused / unanswerable,
                answerable == 0 ? 0.0 : (double) refused / answerable,
                topScoreSum / results.size(),
                minTop == Double.MAX_VALUE ? 0.0 : minTop,
                maxTop == -Double.MAX_VALUE ? 0.0 : maxTop);
    }

    /** top-k 片段去重成文档序列（保持排名顺序）。 */
    static List<String> distinctDocIds(RagAnswer answer, int topK) {
        List<String> docs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int count = 0;
        for (var candidate : answer.reranked()) {
            if (count++ >= topK) {
                break;
            }
            if (seen.add(candidate.docId())) {
                docs.add(candidate.docId());
            }
        }
        return docs;
    }

    /** 首个正确文档排名的倒数。 */
    static double reciprocalRank(List<String> retrieved, Set<String> expected) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (expected.contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /** nDCG@10（二值相关性，文档粒度）。 */
    static double ndcg(List<String> retrieved, Set<String> expected, int k) {
        double dcg = 0.0;
        int limit = Math.min(retrieved.size(), k);
        for (int i = 0; i < limit; i++) {
            if (expected.contains(retrieved.get(i))) {
                // 位置从 1 开始计数：第 1 条的折损因子是 log2(2)=1
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        int ideal = Math.min(expected.size(), k);
        double idcg = 0.0;
        for (int i = 0; i < ideal; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg <= 0 ? 0.0 : dcg / idcg;
    }

    /** 解析回答中的引用编号。 */
    static List<Integer> parseCitations(String response) {
        List<Integer> indexes = new ArrayList<>();
        if (response == null) {
            return indexes;
        }
        Matcher matcher = CITATION.matcher(response);
        while (matcher.find()) {
            indexes.add(Integer.parseInt(matcher.group(1)));
        }
        return indexes;
    }

    /** 引用编号 → 证据文档 id。 */
    static List<String> resolveCitedDocIds(RagAnswer answer, List<Integer> indexes) {
        List<String> docIds = new ArrayList<>();
        List<PromptBlocks.Evidence> evidences = answer.context().evidences();
        for (int index : indexes) {
            if (index >= 1 && index <= evidences.size()) {
                docIds.add(evidences.get(index - 1).id());
            }
        }
        return docIds;
    }
}
