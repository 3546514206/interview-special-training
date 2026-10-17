package edu.zjnu.interview.database.q05;

import edu.zjnu.interview.database.env.EnvLoader;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库面试 Q5/Q6 实验:回表 vs 覆盖索引 vs 全表扫 4 个核心场景.
 * <p>
 * 跑法(先建表灌数据,再跑 lab):
 * <pre>
 *   # 1) 灌 100 万行(约 30-60 秒)
 *   docker exec -i &lt;mysql-container&gt; mysql --default-character-set=utf8mb4 \
 *     -uroot -p"$DB_PASSWORD" interview_lab &lt; doc/database/sql/q05_back_to_table.sql
 *
 *   # 2) 跑 4 个场景 EXPLAIN + 实测
 *   mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.database.q05.BackToTableLab
 * </pre>
 *
 * 实验设计(均使用 user_big 表 100 万行,age 均匀分布 20~60):
 * <ol>
 *   <li>{@code SELECT * WHERE age > 20}    → 命中 ~97.5 万行 → 优化器选<b>全表扫</b>(type=ALL, key=NULL)
 *   <li>{@code SELECT * WHERE age = 25}    → 命中 ~24400 行 → 优化器选<b>idx_age + 回表</b>(type=ref)
 *   <li>{@code SELECT id,age WHERE age=25}  → 覆盖索引 → <b>0 次回表</b>(Extra=Using index)
 *   <li>{@code SELECT * WHERE id = 12345}   → 走<b>主键</b>(type=const, key=PRIMARY)
 * </ol>
 *
 * 关键结论(面试标准答):
 * <ul>
 *   <li>「走 PK 索引树」歧义:既可指 PK 条件定位(type=const/ref),也可指全表扫顺序遍历 PK 树(type=ALL, key=NULL)
 *   <li>全表扫在 InnoDB 里就是顺序遍历聚簇索引(主键索引树),<b>不是</b>「没读 PK 树」
 *   <li>{@code SELECT *} 本身不决定走 PK,真正影响的是<b>覆盖索引</b>——回表成本高时优化器放弃二级索引
 *   <li>「回表」是物理动作(随机 I/O 跳 PK 树),<b>不是</b>性能开关
 * </ul>
 *
 * @author 杨海波 (Setsuna Yang)
 */
public class BackToTableLab {

