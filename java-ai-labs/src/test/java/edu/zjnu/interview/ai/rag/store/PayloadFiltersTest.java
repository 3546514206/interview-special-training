package edu.zjnu.interview.ai.rag.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * payload 过滤语义测试。
 *
 * <p>这层是「内存实现」与「Qdrant 实现」共用的唯一语义来源。
 * 它若出错，症状是「本地测试全绿、线上少召回一半」——必须单独锁定。
 */
class PayloadFiltersTest {

    @Test
    @DisplayName("空过滤条件不过滤任何文档")
    void emptyFilterMatchesAll() {
        assertTrue(PayloadFilters.matches(Map.of("k", "v"), null));
        assertTrue(PayloadFilters.matches(Map.of("k", "v"), Map.of()));
    }

    @Test
    @DisplayName("多条件是 AND 语义：全部命中才算命中")
    void multipleConditionsAreAndSemantics() {
        Map<String, Object> payload = Map.of("category", "准入", "source", "a.md");

        assertTrue(PayloadFilters.matches(payload, Map.of("category", "准入", "source", "a.md")));
        assertFalse(PayloadFilters.matches(payload, Map.of("category", "准入", "source", "b.md")));
    }

    @Test
    @DisplayName("数字宽松比较：Integer / Long / Double 按数值相等")
    void numbersCompareByValue() {
        // JSON 往返后 100 可能变成 100L 或 100.0，equals 会失配
        assertTrue(PayloadFilters.valueEquals(100, 100L));
        assertTrue(PayloadFilters.valueEquals(100, 100.0));
        assertTrue(PayloadFilters.valueEquals(100.0, 100L));
        assertFalse(PayloadFilters.valueEquals(100, 101));
    }

    @Test
    @DisplayName("缺失键视为不命中，null 条件与 null 值互相匹配")
    void missingKeyDoesNotMatch() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("a", 1);

        assertFalse(PayloadFilters.matches(payload, Map.of("missing", "x")));

        // Map.of 不允许 null 值，这里用 HashMap 构造 null 过滤条件
        Map<String, Object> nullFilter = new HashMap<>();
        nullFilter.put("missing", null);
        assertTrue(PayloadFilters.matches(payload, nullFilter));
    }

    @Test
    @DisplayName("null payload 只有空过滤才放行")
    void nullPayloadOnlyPassesEmptyFilter() {
        assertTrue(PayloadFilters.matches(null, null));
        assertFalse(PayloadFilters.matches(null, Map.of("k", "v")));
    }
}
