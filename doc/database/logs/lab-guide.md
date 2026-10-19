# MySQL 日志体系实验教程

> 配套：[interview-questions.md](./interview-questions.md)、[../sql/q09_logs.sql](../sql/q09_logs.sql)
>
> 目标：每个实验都能亲眼看到「日志在哪里、什么时候写、写了什么、怎么用」。
>
> ⚠️ **安全第一**：`kill -9`、`general_log=ON`、`long_query_time=0` 只在一台可随意折腾的实验实例上做，不要在正式库上执行。

## 实验环境

- MySQL **8.0+**（部分命令需要 8.0.22+）
- 练习库：`interview_lab`，先执行 [`../sql/00_init.sql`](../sql/00_init.sql)
- 连接方式（按你的环境替换）：
  ```bash
  mysql -h <mysql-host> -P <mysql-port> -u <user> -p
  ```
- 需要 `SUPER` 或 `SYSTEM_VARIABLES_ADMIN` 权限切换全局日志开关
- 实验 7 需要一台从库；没有从库可以先跳过

---

## Lab 0 · 先看清当前状态

**目标**：确认版本、日志开关、文件位置。

```sql
SELECT VERSION();

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
```

**预期（常见实验环境）**

| 变量 | 常见值 | 说明 |
|---|---|---|
| `log_bin` | `1` | 8.0 默认开启 binlog |
| `binlog_format` | `ROW` | 推荐 |
| `binlog_row_image` | `FULL` | 记录完整前/后镜像 |
| `sync_binlog` | `1` | 每次提交 fsync |
| `flush_at_commit` | `1` | 每次提交 fsync redo |
| `general_log` | `0` | 默认关闭 |
| `slow_query_log` | `0` | 默认关闭 |
| `log_output` | `FILE` | 可切 `TABLE` 便于查询 |
| `long_query_time` | `10` | 默认 10 秒 |
| `verbosity` | `2` | 错误 + 警告 |

> `SHOW MASTER STATUS` 在 MySQL 8.4+ 改名为 `SHOW BINARY LOG STATUS`；本文以 8.0 为准。

**验证点**：你能指出 binlog、redo、undo、error log 分别落在哪个目录/文件。

---

## Lab 1 · error log：默认不记录登录失败

**目标**：验证 `log_error_verbosity` 对登录失败记录的影响。

### 步骤

1. 调到最高详细度：
   ```sql
   SET GLOBAL log_error_verbosity = 3;
   SELECT @@log_error;
   ```
2. 在 shell 里制造一次失败登录（不要用真实账号）：
   ```bash
   mysql -u no_such_user -pwrong_password -h 127.0.0.1
   ```
3. 查看 error log：
   ```bash
   sudo tail -n 5 /var/log/mysql/error.log
   ```
4. 恢复默认：
   ```sql
   SET GLOBAL log_error_verbosity = 2;
   ```

**预期**

```text
[Note] [MY-010926] [Server] Access denied for user 'no_such_user'@'localhost' (using password: YES)
```

**验证点**

- `verbosity=2` 时这条 `Access denied` **不会**出现；`verbosity=3` 才会记录。
- `verbosity` 含义：1 = errors，2 = errors + warnings（默认），3 = 再加 notes。

**面试追问**

- error log 里应该重点看什么？崩溃恢复、复制错误、权限/磁盘问题、认证失败。
- error log 怎么轮转？按文件大小/日志轮转工具，或 8.0 的 `log_error_services` 组合。

---

## Lab 2 · general log：看清楚客户端到底发了什么

**目标**：把 general log 输出到表，观察连接和语句事件。

### 步骤

```sql
-- 1. 输出到表（TABLE），并清空旧记录
SET GLOBAL log_output = 'TABLE';
TRUNCATE TABLE mysql.general_log;

-- 2. 打开 general log
SET GLOBAL general_log = ON;

-- 3. 制造一些语句
SELECT 1 AS demo;
CREATE TABLE IF NOT EXISTS interview_lab.log_lab (
  id INT PRIMARY KEY,
  val VARCHAR(32)
) ENGINE=InnoDB;
INSERT INTO interview_lab.log_lab VALUES (1, 'hello')
  ON DUPLICATE KEY UPDATE val = VALUES(val);
SELECT * FROM interview_lab.log_lab;

-- 4. 关闭并查看
SET GLOBAL general_log = OFF;
SELECT event_time, command_type, argument
FROM mysql.general_log
ORDER BY event_time DESC
LIMIT 10;

-- 5. 恢复文件输出（按需）
SET GLOBAL log_output = 'FILE';
```

