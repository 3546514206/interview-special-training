package edu.zjnu.interview.ai.rag.assemble;

import edu.zjnu.interview.ai.core.PromptBlocks;
import edu.zjnu.interview.ai.rag.retrieve.Candidate;
import edu.zjnu.interview.ai.rag.rerank.ScoredCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文组装器测试。
 *
 * <p>组装层是「检索结果」与「模型输入」之间的最后一道闸门，
 * 这里锁定：token 预算硬约束（含裁剪原因记录）、单来源限额、去重、
 * 引用编号闭环（组装格式必须能被 PromptBlocks 解析回来）、lost-in-the-middle 排序。
 */
class ContextAssemblerTest {

    private static ScoredCandidate scored(String chunkId, String docId, String title, String content, double score) {
        Candidate candidate = new Candidate(chunkId, docId, title, content, "src", 0,
                Map.of(Candidate.CHANNEL_DENSE, score), Map.of());
        return new ScoredCandidate(candidate, score, Map.of("dense", score), "dense=" + score);
    }

    @Test
    @DisplayName("引用编号从 1 连续递增，且组装结果能被 PromptBlocks 完整解析回来")
    void citationNumbersRoundTrip() {
        ContextAssembler assembler = new ContextAssembler(ContextAssembler.Config.defaults());
        List<ScoredCandidate> candidates = List.of(
                scored("c1", "doc-a", "准入标准", "准入要求实缴资本 1000 万元。", 0.9),
                scored("c2", "doc-b", "风险规则", "集中度不得超过 30%。", 0.7));

        AssembledContext assembled = assembler.assemble("查询", candidates);

        assertEquals(2, assembled.evidences().size());
        assertEquals(1, assembled.evidences().get(0).index());
        assertEquals(2, assembled.evidences().get(1).index());
        // 闭环：组装文本 → 提取 context 块 → 解析证据 → id 与正文一致
        String contextBlock = PromptBlocks.extractContext(assembled.text());
        List<PromptBlocks.Evidence> parsed = PromptBlocks.parseEvidence(contextBlock);
        assertEquals(assembled.evidences().size(), parsed.size());
        for (int i = 0; i < parsed.size(); i++) {
            assertEquals(assembled.evidences().get(i).id(), parsed.get(i).id());
            assertTrue(parsed.get(i).content().contains(
                    assembled.evidences().get(i).content().substring(0, 8)));
        }
        assertEquals(List.of("doc-a", "doc-b"), assembled.citedDocIds());
    }

    @Test
    @DisplayName("token 预算裁剪：超出预算的记 budget，且短片段仍可被装入（跳过而非中断）")
    void budgetTruncationRecordsReason() {
        // 预算装得下第一条与第三条，装不下第二条：跳过第二条继续尝试（而不是中断）
        String longContent = "这是一条比较长的证据内容，用来占据预算空间，确保第一条就能消耗掉大部分额度。";
        ContextAssembler assembler = new ContextAssembler(new ContextAssembler.Config(60, 5, false, 0.85));
        List<ScoredCandidate> candidates = List.of(
                scored("c1", "doc-a", "标题", longContent, 0.9),
                scored("c2", "doc-b", "标题", longContent, 0.8),
                scored("c3", "doc-c", "标题", "短证据。", 0.6));

        AssembledContext assembled = assembler.assemble("查询", candidates);

        assertTrue(assembled.budgetExceeded(), "应记录 budget 裁剪");
        assertTrue(assembled.evidences().size() >= 2,
                "第三条短证据应被装入（跳过而非中断），实际: " + assembled.evidences().size());
        assertEquals(1, assembled.truncatedByReason().getOrDefault(AssembledContext.REASON_BUDGET, 0));
    }

    @Test
    @DisplayName("单来源限额：同一文档最多入选 maxPerSource 条")
    void perSourceLimit() {
        ContextAssembler assembler = new ContextAssembler(new ContextAssembler.Config(5000, 2, false, 0.85));
        List<ScoredCandidate> candidates = List.of(
                scored("c1", "doc-a", "标题", "内容一", 0.9),
                scored("c2", "doc-a", "标题", "内容二", 0.8),
                scored("c3", "doc-a", "标题", "内容三", 0.7));

        AssembledContext assembled = assembler.assemble("查询", candidates);

        assertEquals(2, assembled.evidences().size());
        assertEquals(1, assembled.truncatedByReason().getOrDefault(AssembledContext.REASON_SOURCE_LIMIT, 0));
        assertEquals(2, assembled.sourceCounts().get("doc-a"));
    }

    @Test
    @DisplayName("近重复去重：内容几乎相同的候选被 duplicate 裁掉")
    void duplicateDetection() {
        ContextAssembler assembler = new ContextAssembler(new ContextAssembler.Config(5000, 5, true, 0.85));
        List<ScoredCandidate> candidates = List.of(
                scored("c1", "doc-a", "标题", "私募资产准入的实缴资本要求是不低于 1000 万元人民币", 0.9),
                scored("c2", "doc-a", "标题", "私募资产准入的实缴资本要求是不低于 1000 万元人民币整", 0.85));

        AssembledContext assembled = assembler.assemble("查询", candidates);

        assertEquals(1, assembled.evidences().size());
        assertEquals(1, assembled.truncatedByReason().getOrDefault(AssembledContext.REASON_DUPLICATE, 0));
    }

    @Test
    @DisplayName("lost-in-the-middle：第 1 名在最前、第 2 名在最后，其余交替填充")
    void attentionOrdering() {
        List<ScoredCandidate> sorted = List.of(
                scored("c1", "d1", "标题", "一号", 0.9),
                scored("c2", "d2", "标题", "二号", 0.8),
                scored("c3", "d3", "标题", "三号", 0.7),
                scored("c4", "d4", "标题", "四号", 0.6));

        List<ScoredCandidate> ordered = ContextAssembler.orderForAttention(sorted);

        // 算法：i=0 取头（c1），i=1 取尾（c4），i=2 取头（c2），i=3 取尾（c3）
        assertEquals("c1", ordered.get(0).chunkId());
        assertEquals("c4", ordered.get(1).chunkId());
        assertEquals("c2", ordered.get(2).chunkId());
        assertEquals("c3", ordered.get(3).chunkId());
    }

    @Test
    @DisplayName("组装文本带 context 标记，token 估算与正文一致")
    void textWrappedAndTokensEstimated() {
        ContextAssembler assembler = new ContextAssembler(ContextAssembler.Config.defaults());
        AssembledContext assembled = assembler.assemble("查询",
                List.of(scored("c1", "doc-a", "准入标准", "实缴资本 1000 万元起。", 0.9)));

        assertTrue(assembled.text().startsWith(PromptBlocks.CONTEXT_OPEN));
        assertTrue(assembled.text().trim().endsWith(PromptBlocks.CONTEXT_CLOSE));
        assertTrue(assembled.estimatedTokens() > 0);
        assertEquals(assembled.estimatedTokens(),
                edu.zjnu.interview.ai.core.TokenCounter.estimate(assembled.text()));
    }

    @Test
    @DisplayName("空候选组装出空上下文而不是异常")
    void emptyCandidatesYieldEmptyContext() {
        ContextAssembler assembler = new ContextAssembler(ContextAssembler.Config.defaults());

        AssembledContext assembled = assembler.assemble("查询", List.of());

        assertTrue(assembled.evidences().isEmpty());
        assertTrue(assembled.citedDocIds().isEmpty());
        assertTrue(assembled.text().contains(PromptBlocks.CONTEXT_OPEN));
    }
}
