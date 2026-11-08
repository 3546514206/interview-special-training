package edu.zjnu.interview.ai.agent.tools;

import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.core.ToolCall;
import edu.zjnu.interview.ai.core.ToolContext;
import edu.zjnu.interview.ai.core.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只读 SQL 工具：白名单解析 + 写操作一律拒绝。
 */
class SqlQueryToolTest {

    private final SqlQueryTool tool = new SqlQueryTool()
            .registerTable("users", List.of(
                    Map.of("id", 1L, "name", "张三", "level", "R3"),
                    Map.of("id", 2L, "name", "李四", "level", "R5"),
                    Map.of("id", 3L, "name", "王五", "level", "R3")))
            .registerTable("orders", List.of(
                    Map.of("id", 10L, "user_id", 1L, "amount", 100.0),
                    Map.of("id", 11L, "user_id", 2L, "amount", 250.0)));
    private final ToolContext ctx = ToolContext.of("trace-test", Paths.get("/tmp"));

    private ToolResult query(String sql) {
        return tool.execute(ToolCall.of("c1", SqlQueryTool.NAME, "{\"sql\":\"" + sql + "\"}"), ctx);
    }

    @Test
    @DisplayName("SELECT * 返回全部行")
    void selectsAll() {
        ToolResult result = query("SELECT * FROM users");

        assertTrue(result.success());
        assertEquals(3, result.data().get("rowCount"));
        List<?> rows = Json.readList(result.content());
        assertEquals(3, rows.size());
    }

    @Test
    @DisplayName("列投影")
    void selectsColumns() {
        ToolResult result = query("SELECT name, level FROM users");

        assertTrue(result.success());
        List<?> rows = Json.readList(result.content());
        Map<?, ?> first = (Map<?, ?>) rows.get(0);
        assertTrue(first.containsKey("name"));
        assertFalse(first.containsKey("id"));
    }

    @Test
    @DisplayName("WHERE 等值条件过滤")
    void filtersByEquality() {
        ToolResult result = query("SELECT * FROM users WHERE level = 'R3'");

        assertTrue(result.success());
        assertEquals(2, result.data().get("rowCount"));
    }

    @Test
    @DisplayName("多个条件用 AND 串联")
    void filtersWithAnd() {
        ToolResult result = query("SELECT * FROM users WHERE level = 'R3' AND name = '张三'");

        assertTrue(result.success());
        assertEquals(1, result.data().get("rowCount"));
    }

    @Test
    @DisplayName("LIMIT 截断")
    void appliesLimit() {
        ToolResult result = query("SELECT * FROM users LIMIT 2");

        assertTrue(result.success());
        assertEquals(2, result.data().get("rowCount"));
    }

    @Test
    @DisplayName("DDL 一律拒绝")
    void rejectsDdl() {
        for (String sql : new String[]{
                "DROP TABLE users",
                "CREATE TABLE t (id int)",
                "ALTER TABLE users ADD COLUMN x int",
                "TRUNCATE TABLE users"}) {
            ToolResult result = query(sql);
            assertFalse(result.success(), "应当拒绝: " + sql);
            assertEquals("SQL_FORBIDDEN", result.errorCode());
        }
    }

    @Test
    @DisplayName("DML 一律拒绝")
    void rejectsDml() {
        for (String sql : new String[]{
                "DELETE FROM users",
                "UPDATE users SET level = 'R1'",
                "INSERT INTO users (id) VALUES (9)"}) {
            ToolResult result = query(sql);
            assertFalse(result.success(), "应当拒绝: " + sql);
            assertEquals("SQL_FORBIDDEN", result.errorCode());
        }
    }

    @Test
    @DisplayName("注释里的内容不参与执行：被注释掉的 DML 是无害的")
    void ignoresCommentedOutDml() {
        // 注释在解析前就被剥离，注释掉的 DELETE 不会执行，也不该误伤正常查询
        ToolResult result = query("SELECT * FROM users -- DELETE FROM users");

        assertTrue(result.success());
        assertEquals(3, result.data().get("rowCount"));
    }

    @Test
    @DisplayName("表名带下划线不会被关键字误伤")
    void allowsTableNamesContainingKeywords() {
        SqlQueryTool other = new SqlQueryTool()
                .registerTable("update_log", List.of(Map.of("id", 1L)));

        ToolResult result = other.execute(
                ToolCall.of("c1", SqlQueryTool.NAME, "{\"sql\":\"SELECT * FROM update_log\"}"), ctx);

        assertTrue(result.success());
        assertEquals(1, result.data().get("rowCount"));
    }

    @Test
    @DisplayName("表不存在 → TABLE_NOT_FOUND")
    void tableNotFound() {
        ToolResult result = query("SELECT * FROM unknown_table");

        assertFalse(result.success());
        assertEquals("TABLE_NOT_FOUND", result.errorCode());
    }

    @Test
    @DisplayName("不支持的操作符被明确拒绝，而不是静默忽略")
    void rejectsUnsupportedOperator() {
        ToolResult result = query("SELECT * FROM users WHERE id > 1");

        assertFalse(result.success());
        assertEquals("SQL_UNSUPPORTED", result.errorCode());
    }

    @Test
    @DisplayName("多语句注入被拒绝")
    void rejectsMultipleStatements() {
        ToolResult result = query("SELECT * FROM users; SELECT * FROM orders");

        assertFalse(result.success());
    }
}