**预期**

- 能看到 `Connect`、`Query`、`Quit` 等事件。
- 每条 SQL 原文都在 `argument` 里。
- `SET GLOBAL general_log = OFF` 这条语句本身也可能被记录（取决于关闭时机）。

**验证点**

- general log 记录「所有」客户端语句，不是只记慢的。
- 高并发下开销明显；生产只在排查时短开。

**面试追问**

- general log 能当审计日志吗？不建议；它不是防篡改审计系统。
- general log 和 slow log 的区别？一个记全部，一个只记超阈值。

---

## Lab 3 · slow log：最容易踩的会话级坑

**目标**：验证 `long_query_time` 是会话级变量，以及慢日志的输出字段。

### 步骤

```sql
-- 1. 输出到表，清空旧记录
SET GLOBAL log_output = 'TABLE';
TRUNCATE TABLE mysql.slow_log;

-- 2. 打开慢日志，全局阈值设为 0
SET GLOBAL slow_query_log = ON;
SET GLOBAL long_query_time = 0;

-- 3. 观察：当前会话的值仍然是 10
SELECT @@long_query_time;       -- 10（如果连接建立时全局是 10）
SELECT SLEEP(0.5);
SELECT COUNT(*) FROM mysql.slow_log;   -- 0：当前会话阈值还是 10 秒

-- 4. 关键：同时设置 SESSION
SET SESSION long_query_time = 0;
SELECT SLEEP(0.5);
SELECT COUNT(*) FROM mysql.slow_log;   -- >0

-- 5. 查看字段
SELECT start_time, query_time, lock_time, rows_examined, rows_sent, sql_text
FROM mysql.slow_log
ORDER BY start_time DESC
LIMIT 5;

-- 6. 恢复
SET SESSION long_query_time = 10;
SET GLOBAL slow_query_log = OFF;
SET GLOBAL long_query_time = 10;
SET GLOBAL log_output = 'FILE';
```

**预期**

```text
Query_time: 00:00:00.500xxx
Rows_examined: 1
sql_text: SELECT SLEEP(0.5)
```

**验证点**

- 只改 `SET GLOBAL long_query_time`，当前会话不会生效；必须 `SET SESSION` 或重连。
- 慢日志判定用的是**会话**的 `long_query_time`。

**面试追问**

- `log_slow_extra=ON` 多了哪些字段？（`Read_first`、`Read_key`、`Sort_rows` 等）
- `log_queries_not_using_indexes` 有什么用？可能产生大量日志。
- `mysqldumpslow` / `pt-query-digest` 怎么用？

---

## Lab 4 · binlog：看 ROW 格式的前后镜像

**目标**：制造一个事务，观察 binlog 事件链和行镜像。

### 步骤

```sql
-- 1. 切一个新 binlog，记录文件名和位点
FLUSH BINARY LOGS;
SHOW MASTER STATUS\G

-- 2. 准备表
USE interview_lab;
CREATE TABLE IF NOT EXISTS log_lab (
  id INT PRIMARY KEY,
  val VARCHAR(32)
) ENGINE=InnoDB;

-- 3. 一个事务里做 INSERT / UPDATE / DELETE
START TRANSACTION;
INSERT INTO log_lab VALUES (2, 'before')
  ON DUPLICATE KEY UPDATE val = 'before';
UPDATE log_lab SET val = 'after' WHERE id = 2;
DELETE FROM log_lab WHERE id = 2;
COMMIT;

-- 4. 查看事件（把 binlog.000003 换成上一步的 File）
SHOW BINLOG EVENTS IN 'binlog.000003';
```

然后在 shell 里解码（路径按 `@@datadir` 替换）：

```bash
sudo mysqlbinlog --base64-output=DECODE-ROWS -v \
  /var/lib/mysql/binlog.000003 | tail -60
```

**预期事件链**

