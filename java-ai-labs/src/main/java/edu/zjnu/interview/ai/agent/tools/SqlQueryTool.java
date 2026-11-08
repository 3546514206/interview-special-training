package edu.zjnu.interview.ai.agent.tools;

import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.core.Tool;
import edu.zjnu.interview.ai.core.ToolCall;
import edu.zjnu.interview.ai.core.ToolContext;
import edu.zjnu.interview.ai.core.ToolResult;
import edu.zjnu.interview.ai.core.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 只读 SQL 查询工具：对内存表执行 SELECT。
 *
 * <p><b>安全模型</b>：解析器（{@link ReadOnlySqlParser}）是白名单实现，
 * 只认得 SELECT 语法，DDL/DML 在解析阶段就被拒绝；
 * 工具还额外做一层关键字扫描，防止注释绕过。
 * 数据源是进程内的 {@code Map<String, List<Map<String,Object>>>}，
 * 因此不存在「连接到生产库」这一类风险面。
 *
 * <p><b>为什么仍然要做成工具</b>：结构化数据查询是 Agent 的高频需求，
 * 让模型用自然语言问「上季度 TOP3 用户」比让模型拼 SQL 更可控；
 * 而且把 SQL 的能力边界（只读 + 固定子集）写死在工具里，
 * 比指望模型自律要可靠得多。
 */
public final class SqlQueryTool implements Tool {

    public static final String NAME = "sql_query";

    private final Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();

    public SqlQueryTool() {
    }

    public SqlQueryTool(Map<String, List<Map<String, Object>>> initialTables) {
        if (initialTables != null) {
            initialTables.forEach(this::registerTable);
        }
    }

    /** 注册一张表（表名大小写不敏感）。 */
    public SqlQueryTool registerTable(String name, List<Map<String, Object>> rows) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        tables.put(name.toLowerCase(Locale.ROOT), rows == null ? List.of() : List.copyOf(rows));
        return this;
    }

    /** 表名列表。 */
    public List<String> tableNames() {
        return List.copyOf(tables.keySet());
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder(NAME, "对内存表执行只读 SELECT 查询，支持 WHERE 等值条件与 LIMIT")
                .param("sql", "string", true, "SELECT 语句，例如 SELECT name, age FROM users WHERE age > 30 LIMIT 10")
                .sideEffect(ToolSpec.SideEffect.READ_ONLY)
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        Map<String, Object> args = Json.readMap(call.argumentsJson());
        Object rawSql = args.get("sql");
        if (rawSql == null) {
            return ToolResult.error("MISSING_ARGUMENT", "缺少参数 sql");
        }
        String sql = String.valueOf(rawSql);
        ReadOnlySqlParser.Query query;
        try {
            query = ReadOnlySqlParser.parse(sql);
        } catch (ReadOnlySqlParser.SqlParseException e) {
            return ToolResult.error(e.code(), e.getMessage());
        }

        List<Map<String, Object>> rows = tables.get(query.table().toLowerCase(Locale.ROOT));
        if (rows == null) {
            return ToolResult.error("TABLE_NOT_FOUND",
                    "表不存在: " + query.table() + "，可用表: " + tableNames());
        }

        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (matches(row, query.conditions())) {
                matched.add(row);
            }
        }
        if (query.limit() != null && matched.size() > query.limit()) {
            matched = new ArrayList<>(matched.subList(0, query.limit()));
        }

        List<Map<String, Object>> projected = new ArrayList<>();
        for (Map<String, Object> row : matched) {
            projected.add(project(row, query));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("table", query.table());
        data.put("rowCount", projected.size());
        return ToolResult.ok(Json.write(projected), data);
    }

    /** 条件是否全部满足。 */
    private boolean matches(Map<String, Object> row, List<ReadOnlySqlParser.Condition> conditions) {
        for (ReadOnlySqlParser.Condition condition : conditions) {
            Object actual = lookup(row, condition.column());
            if (!valueEquals(actual, condition.value())) {
                return false;
            }
        }
        return true;
    }

    /** 按列名取值（大小写不敏感）。 */
    private static Object lookup(Map<String, Object> row, String column) {
        if (row.containsKey(column)) {
            return row.get(column);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 值比较：数字按数值比较，其余按字符串比较。 */
    static boolean valueEquals(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        if (actual instanceof Number a && expected instanceof Number e) {
            return Double.compare(a.doubleValue(), e.doubleValue()) == 0;
        }
        if (actual instanceof Number a && expected instanceof String e) {
            try {
                return Double.compare(a.doubleValue(), Double.parseDouble(e.trim())) == 0;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return String.valueOf(actual).equals(String.valueOf(expected));
    }

    /** 列投影。 */
    private Map<String, Object> project(Map<String, Object> row, ReadOnlySqlParser.Query query) {
        if (query.selectAll()) {
            return new LinkedHashMap<>(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String column : query.columns()) {
            out.put(column, lookup(row, column));
        }
        return out;
    }
}
