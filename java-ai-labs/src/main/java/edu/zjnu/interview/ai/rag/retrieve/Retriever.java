package edu.zjnu.interview.ai.rag.retrieve;

import java.util.List;
import java.util.Map;

/**
 * 召回器端口。
 *
 * <p>把「召回」抽象成接口的价值在于：pipeline 可以自由组合 dense / sparse / hybrid，
 * 实验时也能只替换其中一路而不动其它环节——这正是做配置对比实验的前提。
 */
public interface Retriever {

    /**
     * 召回。
     *
     * @param query 查询
     * @param topK  返回条数
     * @return 候选列表（按相关性降序）
     */
    List<Candidate> retrieve(String query, int topK);

    /**
     * 带 payload 过滤的召回。
     *
     * @param query  查询
     * @param topK   返回条数
     * @param filter payload 等值过滤
     */
    default List<Candidate> retrieve(String query, int topK, Map<String, Object> filter) {
        return retrieve(query, topK);
    }
}