```text
Format_desc
Previous_gtids
Anonymous_Gtid
Query              BEGIN
Table_map          interview_lab.log_lab
Write_rows         INSERT
Table_map
Update_rows        WHERE 前镜像 / SET 后镜像
Table_map
Delete_rows        WHERE 前镜像
Xid                COMMIT
```

**预期行镜像**

```text
### INSERT INTO `interview_lab`.`log_lab`
### SET
###   @1=2
###   @2='before'

### UPDATE `interview_lab`.`log_lab`
### WHERE
###   @1=2
###   @2='before'
### SET
###   @1=2
###   @2='after'

### DELETE FROM `interview_lab`.`log_lab`
### WHERE
###   @1=2
###   @2='after'
```

**验证点**

- ROW 格式记录的是行镜像，不是 SQL 原文。
- UPDATE/DELETE 有前镜像（WHERE），UPDATE 有后镜像（SET）。
- `Xid` 标记事务提交，是 2PC 判断 binlog 完整性的关键。

**面试追问**

- `binlog_row_image=FULL/MINIMAL/NOBLOB` 有什么区别？
- STATEMENT 为什么可能主从不一致？举一个非确定性函数的例子。
- 如何用 binlog 做 PITR？

---

## Lab 5 · redo log：WAL 与 LSN

**目标**：观察提交后 redo 已落盘、数据页还没刷盘的 WAL 现象。

### 步骤

1. 记录基线：
   ```sql
   SHOW ENGINE INNODB STATUS\G
   ```
   在输出中找到 `LOG` 段，记下：
   - `Log sequence number`
   - `Log flushed up to`
   - `Pages flushed up to`
   - `Last checkpoint at`

2. 跑一个 1000 行事务：
   ```sql
   USE interview_lab;
   CREATE TABLE IF NOT EXISTS redo_lab (
     id INT AUTO_INCREMENT PRIMARY KEY,
     v VARCHAR(64)
   ) ENGINE=InnoDB;

   START TRANSACTION;
   INSERT INTO redo_lab(v)
   WITH RECURSIVE seq AS (
     SELECT 1 AS n
     UNION ALL
     SELECT n + 1 FROM seq WHERE n < 1000
   )
   SELECT CONCAT('row-', n) FROM seq;
   COMMIT;
   ```

3. 再看一次：
   ```sql
   SHOW ENGINE INNODB STATUS\G
   ```

**预期**

```text
Log sequence number       246379632  ->  246481632   （+约 100KB）
Log flushed up to         246379632  ->  246481632   （提交后追平）
Pages flushed up to       246379632  ->  246379632   （数据页还没刷）
Last checkpoint at        246379632  ->  246379632
```

> 数值因环境而异，关键是 **`Log flushed up to` 追平 LSN，而 `Pages flushed up to` 落后**。

**可选：对比刷盘参数**

```sql
SET GLOBAL innodb_flush_log_at_trx_commit = 2;
-- 再跑一个小事务，观察 Log flushed up to 可能落后
SET GLOBAL innodb_flush_log_at_trx_commit = 1;
```

**可选：崩溃恢复（仅限可丢弃实例）**

```bash
# 制造已提交数据后，直接杀进程
sudo kill -9 $(pidof mysqld)
# 重新启动
sudo systemctl start mysql
# 看恢复日志
sudo grep -i "recovery" /var/log/mysql/error.log | tail
```

**验证点**

- redo 是 WAL：提交只需要 redo 落盘，数据页异步刷。
- `Log flushed up to` 和 `Pages flushed up to` 的差就是「已提交但还没刷数据页」的窗口。
- 崩溃后 InnoDB 从 checkpoint 开始重放 redo。

**面试追问**

- LSN、checkpoint、`Log flushed up to`、`Pages flushed up to` 分别是什么？
- `innodb_flush_log_at_trx_commit=0/1/2` 的丢失窗口？
- redo 满了会怎样？

---

## Lab 6 · undo log 与 MVCC：两个会话看旧版本

**目标**：验证未提交修改对其他会话不可见、回滚靠 undo、MVCC 版本链。

### 准备

