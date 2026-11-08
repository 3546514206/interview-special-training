package edu.zjnu.interview.ai.rag.store;

import java.util.Map;
import java.util.Objects;

/**
 * payload 等值过滤的统一语义。
 *
 * <p><b>为什么要单独抽一层？</b>
 * 内存实现是「遍历 + 判断」，Qdrant 实现是「翻译成 Qdrant filter DSL 交给服务端执行」。
 * 如果两边的语义各写各的，就会出现一个非常难查的线上问题：
 * 本地测试全绿，线上 Qdrant 却少召回了一半数据。
 * 所以这里定义唯一的语义（等值匹配 + 数字类型宽松比较），
 * 内存实现直接调用它，Qdrant 实现把它翻译成 {@code {"must":[{"key":k,"match":{"value":v}}]}}。
 *
 * <p>数字比较为什么宽松：payload 经过 JSON 往返之后，{@code Integer} 可能变成 {@code Long}、
 * {@code Double}，如果用 {@code equals} 比较会全部失配（{@code 100 != 100L}）。
 * 这里统一按 double 值比较，避免「过滤条件明明对却一条都查不出来」。
 */
public final class PayloadFilters {

    private PayloadFilters() {
    }

    /**
     * payload 是否命中过滤条件（所有键都必须命中，即 AND 语义）。
     *
     * @param payload 候选 payload
     * @param filter  过滤条件；null 或空表示不过滤
     */
    public static boolean matches(Map<String, Object> payload, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        if (payload == null) {
            return false;
        }
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            Object expected = entry.getValue();
            Object actual = payload.get(entry.getKey());
            if (!valueEquals(expected, actual)) {
                return false;
            }
        }
        return true;
    }

    /** 值相等判定（数字按数值比较，其余按 equals）。 */
    public static boolean valueEquals(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return Objects.equals(expected, actual);
        }
        if (expected instanceof Number a && actual instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        if (expected instanceof String a && actual instanceof String b) {
            return a.equals(b);
        }
        return Objects.equals(expected, actual);
    }
}
