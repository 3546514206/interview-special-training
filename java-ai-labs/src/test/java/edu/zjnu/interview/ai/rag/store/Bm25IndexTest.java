package edu.zjnu.interview.ai.rag.store;

import edu.zjnu.interview.ai.rag.Document;
import edu.zjnu.interview.ai.rag.Chunk;
import edu.zjnu.interview.ai.rag.Chunker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BM25 索引行为测试。
 *
 * <p>重点锁定三件事：精确词命中的排序正确性、payload 过滤先于打分生效、
 * 过滤子集上的 df 统计（而不是全量语料的 df）。
 */
class Bm25IndexTest {

    private final Bm25Index index = new Bm25Index();

    private void seed() {
        index.add("c1", "doc-asset", "私募准入标准",
                "私募资产准入要求实缴资本不低于 1000 万元。", Map.of("category", "准入"));
        index.add("c2", "doc-risk", "风险识别规则",
                "单一私募基金集中度不得超过 30%。", Map.of("category", "风险"));
        index.add("c3", "doc-advisory", "投顾流程",
                "投顾服务前必须完成适当性评估。", Map.of("category", "服务"));
    }

    @Test
    @DisplayName("精确词命中排在最前：BM25 对专有名词与数字不可替代")
    void exactTermRanksFirst() {
        seed();

        List<Bm25Index.Scored> hits = index.search("实缴资本 1000 万元", 3);

        assertEquals("doc-asset", hits.get(0).docId());
        assertTrue(hits.get(0).score() > 0.0);
    }

    @Test
    @DisplayName("payload 过滤生效：过滤后的结果只包含命中文档")
    void payloadFilterNarrowsResults() {
        seed();

        List<Bm25Index.Scored> hits = index.search("私募", 10, Map.of("category", "风险"));

        assertEquals(1, hits.size(), "过滤后只应剩风险类文档: " + hits);
        assertEquals("doc-risk", hits.get(0).docId());
    }

    @Test
    @DisplayName("df 在过滤后的子集上统计：同一查询的 IDF 随语料子集变化")
    void idfComputedOnFilteredSubset() {
        index.add("a", "d1", "t1", "私募 私募 私募", Map.of("group", "A"));
        index.add("b", "d2", "t2", "私募 私募", Map.of("group", "B"));
        index.add("c", "d3", "t3", "私募", Map.of("group", "A"));

        // 全量（3 篇都有「私募」）vs 子集 A（2 篇都有）：df 不同 → idf 不同
        double fullScore = index.search("私募", 10, null).stream()
                .filter(s -> s.businessId().equals("a")).findFirst().orElseThrow().score();
        double subsetScore = index.search("私募", 10, Map.of("group", "A")).stream()
                .filter(s -> s.businessId().equals("a")).findFirst().orElseThrow().score();

        assertTrue(fullScore > 0 && subsetScore > 0);
        assertTrue(fullScore != subsetScore,
                "df 在子集上重新统计后，同一文档的得分应当变化（full=" + fullScore + ", subset=" + subsetScore + "）");
    }

    @Test
    @DisplayName("词频饱和：重复词的贡献有上限，不会无限放大")
    void termFrequencySaturates() {
        // 等长文档，词出现 1 次与 4 次：4 次的得分更高，但远小于 4 倍
        index.add("one", "d1", "t1", "准入 标准评估报告说明", Map.of());
        index.add("four", "d2", "t2", "准入 准入 准入 准入 报告说明评估", Map.of());

        double one = index.search("准入", 10).stream()
                .filter(s -> s.businessId().equals("one")).findFirst().orElseThrow().score();
        double four = index.search("准入", 10).stream()
                .filter(s -> s.businessId().equals("four")).findFirst().orElseThrow().score();

        assertTrue(four > one, "重复出现应加分: " + four + " vs " + one);
        assertTrue(four < one * 4, "TF 饱和后不应线性放大: " + four + " vs " + one);
    }

    @Test
    @DisplayName("与 Chunker 联动：按片段入索引，检索结果能对回 docId")
    void worksWithChunkerOutput() {
        Document doc = new Document("doc-x", "标题", "准入要求 1000 万。\n\n第二段另一事实。",
                "corpus/x.md", Map.of("category", "准入"));
        List<Chunk> chunks = new Chunker().chunk(doc);
        for (Chunk chunk : chunks) {
            index.add(chunk);
        }

        List<Bm25Index.Scored> hits = index.search("准入要求", 5, Map.of("category", "准入"));

        assertTrue(hits.size() >= 1);
        assertEquals("doc-x", hits.get(0).docId());
        assertEquals(chunks.get(0).id(), hits.get(0).businessId());
    }

    @Test
    @DisplayName("无关查询返回空列表而不是报错")
    void unrelatedQueryReturnsEmpty() {
        seed();

        // 语料里没有任何与「外太空探测器」重叠的词项
        assertTrue(index.search("外太空探测器维修手册", 5).isEmpty());
        assertEquals(0, index.search("", 5).size());
    }
}