```sql
USE interview_lab;
CREATE TABLE IF NOT EXISTS log_lab (
  id INT PRIMARY KEY,
  val VARCHAR(32)
) ENGINE=InnoDB;
INSERT INTO log_lab (id, val) VALUES (10, 'v1')
  ON DUPLICATE KEY UPDATE val = 'v1';
```

### 会话 A（保持事务不提交）

```sql
START TRANSACTION;
UPDATE log_lab SET val = 'v2' WHERE id = 10;
SELECT SLEEP(25);   -- 保持 25 秒
ROLLBACK;
```

### 会话 B（另一个连接）

```sql
-- 1. 读同一行：应该看到 v1
SELECT val FROM log_lab WHERE id = 10;

-- 2. 看事务
SELECT trx_id, trx_state, trx_isolation_level,
       trx_rows_modified, trx_started, trx_query
FROM information_schema.innodb_trx\G

-- 3. 看锁
SELECT ENGINE_TRANSACTION_ID, OBJECT_SCHEMA, OBJECT_NAME,
       INDEX_NAME, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA
FROM performance_schema.data_locks\G

-- 4. 看 undo 表空间
SELECT name, space_type, file_size/1024/1024 AS size_mb, state
FROM information_schema.INNODB_TABLESPACES
WHERE space_type = 'UNDO';

-- 5. 看 History list length / undo entries
SHOW ENGINE INNODB STATUS\G
-- 关注 TRANSACTIONS 段的 undo log entries、History list length
```

**预期**

- 会话 B 读到 `v1`（旧值），而不是 `v2`。
- `data_locks` 出现：
  ```text
  LOCK_TYPE: TABLE   LOCK_MODE: IX
  LOCK_TYPE: RECORD  LOCK_MODE: X,REC_NOT_GAP  LOCK_DATA: 10
  ```
- `SHOW ENGINE INNODB STATUS` 中出现 `undo log entries 1`。
- 会话 A 回滚后再读，仍然是 `v1`。

### 可选：RC vs RR

把会话 A 的隔离级别分别设为 `READ COMMITTED` 和 `REPEATABLE READ`，重复上面的读操作：

- RC：每条 SELECT 生成新 Read View；
- RR：第一次快照读生成 Read View，整个事务复用。

**验证点**

- 未提交修改对其他事务不可见，靠 undo 旧版本 + Read View。
- 行锁是 `X,REC_NOT_GAP`（主键等值命中）。
- 回滚靠 undo，不是靠 binlog。

**面试追问**

- `DB_TRX_ID` / `DB_ROLL_PTR` 是什么？
- RC 和 RR 的 Read View 生成时机？
- 长事务为什么会让 `History list length` 变高？

---

## Lab 7 · relay log：从库如何重放主库

> 需要一台已配置复制的从库。没有从库可以只看原理，或先跳过。

### 步骤

```sql
-- 在从库执行
SHOW REPLICA STATUS\G
-- 关注：
--   Replica_IO_Running: Yes
--   Replica_SQL_Running: Yes
--   Relay_Log_File / Relay_Log_Pos
--   Relay_Source_Log_File
--   Seconds_Behind_Source

SHOW PROCESSLIST;
-- 应能看到 system user 的 IO / SQL 线程

SHOW RELAYLOG EVENTS IN 'relay-bin.000002';
```

在主库写一行：

```sql
-- 主库
INSERT INTO interview_lab.log_lab (id, val) VALUES (20, 'replicated')
  ON DUPLICATE KEY UPDATE val = 'replicated';
```

在从库验证：

```sql
-- 从库
SELECT * FROM interview_lab.log_lab WHERE id = 20;
```

可选：观察 relay log 增长

```sql
-- 从库：暂停 SQL 线程
STOP REPLICA SQL_THREAD;

-- 主库：连续写入若干行

-- 从库：观察 relay log 文件和延迟
SHOW REPLICA STATUS\G
-- Relay_Log_File / Relay_Log_Pos 会前进，Seconds_Behind_Source 会变大

-- 恢复
START REPLICA SQL_THREAD;
```

**预期**

- relay log 中出现 `Rotate → Format_desc → BEGIN → Table_map → Write_rows → Xid`。
- IO 线程负责拉取，SQL 线程负责重放；暂停 SQL 线程后 relay log 会堆积。

**面试追问**

