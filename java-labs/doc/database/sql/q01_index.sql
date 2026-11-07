-- =====================================================================
-- q01_index.sql  索引练习: B+树 / 聚簇 / 最左前缀 / 覆盖 / 失效
-- 配套答卷: Q4-Q8
-- 用法: 在 interview_lab 库执行 00_init.sql 后,逐条执行下面 SQL,观察 EXPLAIN 输出
-- =====================================================================

USE interview_lab;

-- =====================================================================
-- 1. 聚簇 vs 非聚簇 — InnoDB 主键索引就是聚簇索引
-- =====================================================================
-- 主键查询,直接走聚簇索引,叶子 = 行数据
EXPLAIN SELECT * FROM users WHERE id = 1;

-- 二级索引查询,先走 idx_name_age 取主键,再回表
EXPLAIN SELECT * FROM users WHERE name = '张伟';

-- 覆盖索引:只查 name, age (都在 idx_name_age 里),不回表
EXPLAIN SELECT name, age FROM users WHERE name = '张伟';

-- =====================================================================
-- 2. 最左前缀 — idx_name_age (name, age) 联合索引
-- =====================================================================
-- ✅ 走索引: 用 name
EXPLAIN SELECT * FROM users WHERE name = '张伟';

-- ✅ 走索引: 用 name + age
EXPLAIN SELECT * FROM users WHERE name = '张伟' AND age = 25;

-- ❌ 跳过 name,全表扫
EXPLAIN SELECT * FROM users WHERE age = 25;

-- ⚠️ 只用 name,跳过 age;c 不走索引(本例中只有 name/age)
EXPLAIN SELECT * FROM users WHERE name = '张伟' AND age > 20;

-- =====================================================================
-- 3. 索引失效 7 大场景
-- =====================================================================
-- 3.1 函数运算
EXPLAIN SELECT * FROM users WHERE DATE(create_time) = '2026-01-05';
-- ✅ 修复: 改范围
EXPLAIN SELECT * FROM users WHERE create_time BETWEEN '2026-01-05 00:00:00' AND '2026-01-05 23:59:59';

-- 3.2 表达式计算
EXPLAIN SELECT * FROM users WHERE age + 10 = 35;
-- ✅ 修复
EXPLAIN SELECT * FROM users WHERE age = 25;

-- 3.3 隐式类型转换 (phone 是 varchar)
EXPLAIN SELECT * FROM users WHERE phone = 13800000001;
-- ✅ 修复: 加引号
EXPLAIN SELECT * FROM users WHERE phone = '13800000001';

-- 3.4 左模糊
EXPLAIN SELECT * FROM users WHERE name LIKE '%伟';
-- ✅ 修复: 右模糊
EXPLAIN SELECT * FROM users WHERE name LIKE '张%';

-- 3.5 OR 两侧非索引列
EXPLAIN SELECT * FROM users WHERE id = 1 OR age = 25;

-- 3.6 不等号 (大多数情况)
EXPLAIN SELECT * FROM users WHERE age != 25;

-- 3.7 IS NULL
EXPLAIN SELECT * FROM users WHERE name IS NULL;

-- =====================================================================
-- 4. 验证 key_len 看走了几个索引列
-- =====================================================================
-- idx_name_age: name(varchar 50 utf8mb4=200B+2 长度) + age(int=4) = 206
EXPLAIN SELECT * FROM users WHERE name = '张伟' AND age = 25;

-- 只有 name: key_len = 202
EXPLAIN SELECT * FROM users WHERE name = '张伟';

-- =====================================================================
-- 5. 为什么推荐自增主键 — 演示 UUID vs 自增插入性能差异(概念演示)
-- =====================================================================
-- 自增主键:总是追加到 B+ 树末尾,无页分裂
-- UUID 主键:随机插入,频繁页分裂,写放大
-- 结论:看 EXPLAIN 的 Extra 不够直观,生产用 sysbench 压测对比;
--      关注 innodb_page_splits 状态变量增长
SHOW GLOBAL STATUS LIKE 'Innodb_page_splits';