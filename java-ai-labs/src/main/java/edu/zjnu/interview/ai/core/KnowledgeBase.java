package edu.zjnu.interview.ai.core;

import java.util.List;
import java.util.Map;

/**
 * 知识检索端口：Agent 与 RAG 之间的解耦点。
 *
 * <p>Agent 只依赖这个接口，因此既可以接「向量库 + BM25 的完整 RAG 检索器」，
 * 也可以接「本地内存里的一个玩具实现」来跑离线单测。
 */
public interface KnowledgeBase {

    /**
     * 检索。
     *
     * @param query 用户问题
     * @param topK  返回条数
     * @return 命中列表，按相关性降序
     */
    List<Hit> search(String query, int topK);

    default List<Hit> search(String query) {
        return search(query, 5);
    }

    /**
     * 检索命中。
     *
     * @param id       片段 id
     * @param title    来源标题
     * @param content  正文
     * @param score    相关性得分（越大越相关）
     * @param metadata 元数据（来源文件、chunk 序号、权限标签等）
     */
    record Hit(String id, String title, String content, double score, Map<String, Object> metadata) {
        public Hit {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
