-- =====================================================================
-- q09_logs.sql  MySQL 日志体系实验
-- 配套: doc/database/logs/lab-guide.md / interview-questions.md
-- 适用: MySQL 8.0+；先在 interview_lab 库执行 00_init.sql
-- 权限: 需要 SUPER / SYSTEM_VARIABLES_ADMIN 切换全局日志开关
--
-- 注意:
--   1. general_log / slow_query_log 打开的瞬间会产生额外 I/O，生产慎用
--   2. 脚本末尾会把开关恢复到安全默认值；如果你希望继续观察，可注释掉末尾
--   3. 标记 [SHELL] 的步骤需要在操作系统 shell 里执行，不是 SQL
-- =====================================================================

USE interview_lab;

-- =====================================================================
-- 0. 当前日志状态
-- =====================================================================
SELECT VERSION() AS version;

SELECT @@log_bin                    AS log_bin,
       @@binlog_format              AS binlog_format,
       @@binlog_row_image           AS row_image,
       @@sync_binlog                AS sync_binlog,
       @@innodb_flush_log_at_trx_commit AS flush_at_commit,
       @@general_log                AS general_log,
       @@slow_query_log             AS slow_query_log,
       @@log_output                 AS log_output,
       @@long_query_time            AS long_query_time,
       @@log_error                  AS error_log,
       @@log_error_verbosity        AS verbosity,
       @@innodb_redo_log_capacity/1024/1024 AS redo_mb;

SHOW BINARY LOGS;
SHOW MASTER STATUS\G
SHOW VARIABLES LIKE 'datadir';


-- =====================================================================
-- 1. 准备实验表
-- =====================================================================
CREATE TABLE IF NOT EXISTS log_lab (
  id  INT PRIMARY KEY,
  val VARCHAR(32)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS redo_lab (
  id INT AUTO_INCREMENT PRIMARY KEY,
  v  VARCHAR(64)
) ENGINE=InnoDB;


-- =====================================================================
-- 2. error log
--    目标: 验证 log_error_verbosity=3 才会记录登录失败
-- =====================================================================
SET GLOBAL log_error_verbosity = 3;

-- [SHELL] 另开终端制造一次失败登录:
--   mysql -u no_such_user -pwrong_password -h 127.0.0.1
-- [SHELL] 然后查看:
--   sudo tail -n 5 /var/log/mysql/error.log
-- 预期: [Note] [MY-010926] Access denied for user ...

-- 恢复默认
SET GLOBAL log_error_verbosity = 2;


-- =====================================================================
-- 3. general query log
--    目标: 看清楚客户端到底发了什么；观察 Connect / Query / Quit
-- =====================================================================
SET GLOBAL general_log = OFF;          -- 先关，才能安全 TRUNCATE
SET GLOBAL log_output  = 'TABLE';
TRUNCATE TABLE mysql.general_log;
SET GLOBAL general_log = ON;

SELECT 1 AS general_log_demo;
INSERT INTO log_lab (id, val) VALUES (1, 'hello')
  ON DUPLICATE KEY UPDATE val = VALUES(val);
SELECT * FROM log_lab;

SET GLOBAL general_log = OFF;

SELECT event_time, command_type, argument
FROM mysql.general_log
ORDER BY event_time DESC
LIMIT 10;

-- 恢复文件输出
SET GLOBAL log_output = 'FILE';


-- =====================================================================
-- 4. slow query log
--    目标: 验证 long_query_time 是会话级变量
-- =====================================================================
SET GLOBAL slow_query_log = OFF;       -- 先关，才能安全 TRUNCATE
SET GLOBAL log_output     = 'TABLE';
TRUNCATE TABLE mysql.slow_log;
SET GLOBAL slow_query_log = ON;
SET GLOBAL long_query_time = 0;

-- 当前会话仍然沿用连接建立时的值（通常是 10）
SELECT @@long_query_time AS session_long_qt;
SELECT SLEEP(0.5);
SELECT COUNT(*) AS slow_rows_without_session AS rows_should_be_0
FROM mysql.slow_log;

-- 关键: 同时设置 SESSION，当前会话才生效
SET SESSION long_query_time = 0;
SELECT SLEEP(0.5);

SELECT COUNT(*) AS slow_rows_with_session AS rows_should_be_positive
FROM mysql.slow_log;

SELECT start_time, query_time, lock_time, rows_examined, rows_sent, sql_text
FROM mysql.slow_log
ORDER BY start_time DESC
LIMIT 5;

-- 恢复
SET SESSION long_query_time = 10;
SET GLOBAL slow_query_log = OFF;
SET GLOBAL long_query_time = 10;
SET GLOBAL log_output = 'FILE';


-- =====================================================================
-- 5. binlog
--    目标: 观察 ROW 格式的事件链和前/后镜像
-- =====================================================================
FLUSH BINARY LOGS;
SHOW MASTER STATUS\G
-- 记下 File（例如 binlog.000003）和 Position

START TRANSACTION;
INSERT INTO log_lab (id, val) VALUES (2, 'before')
  ON DUPLICATE KEY UPDATE val = 'before';
UPDATE log_lab SET val = 'after' WHERE id = 2;
DELETE FROM log_lab WHERE id = 2;
COMMIT;

-- 把 'binlog.000003' 换成上面 SHOW MASTER STATUS 返回的 File
-- SHOW BINLOG EVENTS IN 'binlog.000003';

-- [SHELL] 解码 ROW 事件（路径按 @@datadir 替换）:
--   sudo mysqlbinlog --base64-output=DECODE-ROWS -v \
--     /var/lib/mysql/binlog.000003 | tail -60


-- =====================================================================
-- 6. redo log
--    目标: 观察提交后 Log flushed up to 追平 LSN，
--          但 Pages flushed up to / Last checkpoint 仍然落后
-- =====================================================================
-- 执行前先记录 SHOW ENGINE INNODB STATUS 的 LOG 段
SHOW ENGINE INNODB STATUS\G

START TRANSACTION;
INSERT INTO redo_lab(v)
WITH RECURSIVE seq AS (
  SELECT 1 AS n
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < 1000
)
SELECT CONCAT('row-', n) FROM seq;
COMMIT;

-- 执行后再看一次 LOG 段，对比 LSN
SHOW ENGINE INNODB STATUS\G

-- 可选: 对比刷盘参数（实验后恢复为 1）
-- SET GLOBAL innodb_flush_log_at_trx_commit = 2;
-- SET GLOBAL innodb_flush_log_at_trx_commit = 1;


-- =====================================================================
-- 7. undo log 与 MVCC
--    目标: 未提交修改对其他会话不可见；回滚靠 undo
-- =====================================================================
INSERT INTO log_lab (id, val) VALUES (10, 'v1')
  ON DUPLICATE KEY UPDATE val = 'v1';

-- 会话 A（另一个连接，保持 25 秒不提交）:
--   START TRANSACTION;
--   UPDATE log_lab SET val = 'v2' WHERE id = 10;
--   SELECT SLEEP(25);
--   ROLLBACK;

-- 会话 B（当前连接）:
-- 1) 应该读到 v1
SELECT val AS should_be_v1 FROM log_lab WHERE id = 10;

