package edu.zjnu.interview.ai.rag.store;

/**
 * Qdrant REST 传输层（可替换的接缝）。
 *
 * <p><b>为什么要抽这个接口？</b>
 * 直接在 {@code QdrantVectorStore} 里 new {@code HttpClient} 的话，
 * 「构造出来的 filter JSON 对不对」「upsert body 的字段名对不对」这些只能靠真连服务器才能验证，
 * 单元测试就会依赖网络——这正是 RAG 项目里最常见的「测试没法跑」原因。
 * 抽出接口后：生产用 {@link HttpQdrantTransport}，测试用假实现，
 * <b>协议细节（URL、body 结构、状态码处理）全部可以离线断言</b>。
 */
public interface QdrantTransport {

    /**
     * 发起一次请求。
     *
     * @param method HTTP 方法（GET / PUT / POST / DELETE）
     * @param path   以 {@code /} 开头的路径（相对 base url）
     * @param body   请求体 JSON；GET / DELETE 可为 null
     * @return 响应（状态码 + 响应体）
     */
    Response send(String method, String path, String body);

    /**
     * 一次 HTTP 响应。
     *
     * @param status HTTP 状态码
     * @param body   响应体
     */
    record Response(int status, String body) {

        /** 是否是 2xx。 */
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** 404（集合或资源不存在）。 */
        public boolean notFound() {
            return status == 404;
        }
    }
}
