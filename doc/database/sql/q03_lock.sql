-- =====================================================================
-- q03_lock.sql  锁练习: 行锁 / 表锁 / 间隙锁 / 当前读 vs 快照读
-- 配套答卷: Q13-Q15
-- 用法: 多事务演示必须在两个 mysql 客户端分别执行
-- =====================================================================

USE interview_lab;

-- =====================================================================
-- 1. 行锁 vs 表锁 — 关键:WHERE 走索引才是行锁
-- =====================================================================
-- ✅ 行锁: UPDATE 走主键索引
EXPLAIN UPDATE users SET age=26 WHERE id=1;
-- 客户端 A: BEGIN; UPDATE users SET age=26 WHERE id=1; (不 COMMIT)
-- 客户端 B: UPDATE users SET age=27 WHERE id=2; -- 不冲突,id 不同,正常执行
-- 客户端 B: UPDATE users SET age=28 WHERE id=1; -- 阻塞,等 A 提交/回滚

-- ❌ 表锁: UPDATE 没走索引(age 没有索引在 users 上),升级为表锁
EXPLAIN UPDATE users SET age=26 WHERE age=25;
-- 客户端 A: BEGIN; UPDATE users SET age=26 WHERE age=25; (不 COMMIT)
-- 客户端 B: UPDATE users SET name='新名' WHERE id=2; -- 阻塞! 表锁

-- =====================================================================
-- 2. 当前读 vs 快照读
-- =====================================================================
-- 客户端 A (RR): BEGIN;
-- 客户端 A: SELECT * FROM users WHERE id=1; -- 快照读,读 Read View
-- 客户端 B: UPDATE users SET age=99 WHERE id=1; COMMIT;
-- 客户端 A: SELECT * FROM users WHERE id=1; -- 快照读,age 还是旧值
-- 客户端 A: SELECT * FROM users WHERE id=1 FOR UPDATE; -- 当前读,age=99
-- 客户端 A: COMMIT;

-- =====================================================================
-- 3. 间隙锁 (RR 才有)
-- =====================================================================
-- 表 users 上没有 phone 的范围索引,这里用 phone 的范围演示间隙锁
-- 先建索引(phone 已经是 unique key)
EXPLAIN SELECT * FROM users WHERE phone BETWEEN '13800000001' AND '13800000005' FOR UPDATE;

-- 客户端 A (RR): BEGIN;
-- 客户端 A: SELECT * FROM users WHERE phone='13800000010' FOR UPDATE;
--   -- 唯一索引等值,降级为记录锁,只锁 id=10 的行
-- 客户端 B: UPDATE users SET status=0 WHERE phone='13800000010'; -- 阻塞
-- 客户端 B: UPDATE users SET status=0 WHERE phone='13800000011'; -- 正常,不同行

-- 范围查询 → 临键锁(行锁+间隙锁)
-- 客户端 A: SELECT * FROM users WHERE phone BETWEEN '13800000005' AND '13800000015' FOR UPDATE;
--   -- 锁 (5, 15] 区间内的所有记录 + 间隙
-- 客户端 B: INSERT INTO users(name, phone, age) VALUES('测试', '13800000008', 30);
--   -- 阻塞! phone=8 在 (5,15] 区间内,间隙锁阻止 INSERT

-- =====================================================================
-- 4. 查看锁等待 — 监控死锁/锁竞争
-- =====================================================================
SELECT * FROM performance_schema.data_locks LIMIT 10;
SELECT * FROM performance_schema.data_lock_waits LIMIT 10;

-- 看当前活跃事务
SELECT trx_id, trx_state, trx_started, trx_mysql_thread_id
FROM information_schema.innodb_trx;

-- =====================================================================
-- 5. 共享锁 vs 排他锁
-- =====================================================================
-- 客户端 A: BEGIN; SELECT * FROM users WHERE id=1 LOCK IN SHARE MODE;
--   -- S 锁,自己和其他事务都能读,不能改
-- 客户端 B: SELECT * FROM users WHERE id=1; -- OK,快照读不走 S/X
-- 客户端 B: UPDATE users SET age=99 WHERE id=1; -- 阻塞,等 A COMMIT/ROLLBACK
-- 客户端 A: COMMIT;

-- 排他锁
-- 客户端 A: BEGIN; SELECT * FROM users WHERE id=1 FOR UPDATE;
--   -- X 锁,自己独占
-- 客户端 B: SELECT * FROM users WHERE id=1 LOCK IN SHARE MODE; -- 阻塞
-- 客户端 B: SELECT * FROM users WHERE id=1 FOR UPDATE; -- 阻塞
-- 客户端 B: UPDATE users SET age=99 WHERE id=1; -- 阻塞
-- 客户端 A: COMMIT;

-- =====================================================================
-- 6. 死锁模拟 (供 Java Lab 验证)
-- =====================================================================
-- 客户端 A: BEGIN; UPDATE users SET age=99 WHERE id=1;
-- 客户端 B: BEGIN; UPDATE users SET age=99 WHERE id=2;
-- 客户端 A: UPDATE users SET age=99 WHERE id=2; -- 阻塞,等 B
-- 客户端 B: UPDATE users SET age=99 WHERE id=1; -- 死锁!MySQL 自动回滚一个事务
-- 看错误: ERROR 1213 (40001): Deadlock found when trying to get lock