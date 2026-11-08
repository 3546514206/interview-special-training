package edu.zjnu.interview.ai.rag;

import java.util.Map;

/**
 * 一篇原始文档（切分之前的形态）。
 *
 * <p>「文档」与「片段」在 RAG 里是两个不同的实体，必须在数据模型上分开：
 * 文档是<b>业务实体</b>（有标题、有来源、有权限属性），片段是<b>检索单元</b>（有序号、有向量）。
 * 混在一起会导致「引用编号到底指向文档还是片段」这类问题在后期无法回答——
 * 本模块的引用编号最终指向<b>文档</b>（golden 集也按文档标注），片段只是承载它的检索载体。
 *
 * @param id       文档 id（稳定、可读，例如文件名主干），黄金集按它标注期望文档
 * @param title    文档标题
 * @param content  正文（已剥离 front matter 与标题行）
 * @param source   来源标识（classpath 相对路径）
 * @param metadata 业务元数据（category、updatedAt 等），会随片段进入向量库 payload 用于过滤
 */
public record Document(String id, String title, String content, String source, Map<String, Object> metadata) {

    public Document {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
