package edu.zjnu.interview.ai.rag.store;

/**
 * Qdrant 调用失败。
 *
 * <p>约定：<b>任何对 Qdrant 的失败都直接抛出，不吞异常</b>。
 * RAG 的索引链路里，「静默失败」比「明确报错」危险得多——
 * 写失败却当成写成功，最终表现出来的是「搜不到东西」，排查成本高一个数量级。
 */
public class QdrantException extends RuntimeException {

    private final int status;

    public QdrantException(String message) {
        this(message, -1, null);
    }

    public QdrantException(String message, int status, String body) {
        super(status > 0 ? message + "（HTTP " + status + "）：" + body : message);
        this.status = status;
    }

    public QdrantException(String message, Throwable cause) {
        super(message, cause);
        this.status = -1;
    }

    /** HTTP 状态码（网络层异常时为 -1）。 */
    public int status() {
        return status;
    }
}
