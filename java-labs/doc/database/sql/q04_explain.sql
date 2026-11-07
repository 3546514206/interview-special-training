-- =====================================================================
-- q04_explain.sql  EXPLAIN 解读 + SQL 优化
-- 配套答卷: Q18-Q20
-- =====================================================================

USE interview_lab;

-- =====================================================================
-- 1. EXPLAIN 基础 — 看 type / key / rows / Extra
-- =====================================================================
-- ✅ const: 主键等值
EXPLAIN SELECT * FROM users WHERE id = 1;

-- ✅ ref: 非唯一索引
EXPLAIN SELECT * FROM users WHERE name = '张伟';

-- ✅ range: 范围
EXPLAIN SELECT * FROM users WHERE id BETWEEN 1 AND 10;

-- ⚠️ index: 全索引扫(比 ALL 稍好)
EXPLAIN SELECT id FROM users ORDER BY create_time;

-- ❌ ALL: 全表扫
EXPLAIN SELECT * FROM users WHERE age = 25;

-- =====================================================================
-- 2. Extra 关键值
-- =====================================================================
-- ✅ Using index: 覆盖索引
EXPLAIN SELECT id, name FROM users WHERE name = '张伟';

-- ❌ Using filesort: 排序走文件
EXPLAIN SELECT * FROM users ORDER BY age;

-- ❌ Using temporary: 用临时表
EXPLAIN SELECT gender, COUNT(*) FROM users GROUP BY gender, age;

-- ✅ Using where: server 层过滤(无索引可用)
EXPLAIN SELECT * FROM users WHERE age > 25;

-- =====================================================================
-- 3. 联合索引 key_len 计算
-- =====================================================================
-- idx_name_age = name(50*4+2=202) + age(4) = 206
-- 只用 name: 202; 用 name+age: 206
EXPLAIN SELECT * FROM users WHERE name = '张伟' AND age = 25;

-- =====================================================================
-- 4. 深分页优化
-- =====================================================================
-- ❌ 慢: LIMIT 1000, 10 要扫前 1010 行再丢 1000
EXPLAIN SELECT * FROM users ORDER BY id LIMIT 100, 10;

-- ✅ 延迟关联: 子查询只查 id (覆盖主键索引),JOIN 回表只 10 次
EXPLAIN SELECT u.* FROM users u
JOIN (SELECT id FROM users ORDER BY id LIMIT 100, 10) t
ON u.id = t.id;

-- ✅ 更好: 主键范围定位 (前提 id 连续)
EXPLAIN SELECT * FROM users WHERE id > 100 ORDER BY id LIMIT 10;

-- =====================================================================
-- 5. SQL 优化 10 条实战
-- =====================================================================
-- 5.1 不查 SELECT *
-- ❌
EXPLAIN SELECT * FROM users WHERE id = 1;
-- ✅
EXPLAIN SELECT id, name, age FROM users WHERE id = 1;

-- 5.2 命中索引 (见 q01_index.sql)

-- 5.3 避免函数
-- ❌
EXPLAIN SELECT * FROM users WHERE DATE(create_time) = '2026-10-01';
-- ✅
EXPLAIN SELECT * FROM users WHERE create_time >= '2026-10-01' AND create_time < '2026-10-02';

-- 5.4 小表驱动大表 (JOIN 顺序: orders 小表 JOIN users 大表)
EXPLAIN SELECT o.*, u.name FROM orders o JOIN users u ON o.user_id = u.id;

-- 5.5 索引覆盖
EXPLAIN SELECT user_id, COUNT(*) FROM login_logs GROUP BY user_id;

-- =====================================================================
-- 6. 慢查询日志 (运维)
-- =====================================================================
-- 查看配置
SHOW VARIABLES LIKE 'slow_query%';
SHOW VARIABLES LIKE 'long_query_time';

-- 临时开启
SET GLOBAL slow_query_log = ON;
SET GLOBAL long_query_time = 1;  -- 1 秒
SET GLOBAL log_queries_not_using_indexes = ON;

-- 关闭
SET GLOBAL slow_query_log = OFF;

-- 永久开启: 写 my.cnf
-- [mysqld]
-- slow_query_log = ON
-- slow_query_log_file = /var/log/mysql/slow.log
-- long_query_time = 1
-- log_queries_not_using_indexes = ON