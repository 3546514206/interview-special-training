package edu.zjnu.interview.ai.agent.workflow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流上下文：节点之间传递状态的载体。
 *
 * <p>节点之间不直接互相调用，只通过上下文里的状态通信。
 * 这样做的好处：
 * <ul>
 *   <li>节点可以被单独测试（给一个装好状态的上下文即可）；</li>
 *   <li>工作流的执行路径变得可观测（上下文里能看到每一步留下了什么）；</li>
 *   <li>条件边可以基于上下文做路由决策。</li>
 * </ul>
 *
 * <p>上下文里的 key 约定：节点自己的产出放在 {@code node.<节点名>}，
 * 业务状态用业务语义命名（如 {@code needRetrieval}、{@code verifyAttempts}）。
 */
public final class WorkflowContext {

    private final String traceId;
    private final Map<String, Object> state = new LinkedHashMap<>();
    private final Instant deadline;

    public WorkflowContext(String traceId) {
        this(traceId, null);
    }

    public WorkflowContext(String traceId, Instant deadline) {
        this.traceId = traceId;
        this.deadline = deadline;
    }

    public String traceId() {
        return traceId;
    }

    /** 整体截止时间（可空）。 */
    public Instant deadline() {
        return deadline;
    }

    public boolean expired() {
        return deadline != null && Instant.now().isAfter(deadline);
    }

    public WorkflowContext put(String key, Object value) {
        state.put(key, value);
        return this;
    }

    public Object get(String key) {
        return state.get(key);
    }

    public String getString(String key) {
        Object value = state.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public String getString(String key, String defaultValue) {
        String value = getString(key);
        return value == null ? defaultValue : value;
    }

    public int getInt(String key, int defaultValue) {
        Object value = state.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = state.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return defaultValue;
    }

    /** 计数器自增，返回自增后的值。 */
    public int increment(String key) {
        int next = getInt(key, 0) + 1;
        state.put(key, next);
        return next;
    }

    /** 读取计数器。 */
    public int counter(String key) {
        return getInt(key, 0);
    }

    /** 状态快照。 */
    public Map<String, Object> state() {
        return Map.copyOf(state);
    }
}
