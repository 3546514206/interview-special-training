-- =====================================================================
-- q05_back_to_table.sql  数据库面试 Q5/Q6 实验:回表 vs 覆盖索引 vs 全表扫
-- 配套:edu.zjnu.interview.database.q05.BackToTableLab
-- 配套答卷:Q5 (聚簇 vs 二级索引) / Q6 (覆盖索引) / Q18 (EXPLAIN)
--
-- 跑法:
--   docker exec -i <mysql-container> mysql --default-character-set=utf8mb4 \
--     -uroot -p"$DB_PASSWORD" interview_lab < q05_back_to_table.sql
--
-- ⚠️ 100 万行 seed 大约需要 30-60 秒(单线程 INSERT),请耐心等.
--     如果表已经存在,DROP 后重建(脚本头部已经 IF EXISTS).
-- =====================================================================

SET NAMES utf8mb4;
USE interview_lab;

-- =====================================================================
-- 1. 建表:100 万行 user_big(id 主键,age 有二级索引)
-- =====================================================================
DROP TABLE IF EXISTS user_big;
CREATE TABLE user_big (
    id      BIGINT NOT NULL AUTO_INCREMENT,
    name    VARCHAR(50) NOT NULL,
    age     INT NOT NULL,
    city    VARCHAR(20) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_age (age)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- 2. 灌数据:100 万行,age 均匀分布在 20~60(共 41 个值)
--    → age > 20 命中约 100万 × (40/41) ≈ 97.5 万行(约等于"99 万行")
--    → age = 25 命中约 100万 / 41 ≈ 24400 行
-- =====================================================================
-- 用递归 CTE 批量 INSERT,比逐行快 100 倍.
SET cte_max_recursion_depth = 1000000;

INSERT INTO user_big (name, age, city)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 1000000
)
SELECT
    CONCAT('user_', n),
    20 + (n MOD 41),          -- 20, 21, 22, ..., 60 循环
    CASE (n MOD 5)
        WHEN 0 THEN '北京'
        WHEN 1 THEN '上海'
        WHEN 2 THEN '广州'
        WHEN 3 THEN '深圳'
        ELSE '杭州'
    END
FROM seq;

-- 关闭递归 CTE
SET SESSION cte_max_recursion_depth = 1000;

-- =====================================================================
-- 3. 验证数据:看 age 分布,确认符合"20~60 均匀"
-- =====================================================================
SELECT age, COUNT(*) AS cnt
FROM user_big
GROUP BY age
ORDER BY age;

SELECT
    COUNT(*)                                       AS total,
    SUM(IF(age > 20, 1, 0))                         AS age_gt_20,
    SUM(IF(age = 25, 1, 0))                         AS age_eq_25,
    ROUND(SUM(IF(age > 20, 1, 0)) * 100.0 / COUNT(*), 1) AS age_gt_20_pct
FROM user_big;

-- =====================================================================
-- 4. 4 个核心实验:把回表 / 覆盖索引 / 全表扫讲清楚
--    每条都跑 EXPLAIN + 实际执行,看 type/key/rows/Extra
-- =====================================================================

-- 实验 1:SELECT * + 范围 age > 20(命中约 97.5 万行)
-- 预期:type=ALL, key=NULL, rows≈975000, Extra=Using where
-- 原因:age 索引回表 97 万次 > 全表扫 100 万次顺序 I/O → 优化器放弃二级索引
SELECT '---- 实验 1:SELECT * WHERE age > 20 (高选择性 → 全表扫) ----' AS info;
EXPLAIN SELECT * FROM user_big WHERE age > 20\G

-- 实验 2:SELECT * + 等值 age = 25(命中约 24400 行)
-- 预期:type=ref, key=idx_age, rows≈24400, Extra=Using where(回表)
-- 原因:2.4 万次回表 < 全表扫 100 万次顺序 I/O → 走 idx_age + 回表
SELECT '---- 实验 2:SELECT * WHERE age = 25 (中等选择性 → 索引 + 回表) ----' AS info;
EXPLAIN SELECT * FROM user_big WHERE age = 25\G

