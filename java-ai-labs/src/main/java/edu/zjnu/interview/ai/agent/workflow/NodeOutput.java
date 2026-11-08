package edu.zjnu.interview.ai.agent.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点输出。
 *
 * <p><b>关键语义</b>：{@code success} 表示「这个节点有没有跑完」，
 * <b>不表示「业务结果好不好」</b>。
 * 「答案没通过校验」不是节点失败，而是一次<b>成功的校验</b>——
 * 它通过条件边路由到「重新检索」，而不是把整条工作流判死。
 * 把「执行成功」和「业务达标」混在一起，是工作流引擎最常见的建模错误。
 *
 * @param success      节点是否执行成功
 * @param value        产出值（写回上下文，供下游节点读取）
 * @param errorMessage 失败原因（成功时为 null）
 * @param data         附加数据
 */
public record NodeOutput(boolean success, Object value, String errorMessage, Map<String, Object> data) {

    public NodeOutput {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static NodeOutput ok(Object value) {
        return new NodeOutput(true, value, null, Map.of());
    }

    public static NodeOutput ok(Object value, Map<String, Object> data) {
        return new NodeOutput(true, value, null, data);
    }

    public static NodeOutput fail(String errorMessage) {
        return new NodeOutput(false, null, errorMessage, Map.of());
    }

    /** 附加一个数据项。 */
    public NodeOutput withData(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(data);
        merged.put(key, value);
        return new NodeOutput(success, this.value, errorMessage, merged);
    }
}
