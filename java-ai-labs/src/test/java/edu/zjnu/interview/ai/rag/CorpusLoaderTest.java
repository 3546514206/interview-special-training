package edu.zjnu.interview.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语料加载器测试。
 *
 * <p>语料是资源不是文件：打 jar 后必须还能加载。这里同时验证
 * classpath 目录枚举与 front matter 解析两条约定。
 */
class CorpusLoaderTest {

    @Test
    @DisplayName("从 classpath 加载真实语料：10 篇中文文档，全部有 id / 标题 / 正文")
    void loadsRealCorpus() {
        List<Document> docs = CorpusLoader.loadFromClasspath("corpus");

        assertTrue(docs.size() >= 10, "语料应不少于 10 篇，实际: " + docs.size());
        for (Document doc : docs) {
            assertTrue(!doc.id().isBlank(), "文档 id 不能为空");
            assertTrue(!doc.title().isBlank(), "文档标题不能为空: " + doc.id());
            assertTrue(doc.content().length() > 100, "正文过短: " + doc.id() + " " + doc.content().length());
            assertTrue(!doc.source().isBlank());
            assertTrue(doc.metadata().get("category") != null,
                    "语料应带 category 元数据（过滤与评测依赖它）: " + doc.id());
        }
    }

    @Test
    @DisplayName("文档 id 取文件名主干，跨机器稳定（黄金集按它标注）")
    void docIdIsStableFileNameStem() {
        List<Document> docs = CorpusLoader.loadFromClasspath("corpus");

        // 断言至少包含几篇确定存在的语料
        List<String> ids = docs.stream().map(Document::id).toList();
        assertTrue(ids.contains("private-product-suitability")
                        || ids.contains("asset-allocation-service")
                        || ids.contains("risk-identification-rules"),
                "应包含已知语料文件: " + ids);
    }

    @Test
    @DisplayName("按文件名精确加载指定文档")
    void loadsByName() {
        List<Document> docs = CorpusLoader.loadFromClasspath("corpus",
                List.of("risk-identification-rules.md"));

        assertEquals(1, docs.size());
        assertEquals("risk-identification-rules", docs.get(0).id());
    }

    @Test
    @DisplayName("front matter 解析：key: value 进入 metadata，# 标题成为 title")
    void parsesFrontMatter() {
        String text = """
                ---
                category: 测试
                updatedAt: 2026-01-01
                ---
                # 演示标题
                正文第一段。
                """;

        Document doc = CorpusLoader.parse("demo.md", text);

        assertEquals("demo", doc.id());
        assertEquals("演示标题", doc.title());
        assertEquals("测试", String.valueOf(doc.metadata().get("category")));
        assertTrue(doc.content().contains("正文第一段"));
        assertTrue(!doc.content().contains("# 演示标题"), "标题行不应留在正文里");
    }
}
