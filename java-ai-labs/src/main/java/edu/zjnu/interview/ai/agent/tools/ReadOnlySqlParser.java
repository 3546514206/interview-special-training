package edu.zjnu.interview.ai.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 极简只读 SQL 解析器：只支持 {@code SELECT ... FROM ... [WHERE col = value] [LIMIT n]}。
 *
 * <p><b>为什么不直接用 JDBC</b>：
 * <ul>
 *   <li>JDBC 只能「连上库再执行」，而 Agent 场景里数据库往往是远端服务，
 *       让模型生成的 SQL 直连生产库等于把写权限交给模型；</li>
 *   <li>要「只读」地限制 SQL，标准做法是解析 SQL AST 后按白名单裁剪，
 *       这需要引入解析库（JSqlParser 之类），与「只用 JDK + Jackson」的工程约束冲突。</li>
 * </ul>
 *
 * <p>因此这里走「白名单解析」路线：<b>只认得 SELECT，其它一律拒绝</b>。
 * 解析器不认识任何写语法，因此不存在「绕过检查」的路径——
 * 这是最朴素但最可靠的只读实现方式。
 *
 * <p>支持的子集刻意很小：等值 WHERE（可 AND 串联）、LIMIT。
 * 想要 JOIN / 聚合 / 子查询，就说明这个工具选错了工具。
 */
public final class ReadOnlySqlParser {

    /** 解析失败（带错误码，便于回灌给模型）。 */
    public static final class SqlParseException extends RuntimeException {
        private final String code;

        public SqlParseException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 禁止出现的写操作关键字。 */
    private static final Pattern FORBIDDEN_PATTERN = Pattern.compile(
            "(?i)\\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|MERGE|REPLACE|GRANT|REVOKE|"
                    + "ATTACH|DETACH|PRAGMA|VACUUM|CALL|EXEC|EXECUTE|COPY|INTO)\\b");

    private final List<String> tokens;
    private int pos;

    private ReadOnlySqlParser(List<String> tokens) {
        this.tokens = tokens;
    }

