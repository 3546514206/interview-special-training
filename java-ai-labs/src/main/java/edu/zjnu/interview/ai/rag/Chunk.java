package edu.zjnu.interview.ai.rag;

import java.util.Map;

/**
 * 一个检索片段（chunk）。
 *
 * @param id       片段 id：由 {@code Ids.deterministicUuid} 生成，保证「同样的语料 + 同样的切分配置」永远得到同样的 id
 * @param docId    所属文档 id
 * @param title    所属文档标题（冗余存储，避免检索时回表）
 * @param content  片段正文
 * @param ordinal  在文档内的序号（从 0 开始），重排时用作「位置先验」
 * @param metadata 附加元数据（source、chunk 长度、文档业务元数据等）
 */
public record Chunk(String id, String docId, String title, String content, int ordinal, Map<String, Object> metadata) {

    public Chunk {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
