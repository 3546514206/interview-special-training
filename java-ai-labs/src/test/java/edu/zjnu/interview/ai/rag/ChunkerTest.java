package edu.zjnu.interview.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切分器行为测试。
 *
 * <p>切分质量直接决定召回上限：答案被劈成两半的片段，检索到一半也答不出问题。
 * 这里锁定三条硬保证：片段不超上限、相邻片段有重叠、短片段向前合并而不是丢弃。
 */
class ChunkerTest {

    private static Document doc(String content) {
        return new Document("demo-doc", "演示文档", content, "corpus/demo.md",
                Map.of("category", "测试"));
    }

    @Test
    @DisplayName("长文档按段落切分，每个片段不超过 chunkSize")
    void longDocSplitsByParagraph() {
        String paragraph = "私募资产接入必须完成适当性评估。"
                + "机构投资者需要提供营业执照与审计报告。"
                + "个人投资者需要完成风险测评问卷。";
        // 两段共约 76 字，chunkSize=50 强制切开
        String content = paragraph + "\n\n" + paragraph.replace("适当性评估", "准入审核");
        Chunker chunker = new Chunker(new Chunker.Config(50, 10, 0, Chunker.DEFAULT_SEPARATORS));

        List<Chunk> chunks = chunker.chunk(doc(content));

        assertTrue(chunks.size() >= 2, "长文档应被切开，实际: " + chunks.size());
        for (Chunk chunk : chunks) {
            assertTrue(chunk.content().length() <= 50 + 1,
                    "片段不得超上限，实际 " + chunk.content().length() + ": " + chunk.content());
        }
    }

    @Test
    @DisplayName("相邻片段保留 overlap 尾巴，被劈开的事实至少一侧完整")
    void adjacentChunksShareOverlap() {
        // 一整段没有段落分隔，用句子边界切；句子长度 > chunkSize 触发句内再切
        StringBuilder longSentence = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            longSentence.append("准入材料第").append(i).append("项必须齐全，");
        }
        Chunker chunker = new Chunker(new Chunker.Config(60, 15, 0, Chunker.DEFAULT_SEPARATORS));

        List<Chunk> chunks = chunker.chunk(doc(longSentence.toString()));

        assertTrue(chunks.size() >= 2);
        String first = chunks.get(0).content();
        String second = chunks.get(1).content();
        String tail = first.substring(Math.max(0, first.length() - 15));
        assertTrue(second.startsWith(tail) || second.contains(tail.substring(3)),
                "第二片应包含第一片的尾部重叠。\n第一片尾: " + tail + "\n第二片头: "
                        + second.substring(0, Math.min(30, second.length())));
    }

    @Test
    @DisplayName("短片段向前合并，不丢弃事实")
    void shortChunksAreMergedNotDropped() {
        String content = "准入需要审计报告。\n\n风险测评每年一次。\n\n集中度上限三成。";
        Chunker chunker = new Chunker(new Chunker.Config(400, 80, 120, Chunker.DEFAULT_SEPARATORS));

        List<Chunk> chunks = chunker.chunk(doc(content));

        assertEquals(1, chunks.size(), "短句应合并为一个片段，实际: "
                + chunks.stream().map(Chunk::content).toList());
        assertTrue(chunks.get(0).content().contains("审计报告"));
        assertTrue(chunks.get(0).content().contains("集中度上限三成"));
    }

    @Test
    @DisplayName("片段 id 确定性：同样输入切两次得到同样的 id（向量库幂等覆盖的前提）")
    void chunkIdsAreDeterministic() {
        Document document = doc("第一段内容，用于验证幂等。\n\n第二段内容，同样用于验证。");
        Chunker chunker = new Chunker();

        List<Chunk> first = chunker.chunk(document);
        List<Chunk> second = chunker.chunk(document);

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).id(), second.get(i).id());
        }
    }

    @Test
    @DisplayName("片段携带 docId / title / source / ordinal 元数据，供过滤与回显")
    void chunkCarriesMetadata() {
        List<Chunk> chunks = new Chunker().chunk(doc("第一段。\n\n第二段。"));

        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            assertEquals("demo-doc", chunk.docId());
            assertEquals("演示文档", chunk.title());
            Map<String, Object> metadata = chunk.metadata();
            assertEquals("demo-doc", String.valueOf(metadata.get("docId")));
            assertEquals("测试", String.valueOf(metadata.get("category")));
            assertEquals(i, ((Number) metadata.get("ordinal")).intValue());
        }
        // 片段内容按顺序承载原文
        assertTrue(chunks.get(0).content().contains("第一段"));
        assertTrue(chunks.get(chunks.size() - 1).content().contains("第二段"));
    }

    @Test
    @DisplayName("非法配置在构造期就失败，而不是运行到一半才炸")
    void invalidConfigRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Chunker.Config(10, 20, 0, null),
                "overlap >= chunkSize 必须被拒绝");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Chunker.Config(0, 0, 0, null),
                "chunkSize <= 0 必须被拒绝");
    }
}