    /**
     * 解析 SQL。
     *
     * @param sql 原始 SQL
     * @return 解析结果
     * @throws SqlParseException SQL 不合法或包含写操作
     */
    public static Query parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new SqlParseException("SQL_EMPTY", "SQL 为空");
        }
        String cleaned = stripComments(sql);
        if (FORBIDDEN_PATTERN.matcher(cleaned).find()) {
            throw new SqlParseException("SQL_FORBIDDEN",
                    "只允许只读 SELECT 查询，禁止执行 DDL/DML/其它写操作");
        }
        List<String> tokens = tokenize(cleaned);
        return new ReadOnlySqlParser(tokens).parseQuery();
    }

    /** 去掉行注释（{@code -- ...}），避免注释里藏关键字绕过检查。 */
    static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\n")) {
            int idx = line.indexOf("--");
            String trimmed = idx >= 0 ? line.substring(0, idx) : line;
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(trimmed);
        }
        return sb.toString().trim();
    }

    /** 分词：WORD / NUMBER / STRING / SYMBOL。 */
    static List<String> tokenize(String sql) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(sql.substring(start, i));
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i;
                boolean seenDot = false;
                while (i < n && (Character.isDigit(sql.charAt(i)) || (sql.charAt(i) == '.' && !seenDot))) {
                    if (sql.charAt(i) == '.') {
                        seenDot = true;
                    }
                    i++;
                }
                tokens.add(sql.substring(start, i));
                continue;
            }
            if (c == '\'') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char q = sql.charAt(i);
                    if (q == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            sb.append('\'');
                            i += 2;
                            continue;
                        }
                        i++;
                        closed = true;
                        break;
                    }
                    sb.append(q);
                    i++;
                }
                if (!closed) {
                    throw new SqlParseException("SQL_UNTERMINATED_STRING", "字符串字面量没有闭合的引号");
                }
                tokens.add("'" + sb);
                continue;
            }
            tokens.add(String.valueOf(c));
            i++;
        }
        return tokens;
    }

    private Query parseQuery() {
        expectKeyword("SELECT");
        List<String> columns = new ArrayList<>();
        boolean selectAll = false;
        if (peekSymbol("*")) {
            pos++;
            selectAll = true;
        } else {
            columns.add(expectWord("列名"));
            while (peekSymbol(",")) {
                pos++;
                columns.add(expectWord("列名"));
            }
        }
        expectKeyword("FROM");
        String table = expectWord("表名");

        List<Condition> conditions = new ArrayList<>();
        if (peekKeyword("WHERE")) {
            pos++;
            while (true) {
                String column = expectWord("字段名");
                String op = peekToken();
                if (!"=".equals(op)) {
                    throw new SqlParseException("SQL_UNSUPPORTED",
                            "WHERE 只支持等值条件（列 = 值），不支持操作符 '" + op + "'");
                }
                pos++;
                conditions.add(new Condition(column, parseLiteral()));
                if (peekKeyword("AND")) {
                    pos++;
                    continue;
                }
                break;
            }
        }

        Integer limit = null;
        if (peekKeyword("LIMIT")) {
            pos++;
            String literal = expectNumber("LIMIT 的数字");
            try {
                limit = Integer.valueOf(literal);
            } catch (NumberFormatException e) {
                throw new SqlParseException("SQL_INVALID_LIMIT", "LIMIT 必须是整数: " + literal);
            }
            if (limit < 0) {
                throw new SqlParseException("SQL_INVALID_LIMIT", "LIMIT 不能为负数");
            }
        }

        if (peekSymbol(";")) {
            pos++;
        }
        if (pos < tokens.size()) {
            throw new SqlParseException("SQL_UNSUPPORTED",
                    "无法解析的剩余内容: '" + String.join(" ", tokens.subList(pos, tokens.size())) + "'");
        }
        return new Query(List.copyOf(columns), selectAll, table, List.copyOf(conditions), limit);
    }

    private Object parseLiteral() {
        String token = peekToken();
        if (token == null) {
            throw new SqlParseException("SQL_PARSE_ERROR", "条件缺少值");
        }
        if (token.startsWith("'")) {
            pos++;
            return token.substring(1);
        }
        if (token.chars().allMatch(Character::isDigit)) {
            pos++;
            try {
                return Long.parseLong(token);
            } catch (NumberFormatException e) {
                return token;
            }
        }
        throw new SqlParseException("SQL_UNSUPPORTED", "条件值只支持数字或单引号字符串，得到: " + token);
    }

    private String peekToken() {
        return pos < tokens.size() ? tokens.get(pos) : null;
    }

    private boolean peekKeyword(String keyword) {
        String token = peekToken();
        return token != null && token.equalsIgnoreCase(keyword);
    }

    private boolean peekSymbol(String symbol) {
        String token = peekToken();
        return token != null && token.equals(symbol);
    }

    private void expectKeyword(String keyword) {
        if (!peekKeyword(keyword)) {
            throw new SqlParseException("SQL_PARSE_ERROR",
                    "期望关键字 " + keyword + "，实际为: " + peekToken());
        }
        pos++;
    }

    private String expectWord(String what) {
        String token = peekToken();
        if (token == null || token.isEmpty() || !Character.isLetter(token.charAt(0)) && token.charAt(0) != '_') {
            throw new SqlParseException("SQL_PARSE_ERROR", "期望" + what + "，实际为: " + token);
        }
        pos++;
        return token.toLowerCase(Locale.ROOT);
    }

    /** 数字字面量（LIMIT 用）。 */
    private String expectNumber(String what) {
        String token = peekToken();
        if (token == null || token.isEmpty() || !token.chars().allMatch(Character::isDigit)) {
            throw new SqlParseException("SQL_PARSE_ERROR", "期望" + what + "，实际为: " + token);
        }
        pos++;
        return token;
    }

    /**
     * 解析出的查询。
     *
     * @param columns    选中的列（{@code selectAll} 时为空）
     * @param selectAll  是否 {@code SELECT *}
     * @param table      表名（小写）
     * @param conditions 等值条件
     * @param limit      行数上限（可空）
     */
    public record Query(List<String> columns, boolean selectAll, String table,
                        List<Condition> conditions, Integer limit) {
    }

    /**
     * 一个等值条件。
     *
     * @param column 列名（小写）
     * @param value  值（String 或 Long）
     */
    public record Condition(String column, Object value) {
    }
}
