-- =====================================================================
-- q02_transaction.sql  事务练习: 4 个隔离级别 / MVCC / 幻读 / 快照读 vs 当前读
-- 配套答卷: Q9-Q12
-- 用法: 在 interview_lab 库执行 00_init.sql 后,本文件所有 SQL 都在该库内执行
--      多事务演示需要在两个 mysql 客户端分别执行 BEGIN / COMMIT
-- =====================================================================

USE interview_lab;

-- =====================================================================
-- 1. 查看/修改当前会话隔离级别
-- =====================================================================
SELECT @@transaction_isolation;   -- 默认 REPEATABLE-READ

SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
SELECT @@transaction_isolation;   -- 已切到 RC

-- 改回默认 RR
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
SELECT @@transaction_isolation;

-- =====================================================================
-- 2. 脏读演示 — 必须在 RU 级别下,事务 A 未提交事务 B 能读到
-- =====================================================================
-- 客户端 A: BEGIN; UPDATE users SET status=0 WHERE id=1; (不 COMMIT)
-- 客户端 B (RU): SELECT * FROM users WHERE id=1;  -- 读到 status=0 (脏)
-- 客户端 A: ROLLBACK;
-- 客户端 B: 再 SELECT 又恢复到 status=1
-- ⚠️ 注意:RC 及以上隔离级别不会脏读

-- =====================================================================
-- 3. 不可重复读演示 — RC 级别下同事务两次读值不同
-- =====================================================================
-- 客户端 A (RC): BEGIN; SELECT age FROM users WHERE id=1;  -- 假设 25
-- 客户端 B:     UPDATE users SET age=30 WHERE id=1; COMMIT;
-- 客户端 A:     SELECT age FROM users WHERE id=1;  -- 看到 30 (不可重复读)
-- 客户端 A:     COMMIT;

-- RR 下客户端 A 两次读都是 25 (MVCC 同一 Read View)

-- =====================================================================
-- 4. 幻读演示 — RR 下当前读仍可能边界幻读
-- =====================================================================
-- 客户端 A (RR): BEGIN;
-- 客户端 A: SELECT * FROM users WHERE age BETWEEN 20 AND 25;
--   -- 假设有 5 行
-- 客户端 B: INSERT INTO users(name, phone, age) VALUES('测试','13999999999',22); COMMIT;
-- 客户端 A: UPDATE users SET status=0 WHERE age BETWEEN 20 AND 25;
--   -- 当前读:发现 6 行被影响 (幻读)
-- 客户端 A: SELECT * FROM users WHERE age BETWEEN 20 AND 25;
--   -- 快照读:还是 5 行 (但实际有 6 行 status=0)
-- 客户端 A: COMMIT;

-- =====================================================================
-- 5. MVCC 核心 — 看 Undo 版本链(简化,实际是行隐藏字段)
-- =====================================================================
-- 验证当前隔离级别生成 Read View 的时机差异
-- RC 下每次 SELECT 都生成新 Read View → 能看到其他事务最新提交
-- RR 下事务内一个 Read View → 整个事务一致快照
SELECT @@transaction_isolation;

-- 查看自己事务 ID (理论值,实际要看 processlist)
SELECT CONNECTION_ID() AS my_thread_id;

-- =====================================================================
-- 6. 实战:用窗口函数验证"连续登录"是不是真连续(给 Q25 铺路)
-- =====================================================================
-- 同一用户 login_date 排序后,date - row_number 的差值,在连续段内是常量
WITH ranked AS (
    SELECT user_id, login_date,
           ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY login_date) AS rn
    FROM login_logs
),
grouped AS (
    SELECT user_id, login_date,
           DATE_SUB(login_date, INTERVAL rn DAY) AS grp
    FROM ranked
)
SELECT user_id,
       MIN(login_date) AS start_date,
       MAX(login_date) AS end_date,
       COUNT(*)        AS continuous_days
FROM grouped
GROUP BY user_id, grp
ORDER BY user_id, start_date;