- relay log 和 binlog 的关系？
- `relay_log_purge` 的作用？
- 多线程复制怎么并行？大事务为什么会卡住后续事务？

---

## Lab 8 · 2PC 与崩溃恢复：观察提交边界

**目标**：理解 redo prepare、binlog 完整、redo commit 三者的关系。这个实验不需要真的在 prepare 和 commit 之间精确杀进程，重点是学会看证据。

### 步骤

1. 确认「双 1」：
   ```sql
   SELECT @@sync_binlog, @@innodb_flush_log_at_trx_commit;
   ```
2. 跑一个事务，记下 binlog 位点：
   ```sql
   FLUSH BINARY LOGS;
   SHOW MASTER STATUS\G
   START TRANSACTION;
   INSERT INTO interview_lab.log_lab (id, val) VALUES (30, '2pc')
     ON DUPLICATE KEY UPDATE val = '2pc';
   COMMIT;
   ```
3. 查看 binlog 中的提交标记：
   ```sql
   SHOW BINLOG EVENTS IN 'binlog.000004';
   -- 找到 Xid 事件
   ```
4. 查看 redo 的 LSN：
   ```sql
   SHOW ENGINE INNODB STATUS\G
   ```
5. 如果要在可丢弃实例上做崩溃实验：
   ```bash
   sudo kill -9 $(pidof mysqld)
   sudo systemctl start mysql
   sudo grep -i "crash recovery\|recovery" /var/log/mysql/error.log | tail -20
   ```
   然后检查数据是否还在、binlog 中是否有完整事务。

**恢复判断表**

| 崩溃点 | redo 状态 | binlog 状态 | 结果 |
|---|---|---|---|
| 写 binlog 前 | prepare | 无 | 回滚 |
| binlog 写了一半 | prepare | 不完整 | 回滚 |
| binlog 完整、commit 前 | prepare | 完整 | 提交 |
| redo commit 后 | commit | 完整 | 提交 |

**验证点**

- 2PC 的核心是让 redo 和 binlog 在崩溃时能做出同一个决定。
- binlog 中的 `Xid` 是判断事务完整性的关键证据。
- 生产上不要用 `kill -9` 做实验；用可丢弃实例或从库。

**面试追问**

- 为什么 redo 要 prepare，而不是直接 commit？
- 恢复时怎么判断 binlog 是否完整？
- group commit 和 2PC 是什么关系？

---

## 附录 A · 一键恢复默认开关

```sql
SET GLOBAL general_log = OFF;
SET GLOBAL slow_query_log = OFF;
SET GLOBAL log_output = 'FILE';
SET GLOBAL long_query_time = 10;
SET SESSION long_query_time = 10;
SET GLOBAL log_error_verbosity = 2;
-- 刷盘参数按你的环境恢复
SET GLOBAL innodb_flush_log_at_trx_commit = 1;
SET GLOBAL sync_binlog = 1;
```

## 附录 B · 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `general_log=ON` 但表里没数据 | `log_output` 不含 `TABLE` | `SET GLOBAL log_output='TABLE'` |
| `slow_log` 没记录 | 当前会话 `long_query_time` 还是旧值 | `SET SESSION long_query_time=...` 或重连 |
| `SHOW BINLOG EVENTS` 报找不到文件 | 文件名写错/已被清理 | 用 `SHOW MASTER STATUS` 的 `File` |
| `mysqlbinlog` 读不了文件 | 权限不足 | 用有权限的账号/`sudo`，路径从 `@@datadir` 取 |
| 从库 `SHOW REPLICA STATUS` 为空 | 没有配置复制 | 先配置 `CHANGE REPLICATION SOURCE` |
| `Seconds_Behind_Source=0` 但数据旧 | 主库无写入/时钟偏差/SQL 线程空闲 | 用 `pt-heartbeat` 或位点对比 |
| redo 实验后数据页仍落后 | WAL 正常现象 | 等待 checkpoint 或手动刷脏 |

## 附录 C · 实验记录模板

每个实验建议记录：

```text
实验：
时间：
MySQL 版本：
关键参数：
执行命令：
预期现象：
实际输出：
结论：
面试追问：
```

> 记录「预期 vs 实际」的差异，比只抄命令更有价值。