-- 实验 3:SELECT id, age + 等值 age = 25(覆盖索引)
-- 预期:type=ref, key=idx_age, rows≈24400, Extra=Using index
-- 原因:查询列 (id, age) 都在 idx_age 叶子 → 0 次回表
SELECT '---- 实验 3:SELECT id,age WHERE age = 25 (覆盖索引 → 不回表) ----' AS info;
EXPLAIN SELECT id, age FROM user_big WHERE age = 25\G

-- 实验 4:SELECT * + WHERE id = 12345(走主键,无 WHERE 索引回表)
-- 预期:type=const, key=PRIMARY, rows=1, Extra=NULL
-- 原因:id 是主键,主键叶子就是完整行,直接拿
SELECT '---- 实验 4:SELECT * WHERE id = 12345 (走主键) ----' AS info;
EXPLAIN SELECT * FROM user_big WHERE id = 12345\G

-- =====================================================================
-- 5. 实测耗时对比:看"理论分析"和"实测数据"的对应关系
-- =====================================================================
SELECT '---- 耗时对比 (5 次平均) ----' AS info;

-- 实验 1 耗时:age > 20 范围扫
SELECT BENCHMARK(1, (SELECT COUNT(*) FROM user_big WHERE age > 20)) INTO @dummy;
-- 注:BENCHMARK 不返回时间,只是热身.实际用 SQL_NO_CACHE + profiling 看.

-- 实际用 SET profiling + SHOW PROFILES 拿耗时
SET profiling = 1;

SELECT COUNT(*) FROM user_big WHERE age > 20;          -- 全表扫
SELECT COUNT(*) FROM user_big WHERE age = 25;          -- idx_age + 回表
SELECT COUNT(*) FROM user_big WHERE age = 25
    AND id > 100000;                                   -- idx_age + 回表 + WHERE
SELECT id, age FROM user_big WHERE age = 25;           -- 覆盖索引
SELECT * FROM user_big WHERE id = 12345;               -- 主键

SHOW PROFILES;

SET profiling = 0;

-- =====================================================================
-- 6. 关键观察:覆盖索引 vs 回表的差距
-- =====================================================================
-- 用 SQL_NO_CACHE 避免 query cache 干扰
SELECT SQL_NO_CACHE COUNT(*) FROM user_big WHERE age = 25;            -- 回表取 COUNT
SELECT SQL_NO_CACHE COUNT(*) FROM user_big WHERE id IN
    (SELECT id FROM user_big WHERE age = 25);                          -- 子查询 = 覆盖索引

-- =====================================================================
-- 7. EXPLAIN FORMAT=JSON:看更详细的成本估算
-- =====================================================================
EXPLAIN FORMAT=JSON SELECT * FROM user_big WHERE age > 20\G
EXPLAIN FORMAT=JSON SELECT * FROM user_big WHERE age = 25\G
EXPLAIN FORMAT=JSON SELECT id, age FROM user_big WHERE age = 25\G
EXPLAIN FORMAT=JSON SELECT * FROM user_big WHERE id = 12345\G

-- =====================================================================
-- 8. 验证"key=NULL 不等于没读 PK 树"——全表扫就是顺序遍历聚簇索引
--    用 OPTIMIZER_TRACE 看优化器实际选择的访问路径
-- =====================================================================
SET optimizer_trace = 'enabled=on';
SELECT * FROM user_big WHERE age > 20 LIMIT 1;
SELECT * FROM information_schema.OPTIMIZER_TRACE\G
SET optimizer_trace = 'enabled=off';

-- =====================================================================
-- 9. 索引统计:看 idx_age 的 cardinality(区分度)
-- =====================================================================
ANALYZE TABLE user_big;
SHOW INDEX FROM user_big;
SELECT
    INDEX_NAME,
    COLUMN_NAME,
    CARDINALITY,
    ROUND(CARDINALITY / (SELECT COUNT(*) FROM user_big) * 100, 1) AS pct
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'interview_lab'
  AND TABLE_NAME = 'user_big';

-- =====================================================================
-- 10. 清理(可选,生产别用)
-- =====================================================================
-- DROP TABLE IF EXISTS user_big;
