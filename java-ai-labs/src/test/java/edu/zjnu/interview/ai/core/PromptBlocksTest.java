package edu.zjnu.interview.ai.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文标记与证据解析的约定测试。
 *
 * <p>这部分是 RAG 组装层与模型层之间的契约：组装器按固定格式产出证据，
 * 模型（以及抽取式基线）按同一格式解析。任何一方改了格式，
 * 引用编号就会错位——这类错误在效果上表现为「引用了不相关的段落」，
 * 所以用测试锁住。
 */
class PromptBlocksTest {

    @Test
    @DisplayName("上下文标记可以被提取")
    void extractContextBlock() {
        String prompt = "请回答下面的问题。\n" + PromptBlocks.wrapContext("证据正文");

        String context = PromptBlocks.extractContext(prompt);

        assertNotNull(context);
        assertEquals("证据正文", context);
    }

    @Test
    @DisplayName("没有标记时返回 null")
    void noBlockReturnsNull() {
        assertNull(PromptBlocks.extractContext("一段没有标记的文本"));
    }

    @Test
    @DisplayName("证据按 [序号] (id) 标题 的格式解析，正文归入所属证据")
    void parseEvidence() {
        String context = """
                [1] (doc-a) 私募准入标准
                私募资产准入要求实缴金额不低于 1000 万元。
                需提供近三年审计报告。

                [2] (doc-b) 风险识别规则
                单一私募基金集中度不得超过 30%。
                """;

        List<PromptBlocks.Evidence> evidences = PromptBlocks.parseEvidence(context);

        assertEquals(2, evidences.size());
        assertEquals(1, evidences.get(0).index());
        assertEquals("doc-a", evidences.get(0).id());
        assertEquals("私募准入标准", evidences.get(0).title());
        assertTrue(evidences.get(0).content().contains("1000 万元"));
        assertTrue(evidences.get(0).content().contains("审计报告"));
        assertEquals(2, evidences.get(1).index());
        assertEquals("doc-b", evidences.get(1).id());
    }

    @Test
    @DisplayName("脏数据不应让整体解析失败")
    void malformedEvidenceIsTolerated() {
        String context = """
                [x] (doc-c) 序号非法
                正文
                """;

        List<PromptBlocks.Evidence> evidences = PromptBlocks.parseEvidence(context);

        assertEquals(1, evidences.size());
        assertEquals("doc-c", evidences.get(0).id());
    }

    @Test
    @DisplayName("`[1] (id) 标题` 与 `[1](id) 标题` 两种写法都能解析")
    void bothHeaderSpacingStylesParse() {
        String spaced = "[1] (doc-a) 标题\n正文A";
        String compact = "[1](doc-a) 标题\n正文A";

        assertEquals(1, PromptBlocks.parseEvidence(spaced).size());
        assertEquals(1, PromptBlocks.parseEvidence(compact).size());
        assertEquals("doc-a", PromptBlocks.parseEvidence(spaced).get(0).id());
        assertEquals("doc-a", PromptBlocks.parseEvidence(compact).get(0).id());
    }

    @Test
    @DisplayName("正文里的方括号行不应被误判成证据头")
    void bodyBracketLineIsNotHeader() {
        String context = """
                [1] (doc-a) 标题
                注意：[注] 这一行只是正文
                """;

        List<PromptBlocks.Evidence> evidences = PromptBlocks.parseEvidence(context);

        assertEquals(1, evidences.size());
        assertTrue(evidences.get(0).content().contains("[注]"));
    }
}