-- 2) 事务
SELECT trx_id, trx_state, trx_isolation_level,
       trx_rows_modified, trx_started, trx_query
FROM information_schema.innodb_trx\G

-- 3) 锁（MySQL 8.0+）
SELECT ENGINE_TRANSACTION_ID, OBJECT_SCHEMA, OBJECT_NAME,
       INDEX_NAME, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA
FROM performance_schema.data_locks\G

-- 4) undo tablespace
SELECT name, space_type, file_size/1024/1024 AS size_mb, state
FROM information_schema.INNODB_TABLESPACES
WHERE space_type = 'UNDO';

-- 5) History list length / undo log entries
SHOW ENGINE INNODB STATUS\G

-- 会话 A 回滚后再读，仍然是 v1
SELECT val AS after_rollback FROM log_lab WHERE id = 10;


-- =====================================================================
-- 8. relay log（只在从库执行）
-- =====================================================================
-- SHOW REPLICA STATUS\G
--   -- 关注 Replica_IO_Running / Replica_SQL_Running
--   --      Relay_Log_File / Relay_Log_Pos / Relay_Source_Log_File
--   --      Seconds_Behind_Source
--
-- SHOW PROCESSLIST;
--   -- 应看到 system user 的 IO / SQL 线程
--
-- SHOW RELAYLOG EVENTS IN 'relay-bin.000002';
--
-- 可选: 暂停 SQL 线程观察 relay log 堆积
-- STOP REPLICA SQL_THREAD;
-- -- 在主库写入若干行
-- SHOW REPLICA STATUS\G
-- START REPLICA SQL_THREAD;
--
-- MySQL 5.7 使用 SHOW SLAVE STATUS / STOP SLAVE SQL_THREAD。


-- =====================================================================
-- 9. 恢复默认开关（按需注释）
-- =====================================================================
SET GLOBAL general_log = OFF;
SET GLOBAL slow_query_log = OFF;
SET GLOBAL log_output = 'FILE';
SET GLOBAL long_query_time = 10;
SET SESSION long_query_time = 10;
SET GLOBAL log_error_verbosity = 2;

-- 可选清理实验表：
-- DROP TABLE IF EXISTS log_lab;
-- DROP TABLE IF EXISTS redo_lab;