    private static final String HOST = EnvLoader.getRequired("DB_HOST");
    private static final String PORT = EnvLoader.getRequired("DB_PORT");
    private static final String NAME = EnvLoader.getRequired("DB_NAME");
    private static final String URL  = "jdbc:mysql://" + HOST + ":" + PORT + "/" + NAME
            + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
    private static final String USER = EnvLoader.getRequired("DB_USER");
    private static final String PASS = EnvLoader.getRequired("DB_PASSWORD");

    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
            sanityCheck(conn);
            runScenario1(conn);   // SELECT * + 范围 → 全表扫
            runScenario2(conn);   // SELECT * + 等值 → idx_age + 回表
            runScenario3(conn);   // SELECT id,age + 等值 → 覆盖索引
            runScenario4(conn);   // SELECT * + WHERE id → 主键定位
            runScenario5(conn);   // 耗时对比:3 种访问路径的实际开销
        }
    }

    /** 确认 user_big 表存在且有 100 万行. */
    private static void sanityCheck(Connection conn) throws SQLException {
        System.out.println("\n========== 前置检查 ==========");
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) AS cnt FROM user_big")) {
            if (rs.next()) {
                long n = rs.getLong("cnt");
                System.out.println("user_big 总行数: " + n);
                if (n < 900_000) {
                    System.err.println("⚠ 行数不足 90 万,可能是数据未灌好.建议先跑 q05_back_to_table.sql 灌数据.");
                }
            } else {
                System.err.println("⚠ user_big 表不存在!先跑 q05_back_to_table.sql");
            }
        }
        System.out.println();
    }

    // ============== Scenario 1: 高选择性范围 → 全表扫 ==============
    private static void runScenario1(Connection conn) throws SQLException {
        String title = "① SELECT * WHERE age > 20  (命中 ~97.5 万行)";
        String sql   = "SELECT * FROM user_big WHERE age > 20";
        String hint  = "预期:type=ALL, key=NULL, rows≈975000, Extra=Using where";
        String why  =
            "  age>20 命中约 97.5% 行(20~60 共 41 值,20 之外有 40 个).如果走 idx_age:\n"
          + "  - 扫 97.5 万个叶子 → 拿 97.5 万个 id → 跳 PK 树 97.5 万次(随机 I/O)\n"
          + "  - 总成本 ≈ 100 万次随机 I/O,约几十秒\n"
          + "  如果走全表扫:\n"
          + "  - 顺序读 PK 树叶子页,每页 16KB ≈ 100 行,共 ~1 万页\n"
          + "  - 顺序 I/O 预读友好,机械盘也能 ~100MB/s,100 万行 ~2-3 秒\n"
          + "  优化器一算:97.5 万次随机 I/O > 100 万行顺序 I/O → 放弃 idx_age,选全表扫\n"
          + "  ⚠ key=NULL <b>不等于</b>「没读 PK 树」——全表扫就是顺序遍历聚簇索引(主键索引树)";
        runExplainScenario(title, sql, hint, why);
    }

    // ============== Scenario 2: 中等选择性等值 → 走 idx_age + 回表 ==============
    private static void runScenario2(Connection conn) throws SQLException {
        String title = "② SELECT * WHERE age = 25  (命中 ~24400 行)";
        String sql   = "SELECT * FROM user_big WHERE age = 25";
        String hint  = "预期:type=ref, key=idx_age, rows≈24400, Extra=Using where";
        String why  =
            "  age=25 命中约 100万/41 ≈ 24400 行,选择性 2.4%.\n"
          + "  走 idx_age 的成本:\n"
          + "  - 扫 ~24400 个 idx_age 叶子 → 拿 24400 个 id → 跳 PK 树 24400 次\n"
          + "  - 24400 次随机 I/O ≈ 240 秒(机械盘)或 2.4 秒(SSD)\n"
          + "  走全表扫的成本:读 100 万行 ≈ 100 万次顺序 I/O\n"
          + "  优化器判断:24400 次随机 I/O < 100 万次顺序 I/O(尤其在 SSD 上更明显)\n"
          + "  → 选 idx_age + 回表\n"
          + "  ⚠ Extra=Using where 表示走了索引但<b>仍要回表</b>(因为 SELECT * 要所有列)";
        runExplainScenario(title, sql, hint, why);
    }

    // ============== Scenario 3: 覆盖索引 → 0 回表 ==============
    private static void runScenario3(Connection conn) throws SQLException {
        String title = "③ SELECT id, age WHERE age = 25  (覆盖索引, 0 回表)";
        String sql   = "SELECT id, age FROM user_big WHERE age = 25";
        String hint  = "预期:type=ref, key=idx_age, rows≈24400, Extra=Using index";
        String why  =
            "  查询列 (id, age) 都在 idx_age 叶子节点里(idx_age 叶子 = (age, id)).\n"
          + "  idx_age 叶子就能完整回答这个查询,不需要回 PK 树.\n"
          + "  Extra=Using index 是<b>覆盖索引生效</b>的标志.\n"
          + "  对比 Scenario 2:同样走 idx_age,但因为不需要其他列,省了 24400 次回表.\n"
          + "  <b>经验</b>:经常查 (age, ?) 的场景,建 idx_age 比改 SELECT 性价比更高.";
        runExplainScenario(title, sql, hint, why);
    }

    // ============== Scenario 4: 主键定位 ==============
    private static void runScenario4(Connection conn) throws SQLException {
        String title = "④ SELECT * WHERE id = 12345  (走主键定位)";
        String sql   = "SELECT * FROM user_big WHERE id = 12345";
        String hint  = "预期:type=const, key=PRIMARY, rows=1, Extra=NULL";
        String why  =
            "  id 是主键,主键索引树叶子节点就是完整行.\n"
          + "  二分定位 PK=12345 → 1 次寻道 → 拿到完整行.\n"
          + "  与 SELECT * 无关——只要 WHERE 用 PK 列,主键路径就最优.\n"
          + "  回答原问题:「为什么 SELECT * 不默认走 PK 索引树」——\n"
          + "    错. WHERE id=... 时 SELECT * 照样走主键(type=const).\n"
          + "    真正影响的是<b>非 PK 列的查询</b>,SELECT * 增加回表成本,可能让优化器放弃 idx_age 选全表扫.";
        runExplainScenario(title, sql, hint, why);
    }

    // ============== Scenario 5: 实际耗时对比 ==============
    private static void runScenario5(Connection conn) throws SQLException {
        System.out.println("\n========== ⑤ 实际耗时对比 (SQL_NO_CACHE + 3 次平均) ==========");
        String[][] scenarios = {
            {"SELECT * WHERE age > 20",                "全表扫 100 万行(type=ALL)"},
            {"SELECT * WHERE age = 25",                "idx_age + 24400 次回表"},
            {"SELECT id, age WHERE age = 25",          "覆盖索引, 0 回表"},
            {"SELECT * WHERE id = 12345",              "主键定位, 0 回表"}
        };

        System.out.printf("%-40s %15s %20s%n", "SQL", "耗时 (ms)", "说明");
        System.out.println("─".repeat(80));
        for (String[] s : scenarios) {
            String sql = s[0];
            String desc = s[1];
            long total = 0;
            for (int i = 0; i < 3; i++) {
                long t0 = System.nanoTime();
                try (PreparedStatement ps = conn.prepareStatement("SELECT SQL_NO_CACHE " + sql);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { /* drain */ }
                }
                total += (System.nanoTime() - t0) / 1_000_000;
            }
            long avg = total / 3;
            System.out.printf("%-40s %15d %20s%n", truncate(sql, 40), avg, desc);
        }
        System.out.println("─".repeat(80));
        System.out.println("(耗时因机器配置差异很大,本表重点看<b>相对比例</b>:");
        System.out.println(" 全表扫 vs 覆盖索引 通常是 5-50 倍差距,与 EXPLAIN 的 type 预测一致.)");
    }

    // ============== Helpers ==============
    private static void runExplainScenario(String title, String sql, String hint, String why) throws SQLException {
        System.out.println("\n========== " + title + " ==========");
        System.out.println("SQL: " + sql);
        System.out.println("提示: " + hint);
        System.out.println("\n--- EXPLAIN 输出 ---");
        try (PreparedStatement ps = connection().prepareStatement("EXPLAIN " + sql);
             ResultSet rs = ps.executeQuery()) {
            printResultSet(rs);
        }
        System.out.println("\n--- 为什么这样执行 ---");
        System.out.println(why);
    }

    /** Print all rows of a ResultSet in a clean column-aligned table. */
    private static void printResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        String[] cols = new String[n];
        int[] widths = new int[n];
        for (int i = 0; i < n; i++) {
            cols[i] = md.getColumnLabel(i + 1);
            widths[i] = Math.max(cols[i].length(), 8);
        }
        List<String[]> rows = new ArrayList<>();
        while (rs.next()) {
            String[] row = new String[n];
            for (int i = 0; i < n; i++) {
                String v = rs.getString(i + 1);
                row[i] = v == null ? "NULL" : v;
                widths[i] = Math.max(widths[i], row[i].length());
            }
            rows.add(row);
        }
        // 打印表头
        StringBuilder sep = new StringBuilder();
        for (int i = 0; i < n; i++) {
            System.out.printf("%-" + widths[i] + "s  ", cols[i]);
            sep.append("─".repeat(widths[i] + 2));
        }
        System.out.println();
        System.out.println(sep);
        for (String[] row : rows) {
            for (int i = 0; i < n; i++) {
                System.out.printf("%-" + widths[i] + "s  ", row[i]);
            }
            System.out.println();
        }
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 3) + "...";
    }

    /** Tiny thread-local-ish trick: re-open a quick connection for EXPLAIN so we don't share state. */
    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }
}
