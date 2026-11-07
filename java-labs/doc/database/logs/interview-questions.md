# MySQL 日志体系面试题 35 题

> **适用**：P6/P7 后端面试；配合主卷 [Q16 / Q17](../数据库面试真题答卷-经核实版-v1.md) 使用。
>
> **标记说明**：⭐ 出现频率；**【一句话答】** 先给结论；**【展开】** 答题细节；**【追问】** 面试官可能继续问；**【易错】** 常见误区。

## 一、全景与分层

### L01 · MySQL 有哪些日志？分别属于哪一层？⭐⭐⭐⭐⭐

**【一句话答】** Server 层有 error、general、slow、binlog、relay，InnoDB 层有 redo、undo，另有内部的 DDL log。

**【展开】**

| 日志 | 层级 | 一句话定位 |
|---|---|---|
| error log | Server | 启动/关闭/错误/警告，排障第一现场 |
| general query log | Server | 所有客户端语句，调试/审计 |
| slow query log | Server | 超过阈值的慢 SQL |
| binary log | Server | 逻辑变更，复制 / PITR |
| relay log | Server（从库） | 从库 IO 线程拉取的主库 binlog 副本 |
| DDL log | InnoDB | 原子 DDL 的元数据日志，内部使用 |
| redo log | InnoDB | 物理页变更，WAL，崩溃恢复 |
| undo log | InnoDB | 旧版本 / 回滚信息，MVCC + 回滚 |

**【追问】** 哪些默认开？哪些不能关？
- error log 默认开；binlog 在 8.0 默认开；general/slow 默认关。
- redo/undo 是 InnoDB 运行必需，不能关；relay log 只要配置复制就会产生。

**【易错】** redo/undo 不是「可选日志」，它们是 InnoDB 事务和崩溃恢复的基础设施。

---

### L02 · 为什么需要 redo 和 binlog 两份日志？只留一份行不行？⭐⭐⭐⭐⭐

**【一句话答】** 职责不同：redo 是 InnoDB 的物理日志，负责崩溃恢复；binlog 是 Server 层的逻辑日志，负责复制和 PITR。少一份都无法同时满足持久性和复制/归档。

**【展开】**
- **只留 redo**：redo 记录页级物理修改，平台/版本相关，不能拿去做主从复制，也不能按 SQL/时间点做 PITR。
- **只留 binlog**：binlog 是逻辑日志，重放需要执行 SQL，无法保证页级崩溃恢复的效率和确定性；InnoDB 的脏页恢复必须靠 redo。
- **两份都要**：所以需要 2PC 保证 redo 和 binlog 在崩溃时一致。

**【追问】** binlog 能不能做崩溃恢复？
- 可以做 **PITR**（基于全量备份 + 重放），但不是 InnoDB 的崩溃恢复机制。崩溃恢复看 redo，PITR 看 binlog。

**【易错】** 把「PITR」和「崩溃恢复」混为一谈。

---

### L03 · redo、undo、binlog 三件套的区别？⭐⭐⭐⭐⭐

**【一句话答】** redo 物理、undo 逻辑反向、binlog 逻辑变更；redo 管崩溃恢复，undo 管回滚和 MVCC，binlog 管复制和 PITR。

**【展开】**

| 维度 | redo log | undo log | binlog |
|---|---|---|---|
| 层级 | InnoDB | InnoDB | Server |
| 内容 | 物理页变更（页号 + 偏移 + 修改） | 逻辑反向操作 + 旧版本 | 逻辑变更（ROW 模式是行镜像） |
| 写入时机 | 事务执行中持续写，提交时刷盘 | DML 执行时 | 事务提交时（2PC 第二阶段） |
| 文件 | `#innodb_redo/` 循环写 | undo tablespace | `binlog.00000N` 追加写 |
| 用途 | 崩溃恢复 | 回滚 + MVCC | 复制 + PITR |
| 清理 | 容量固定，循环覆盖 | purge 线程清理 | `binlog_expire_logs_seconds` 过期 |

**【追问】** 为什么 undo 本身也写 redo？
- undo 的修改也是页修改，需要 redo 保护；否则崩溃后 undo 页损坏，回滚/MVCC 也会出问题。

**【易错】** 「undo 用来恢复数据」——undo 用于回滚和 MVCC，崩溃恢复靠 redo。

---

### L04 · relay log、error log、general log、slow log、DDL log 各自解决什么问题？⭐⭐⭐

**【一句话答】** relay 用于从库重放；error 排障；general 调试/审计；slow 性能定位；DDL log 保证原子 DDL。

**【展开】**

| 日志 | 解决的问题 | 生产建议 |
|---|---|---|
| relay log | 从库 IO 线程落地主库 binlog，SQL 线程重放 | 必须监控延迟和 `Last_SQL_Error` |
| error log | 启动失败、崩溃、复制错误、警告 | 必开，集中采集 |
| general log | 看清客户端到底发了什么 SQL | 只在排查时短开 |
| slow log | 找慢 SQL、分析执行计划 | 建议开，阈值按业务定 |
| DDL log | DDL 崩溃后的一致性（原子 DDL） | 内部使用，无需手工维护 |

**【追问】** general log 能当审计日志吗？
- 不建议。它不是防篡改审计系统，开销大，也不保证完整性；合规审计用 MySQL Enterprise Audit 或外部审计。

**【易错】** 把 general log 和 slow log 混为一谈：general 记「所有」，slow 只记「超时」。

---

## 二、binlog

### L05 · binlog 的三种格式有什么区别？生产怎么选？⭐⭐⭐⭐⭐

**【一句话答】** STATEMENT 记 SQL，ROW 记行镜像，MIXED 由服务器按语句选择；生产默认用 ROW。

**【展开】**

| 格式 | 记录内容 | 优点 | 缺点 |
|---|---|---|---|
| STATEMENT | 原始 SQL | 体积小、可读性好 | 非确定性函数/上下文可能导致主从不一致 |
| ROW | 行级前/后镜像 | 安全、可精确恢复 | 体积大、批量操作日志膨胀 |
| MIXED | 服务器自动选择 | 兼顾体积和安全 | 行为不如 ROW 确定 |

- MySQL 8.0 默认 `binlog_format=ROW`。
- 非确定性场景：`UUID()`、`NOW()`、`RAND()`、用户变量、无 `ORDER BY` 的 `LIMIT`、部分触发器/存储过程。

**【追问】** 为什么 ROW 更安全？
- 从库直接应用行变更，不重新执行 SQL，避免函数/上下文/优化器差异导致的数据不一致。

**【易错】** MIXED 不是「一半 STATEMENT 一半 ROW」的固定比例，而是按语句选择。

---

### L06 · ROW 格式下一条 UPDATE 在 binlog 里长什么样？前镜像/后镜像有什么用？⭐⭐⭐⭐

**【一句话答】** `Table_map` + `Update_rows`：`WHERE` 是前镜像，`SET` 是后镜像；前镜像用于定位行和冲突检测，后镜像用于从库应用。

**【展开】**
- 一条事务的事件链：`BEGIN → Table_map → Write_rows/Update_rows/Delete_rows → Xid`。
- `binlog_row_image=FULL`（默认）：记录所有列的前后镜像。
- `MINIMAL`：只记录主键/唯一键和修改列；体积小，但要求表有主键，且某些场景不够安全。
- `NOBLOB`：不记录未修改的 BLOB/TEXT 列。

**【追问】** 为什么 UPDATE/DELETE 需要前镜像？
- 从库需要用前镜像定位要修改/删除的行；也用于冲突检测和部分复制场景。

**【易错】** ROW 格式不是「只记新值」，UPDATE/DELETE 一定包含前镜像（至少主键）。

---

### L07 · binlog 什么时候写？`sync_binlog` 怎么选？⭐⭐⭐⭐

**【一句话答】** binlog 在事务提交的 2PC 第二阶段写；`sync_binlog=1` 每次提交 fsync，最安全；`0` 由 OS 决定；`N` 每 N 次提交 fsync。

**【展开】**

| `sync_binlog` | 行为 | 风险 |
|---|---|---|
| `0` | 写 OS cache，由 OS 刷盘 | 机器掉电可能丢较多事务 |
| `1` | 每次提交 fsync | 最安全，性能开销最大 |
| `N` | 每 N 次提交 fsync | 掉电可能丢最多 N-1 个事务 |

- 生产常见「双 1」：`sync_binlog=1` + `innodb_flush_log_at_trx_commit=1`。
- `group commit` 会把多个事务的 fsync 合并，降低「双 1」的性能损失。

**【追问】** 为什么 binlog 不在事务执行过程中写？
- 2PC 需要保证 redo 和 binlog 一致；binlog 在提交阶段写，配合 redo prepare/commit 才能做崩溃判断。

**【易错】** `sync_binlog=0` 不等于「不写 binlog」，只是不保证每次提交都落盘。

---

### L08 · 如何用 binlog 做 PITR？给出误删恢复流程。⭐⭐⭐⭐

**【一句话答】** 先恢复到最近的全量备份，再用 `mysqlbinlog` 重放备份点到误删点之前的 binlog。

**【展开】**
1. 停止业务写入（或切只读），保留现场。
2. 找到最近一次全量备份，恢复到临时实例/临时库。
3. 确认误删发生的时间/位点：`SHOW BINLOG EVENTS`、`mysqlbinlog` 或审计日志。
4. 重放 binlog 到误删前：
   ```bash
   mysqlbinlog --start-datetime='2026-10-18 10:00:00' \
               --stop-datetime='2026-10-18 10:30:00' \
               binlog.000012 | mysql -h <host> -u root -p
   ```
   或使用 `--start-position` / `--stop-position`。
5. 跳过误删语句，继续重放后续 binlog（如业务允许）。
6. 校验数据后切回。

**【追问】** 没有全量备份怎么办？
- 如果 binlog 从建库起都保留，可以尝试从头重放；但风险高、耗时长。更现实的是用 `binlog2sql` / `my2sql` 从 ROW 日志生成回滚 SQL，先恢复被删数据。前提是 `binlog_row_image=FULL`。

**【易错】** 直接在生产库上重放、不先备份现场、忘记跳过误删语句。

---

### L09 · position 和 GTID 有什么区别？主从切换为什么推荐 GTID？⭐⭐⭐⭐

**【一句话答】** position 是「文件 + 偏移」，GTID 是「全局事务 ID」；GTID 能自动定位事务，主从切换不用手工算位点。

**【展开】**

| 维度 | position | GTID |
|---|---|---|
| 标识 | `binlog.000012:12345` | `server_uuid:transaction_id` |
| 主从切换 | 需要找到新主对应位点，易错 | 自动定位，未执行事务自动补 |
| 配置 | 默认可用 | `gtid_mode=ON` + `enforce_gtid_consistency=ON` |
| 常用命令 | `SHOW MASTER STATUS` | `SHOW MASTER STATUS` / `gtid_executed` |

**【追问】** GTID 有什么限制？
- 不支持某些非事务引擎/语句（如部分 `CREATE TABLE ... SELECT` 历史限制）；需要一致性配置；切换前要确认 `gtid_executed` 和 `gtid_purged`。

**【易错】** GTID 不是「事务 ID」本身，而是 `server_uuid:transaction_id` 的全局唯一标识。

---

### L10 · binlog 什么时候轮转？怎么清理/保留？⭐⭐⭐

**【一句话答】** 达到 `max_binlog_size`、执行 `FLUSH BINARY LOGS`、实例重启等会轮转；用 `binlog_expire_logs_seconds` 自动过期，或 `PURGE BINARY LOGS` 手工清理。

**【展开】**
- `max_binlog_size` 默认 1GB（本实验环境设为 100MB）。
- 轮转后生成 `binlog.00000N`，`binlog.index` 记录文件列表。
- 8.0 用 `binlog_expire_logs_seconds`（默认 30 天）；`expire_logs_days` 已废弃。
- 手工清理：
  ```sql
  PURGE BINARY LOGS BEFORE '2026-10-01 00:00:00';
  PURGE BINARY LOGS TO 'binlog.000020';
  ```
- 清理前确认从库已经消费：`SHOW REPLICA STATUS` 的 `Relay_Source_Log_File` / `Exec_Source_Log_Pos`。

**【追问】** 直接 `rm` binlog 文件行不行？
- 不行。会破坏 `binlog.index`，复制和 PITR 都可能出问题；必须用 `PURGE BINARY LOGS`。

**【易错】** 只看主库磁盘，不看从库消费位点就清理。

---

## 三、redo log

### L11 · 为什么 redo log 是物理日志？它到底记录了什么？⭐⭐⭐⭐

**【一句话答】** redo 记录「哪个表空间的哪个页、哪个偏移、改了什么」，是页级物理变更，不是 SQL。

**【展开】**
- 更准确地说，InnoDB redo 是 **physiological**：物理到页，逻辑到页内。
- 例：某页的某条记录插入/更新，redo 记录页号、偏移和字节级修改。
- 物理日志的好处：恢复时直接应用页修改，不需要解析 SQL、执行优化器，速度快且确定。

**【追问】** redo 和 binlog 的粒度差异会带来什么问题？
- 两者粒度不同，必须靠 2PC 保证一致；否则崩溃后可能出现 redo 有、binlog 没有（或反过来）。

**【易错】** 「redo 记录 SQL」——那是 binlog；redo 不记录 SQL。

---

### L12 · 什么是 WAL？为什么先写 redo 再刷数据页？⭐⭐⭐⭐⭐

**【一句话答】** WAL（Write-Ahead Logging）是先写日志再刷数据页；把随机写变成顺序写，同时保证崩溃后能恢复。

**【展开】**
- 事务提交时，只需要保证 redo 落盘，数据页可以之后慢慢刷。
- 数据页是随机 I/O；redo 是顺序追加，性能高得多。
- 崩溃后从 checkpoint 开始重放 redo，恢复已提交的修改；未提交的用 undo 回滚。
- 关键参数：`innodb_flush_log_at_trx_commit`。

**【追问】** 为什么不能直接刷数据页？
- 随机 I/O 太慢；一个事务可能修改多个页，无法保证原子性；崩溃时可能只刷了一半。

**【易错】** WAL 不是「先写 binlog」；InnoDB 的 WAL 指 redo。

---

### L13 · LSN、checkpoint、`Log flushed up to`、`Pages flushed up to` 怎么理解？⭐⭐⭐⭐

**【一句话答】** LSN 是日志序列号；`Log flushed up to` 是已 fsync 的 redo；`Pages flushed up to` 是已刷盘的数据页；`Last checkpoint at` 是最近一次 checkpoint 的位置。

**【展开】**
- `Log sequence number`：当前 redo 写入位置（单调递增）。
- `Log flushed up to`：已经 fsync 到磁盘的 redo 位置。
- `Log written up to`：已写入 OS cache 的位置。
- `Pages flushed up to`：数据页已经刷到磁盘的位置。
- `Last checkpoint at`：checkpoint 位置；checkpoint 之前的 redo 可以被覆盖。
- 正常现象：提交后 `Log flushed up to` 追平 `Log sequence number`，但 `Pages flushed up to` 可能落后——这就是 WAL。

**【追问】** 崩溃恢复从哪里开始？
- 从 `Last checkpoint at` 开始扫描 redo，应用到最新 LSN；再用 undo 回滚未提交事务。

**【易错】** 把 LSN 当成时间戳；LSN 是字节偏移式的单调序列。

---

### L14 · `innodb_flush_log_at_trx_commit` 0/1/2 的区别？生产怎么选？⭐⭐⭐⭐⭐

**【一句话答】** 1 每次提交 fsync（最安全）；0 每秒刷一次（可能丢 1 秒）；2 每次提交写 OS cache、每秒 fsync（mysqld 崩溃不丢，OS 崩溃可能丢 1 秒）。

**【展开】**

| 值 | 提交时行为 | mysqld 崩溃 | OS/机器掉电 |
|---|---|---|---|
| `0` | 写 redo buffer，后台每秒刷盘 | 可能丢约 1 秒 | 可能丢约 1 秒 |
| `1` | 每次提交 fsync redo | 不丢 | 不丢 |
| `2` | 每次提交 write 到 OS cache，每秒 fsync | 不丢（OS cache 还在） | 可能丢约 1 秒 |

- 金融/订单：`1`。
- 一般业务：`2` 或 `1`。
- 日志/可容忍丢失：`0`。
- 常与 `sync_binlog=1` 组成「双 1」。

**【追问】** `2` 为什么 mysqld 崩溃不丢？
- 数据已经交给操作系统 page cache，进程崩溃不会清空 OS cache；只有机器掉电/OS 崩溃才可能丢。

**【易错】** 以为 `2` 完全不丢；机器掉电仍可能丢约 1 秒。

---

### L15 · redo log 容量怎么配？写满了会怎样？⭐⭐⭐

**【一句话答】** 8.0.30+ 用 `innodb_redo_log_capacity`（默认 100MB）；满了会触发 checkpoint，checkpoint 跟不上就会阻塞写入甚至报错。

**【展开】**
- redo 是循环写：checkpoint 推进后，旧日志空间才能复用。
- 容量太小：频繁 checkpoint，写性能抖动，极端情况「redo log is full」。
- 容量太大：崩溃恢复时间变长，磁盘占用增加。
- 监控：`SHOW ENGINE INNODB STATUS` 的 `Log capacity` / `Log capacity used`。
- 8.0.30 之前：`innodb_log_file_size × innodb_log_files_in_group`。

**【追问】** 为什么 redo 不能无限大？
- 恢复时间、磁盘空间、checkpoint 行为都需要平衡；不是越大越好。

**【易错】** 只调 `innodb_log_file_size` 却不知道 8.0.30+ 已被 `innodb_redo_log_capacity` 取代。

---

### L16 · 崩溃恢复时 redo 怎么判断哪些事务要重做？⭐⭐⭐⭐

**【一句话答】** 从 checkpoint 开始重放 redo；已提交事务重做，未提交事务用 undo 回滚；2PC 场景还要结合 binlog 是否完整。

**【展开】**
- redo 中记录事务状态（prepare/commit）。
- 恢复流程：
  1. 从 `Last checkpoint` 开始扫描 redo；
  2. 重做已提交/已 prepare 的页修改；
  3. 对未提交事务执行 undo；
  4. 2PC：prepare + binlog 完整 → 提交；prepare + binlog 不完整 → 回滚。
- `innodb_force_recovery` 只在极端损坏时使用，可能丢数据。

**【追问】** redo 损坏了怎么办？
- 优先从备份恢复；`innodb_force_recovery` 是最后手段，逐级尝试并尽快导出数据。

**【易错】** 以为崩溃恢复只靠 redo；未提交事务的回滚需要 undo，2PC 判断需要 binlog。

---

## 四、undo log 与 MVCC

### L17 · undo log 的两大作用？为什么它既能回滚又能支持 MVCC？⭐⭐⭐⭐⭐

**【一句话答】** undo 记录旧版本/反向操作：事务回滚时反向执行；MVCC 通过 undo 版本链读取历史版本。

**【展开】**
- INSERT：undo 记录删除操作。
- UPDATE：undo 记录旧值（反向 UPDATE）。
- DELETE：undo 记录插入操作。
- 每行有隐藏字段 `DB_ROLL_PTR` 指向 undo 版本链。
- purge 线程在确认没有 Read View 需要旧版本后清理 undo。

**【追问】** undo 自己也需要 redo 保护吗？
- 需要。undo 页的修改同样要写 redo，否则崩溃后 undo 也可能损坏。

**【易错】** 把 undo 当成「数据备份」；它只服务于事务回滚和 MVCC。

---

### L18 · MVCC 的版本链怎么串？`DB_TRX_ID` / `DB_ROLL_PTR` 是什么？⭐⭐⭐⭐⭐

**【一句话答】** 每行有隐藏字段 `DB_TRX_ID`（最后修改事务 ID）、`DB_ROLL_PTR`（指向 undo 旧版本）、`DB_ROW_ID`（无主键时的行 ID）；通过 `DB_ROLL_PTR` 串成版本链。

**【展开】**

| 隐藏字段 | 作用 |
|---|---|
| `DB_TRX_ID` | 最后修改该行的事务 ID |
| `DB_ROLL_PTR` | 指向 undo log 中的上一个版本 |
| `DB_ROW_ID` | 没有主键时 InnoDB 生成的 6 字节行 ID |

- Read View 判断版本可见性：
  - `trx_id < min_trx_id`：已提交，可见；
  - `trx_id >= max_trx_id`：未来事务，不可见；
  - `min_trx_id <= trx_id < max_trx_id`：看是否在 `m_ids` 活跃事务列表中。
- 快照读沿版本链找到第一个可见版本；当前读读最新版本。

**【追问】** 快照读和当前读的区别？
- 快照读：普通 `SELECT`，走 MVCC；
- 当前读：`SELECT ... FOR UPDATE`、`UPDATE`、`DELETE`，读最新版本并加锁。

**【易错】** MVCC 只对快照读生效；当前读不受 Read View 保护。

---

### L19 · RC 和 RR 的 Read View 生成时机？RR 能完全避免幻读吗？⭐⭐⭐⭐⭐

**【一句话答】** RC 每条语句生成新 Read View；RR 在事务第一次快照读时生成一个 Read View 并复用。RR 的快照读不会幻读，但当前读在特殊边界仍可能出现幻读。

**【展开】**

| 隔离级别 | Read View 时机 | 现象 |
|---|---|---|
| READ COMMITTED | 每条 SELECT | 不可重复读、幻读可能 |
| REPEATABLE READ | 第一次快照读 | 同一事务快照一致 |

- RR 下当前读（`UPDATE`/`DELETE`/`SELECT ... FOR UPDATE`）靠 next-key lock 防幻读。
- 特殊场景：先快照读、再当前读，可能看到「幻影行」；边界条件/并发插入仍可能触发。
- InnoDB 的 RR 不是「完全串行化」。

**【追问】** 为什么 RC 不能复用 Read View？
- RC 的语义就是「读已提交」，每条语句都要看到最新提交；复用会变成 RR。

**【易错】** 「RR 完全解决幻读」——严格说只在快照读下成立，当前读要靠锁。

---

### L20 · 长事务为什么危险？`History list length` 高说明什么？⭐⭐⭐⭐

**【一句话答】** 长事务阻止 undo 版本被 purge，导致 undo 膨胀、锁持有时间长、复制延迟；`History list length` 高说明有旧版本堆积。

**【展开】**
- 长事务期间，Read View 可能还需要旧版本，purge 不能清理。
- 后果：undo tablespace 增长、查询变慢、锁等待、主从延迟、崩溃恢复变慢。
- 排查：
  ```sql
  SELECT trx_id, trx_state, trx_started, trx_rows_modified, trx_query
  FROM information_schema.innodb_trx
  ORDER BY trx_started;
  ```
- `SHOW ENGINE INNODB STATUS` 的 `History list length` 持续增长要警惕。

**【追问】** 怎么处理长事务？
- 找到来源（应用未提交、连接池泄漏、交互式事务），设置 `innodb_lock_wait_timeout`、`wait_timeout`，必要时 kill；根本上是缩短事务。

**【易错】** 看到 `Sleep` 就以为没事务；`START TRANSACTION` 后不提交连接会一直持有 Read View。

---

### L21 · undo tablespace 怎么管理？undo 会无限增长吗？⭐⭐⭐

**【一句话答】** 8.0 默认 2 个 undo tablespace，可用 `CREATE UNDO TABLESPACE` 扩展；正常情况下 purge 会回收空间，长事务/大量旧版本时可能增长。

**【展开】**
- 文件：`undo_001`、`undo_002`（默认各 32MB 左右，按需增长）。
- `innodb_undo_tablespaces` 在 8.0 已废弃；用 `CREATE UNDO TABLESPACE` 增加。
- `innodb_purge_threads` 控制 purge 并发；`innodb_max_undo_log_size` 控制 truncate 阈值。
- 长事务会阻塞 purge，undo 可能持续增长。

**【追问】** undo 文件能手工删吗？
- 不能。必须通过 `ALTER UNDO TABLESPACE ... SET INACTIVE` + `DROP UNDO TABLESPACE` 等正规流程。

**【易错】** 直接删 `undo_001`/`undo_002`，会导致实例无法启动。

---

## 五、2PC 与崩溃恢复

### L22 · 两阶段提交的完整流程？为什么 redo 要 prepare？⭐⭐⭐⭐⭐

**【一句话答】** redo prepare → binlog write/fsync → redo commit；prepare 是为了在崩溃时判断 binlog 是否已经完整，从而决定提交还是回滚。

**【展开】**
1. InnoDB 写 redo，事务状态标记 `prepare`。
2. Server 写 binlog，并 `fsync`（受 `sync_binlog` 控制）。
3. InnoDB 写 redo commit 标记，事务提交完成。
- 如果先 commit redo 再写 binlog，binlog 失败时主库有数据、从库没有，主从不一致。
- `group commit` 会把多个事务的 redo/binlog fsync 合并。

**【追问】** 为什么需要 prepare 状态，而不是直接写 binlog？
- prepare 让 InnoDB 知道「这个事务的 binlog 可能还没写完」；崩溃恢复时才能结合 binlog 判断。

**【易错】** 把 2PC 说成分布式事务；这里是单机 MySQL 内部 redo ↔ binlog 的一致性协议。

---

### L23 · 崩溃发生在 2PC 的不同阶段，恢复时分别怎么处理？⭐⭐⭐⭐⭐

**【一句话答】** redo prepare + binlog 完整 → 提交；redo prepare + binlog 不完整 → 回滚；redo commit → 直接提交。

**【展开】**

| 崩溃点 | redo 状态 | binlog 状态 | 恢复动作 |
|---|---|---|---|
| 写 binlog 之前 | prepare | 无/不完整 | 回滚 |
| 写 binlog 之后、commit 之前 | prepare | 完整 | 提交 |
| commit 之后 | commit | 完整 | 提交 |
| binlog 写了一半 | prepare | 不完整 | 回滚 |

- 判断 binlog 是否完整：事务的 `Xid` 是否出现在 binlog 中，且事务边界完整。

**【追问】** 恢复时怎么知道 binlog 是完整的？
- 通过 binlog 中的事务边界事件（`Xid`/`COMMIT`）和 redo 中的事务 Xid 对应关系判断；不完整的事务不会出现在 binlog 的已提交事务列表中。

**【易错】** 以为「binlog 写了就提交」；必须是一个完整事务。

---

### L24 · 2PC 能关吗？group commit 和 2PC 是什么关系？⭐⭐⭐

**【一句话答】** 不能关；2PC 是 redo 和 binlog 一致性的基础。group commit 是在 2PC 基础上合并 fsync，提升性能，不改变一致性语义。

**【展开】**
- MySQL 8.0 中 2PC 是内部固定流程；`innodb_support_xa` 已废弃/移除。
- `group commit`：多个事务一起刷 redo/binlog，减少 fsync 次数。
- 相关参数：`binlog_group_commit_sync_delay`、`binlog_group_commit_sync_no_delay_count`。

**【追问】** group commit 会不会影响持久性？
- 会按参数引入延迟，但提交返回前仍保证日志落盘；具体取决于 `sync_binlog` 和 flush 设置。

**【易错】** 把 2PC 和 XA 分布式事务混为一谈。

---

## 六、运维日志

### L25 · error log 怎么配？`log_error_verbosity` 2/3 有什么区别？⭐⭐⭐

**【一句话答】** `log_error` 指定文件；`log_error_verbosity` 1 只记错误，2 记错误+警告（默认），3 还记 notes（如登录失败）。

**【展开】**
- `log_error=/var/log/mysql/error.log`
- `log_error_verbosity=2` 时，`Access denied` 登录失败**不会**进 error log；调到 3 才会记录 `[Note] [MY-010926]`。
- 启动/关闭、崩溃恢复、复制错误、警告都在这里。
- 8.0 支持 `log_error_services` 组合多个日志组件。

**【追问】** error log 里重点关注什么？
- `[ERROR]`、崩溃恢复信息、复制 `Last_IO_Error`/`Last_SQL_Error`、磁盘/权限问题、认证失败（verbosity=3）。

**【易错】** 开了 error log 就以为登录失败一定有记录；默认 verbosity=2 不记 note。

---

### L26 · general log 为什么不能常开？什么时候用？⭐⭐⭐

**【一句话答】** 它记录所有客户端语句和连接事件，I/O 和磁盘开销大，还可能泄露敏感 SQL；只在排查问题时短开。

**【展开】**
- `general_log=ON`，`log_output=FILE` 或 `TABLE`。
- 记录 `Connect`、`Query`、`Quit` 等事件。
- 开销：每条语句都写日志，高并发下性能明显下降；日志可能迅速膨胀。
- 适合：确认客户端到底发了什么 SQL、排查连接/权限/ORM 行为。

**【追问】** general log 和审计日志的区别？
- general log 不是防篡改审计系统；合规审计用 Enterprise Audit 或外部审计。

**【易错】** 在生产长期开启 general log；忘记关掉导致磁盘写满。

---

### L27 · slow log 怎么开？`long_query_time` 会话级 vs 全局级的坑？⭐⭐⭐⭐

**【一句话答】** `slow_query_log=ON` + `long_query_time` 设置阈值；`long_query_time` 是会话级变量，`SET GLOBAL` 不影响已存在的会话。

**【展开】**
```sql
SET GLOBAL slow_query_log = ON;
SET GLOBAL long_query_time = 1;   -- 只影响新会话
SET SESSION long_query_time = 1;  -- 当前会话也要设置
```
- 相关参数：`log_output`、`log_slow_extra`、`log_queries_not_using_indexes`、`log_slow_admin_statements`。
- `long_query_time=0` 会记录所有语句（实验用，生产慎用）。
- 慢日志判定用的是**会话**的 `long_query_time`。

**【追问】** 为什么 `SET GLOBAL` 后当前会话还是不记录？
- 会话变量在连接建立时从全局值初始化；已有会话不会自动同步，需要 `SET SESSION` 或重连。

**【易错】** 只改全局就以为当前会话生效；这是实际踩过的坑。

---

### L28 · 慢日志看哪些字段？`Rows_examined` 和 `Rows_sent` 差很大说明什么？⭐⭐⭐

**【一句话答】** 重点看 `Query_time`、`Lock_time`、`Rows_sent`、`Rows_examined`；`Rows_examined` 远大于 `Rows_sent` 通常说明索引缺失或扫描过多。

**【展开】**

| 字段 | 含义 |
|---|---|
| `Query_time` | 总执行时间 |
| `Lock_time` | 等锁时间 |
| `Rows_sent` | 返回给客户端的行数 |
| `Rows_examined` | 存储引擎检查的行数 |
| `Rows_affected` | 受影响行数 |
| `Bytes_sent` / `Bytes_received` | 网络传输量 |

- `log_slow_extra=ON` 会输出更多字段（`Read_first`、`Read_key`、`Sort_rows` 等）。
- 工具：`mysqldumpslow`、`pt-query-digest`。

**【追问】** 慢日志里没有的慢查询怎么找？
- 高频但单次不慢的查询、锁等待、网络问题；结合 `performance_schema`、`SHOW PROCESSLIST`、APM。

**【易错】** 只看 `Query_time`，忽略 `Rows_examined` 和 `Lock_time`。

---

## 七、relay log 与复制

### L29 · relay log 怎么产生？IO 线程和 SQL 线程各做什么？⭐⭐⭐⭐

**【一句话答】** 从库 IO 线程连接主库，拉取 binlog 写成 relay log；SQL 线程读取 relay log 并重放。

**【展开】**
- IO 线程：`Replica_IO_Running`，连接主库，接收 binlog 事件，写 relay log。
- SQL 线程：`Replica_SQL_Running`，读 relay log，应用到从库数据。
- 多线程复制：coordinator + worker 线程，按事务/库并行。
- `SHOW PROCESSLIST` 中能看到 `system user` 的 IO/SQL 线程。

**【追问】** IO 线程和 SQL 线程分离的好处？
- 网络接收和本地重放解耦；主库短暂不可用时，从库可以继续应用已拉取的 relay log。

**【易错】** 以为 relay log 是主库的文件；它是从库本地的副本。

---

### L30 · relay log 和 binlog 什么关系？`SHOW RELAYLOG EVENTS` 怎么看？⭐⭐⭐

**【一句话答】** relay log 和 binlog 格式相同；在从库上用 `SHOW RELAYLOG EVENTS` 或 `mysqlbinlog` 查看。

**【展开】**
```sql
SHOW REPLICA STATUS\G
-- 关注 Relay_Log_File、Relay_Log_Pos、Relay_Source_Log_File
SHOW RELAYLOG EVENTS IN 'relay-bin.000002';
```
- relay log 中会看到 `Rotate`（指向主库 binlog 位点）、`Format_desc`、事务事件。
- `relay_log_purge=ON`（默认）会在应用后清理 relay log。
- `relay_log_recovery=ON` 可在崩溃后丢弃未应用的 relay log 并重新拉取。

**【追问】** 为什么 relay log 里有 `Rotate` 事件？
- 记录当前 relay log 对应主库 binlog 的文件和位点，便于恢复和切换。

**【易错】** 用主库的 binlog 文件名去看 relay log；两者文件名/位点不同。

---

### L31 · 主从延迟怎么排查？`Seconds_Behind_Source` 可信吗？⭐⭐⭐⭐

**【一句话答】** 看 `SHOW REPLICA STATUS` 的 IO/SQL 线程状态、`Relay_Log_Pos`/`Exec_Source_Log_Pos`、`Seconds_Behind_Source`；但 `Seconds_Behind_Source` 可能为 0 却不代表没延迟。

**【展开】**
- `Seconds_Behind_Source` 基于事件时间戳和当前时间，主库无写入、时钟偏差、SQL 线程空闲时可能为 0。
- 更可靠：`pt-heartbeat`、对比主从位点、`performance_schema.replication_applier_status`。
- 常见原因：大事务、DDL、单线程复制、从库磁盘慢、锁等待、网络。
- 多线程复制参数：`replica_parallel_workers`、`replica_parallel_type`。

**【追问】** 大事务为什么导致延迟？
- 一个事务必须在同一个 worker 上完整应用；大事务会阻塞后续事务，且 commit 前不可见。

**【易错】** 只看 `Seconds_Behind_Source=0` 就认为没有延迟。

---

## 八、场景题

### L32 · 误删数据怎么恢复？有备份/没备份分别怎么做？⭐⭐⭐⭐⭐

**【一句话答】** 有备份：全量恢复 + binlog 重放到误删前；没备份：用 ROW binlog 生成回滚 SQL（binlog2sql/my2sql），或从备份/从库恢复。

**【展开】**
- 有备份：
  1. 停写/只读，保留现场；
  2. 恢复最近全备到临时实例；
  3. `mysqlbinlog --stop-datetime/--stop-position` 重放到误删前；
  4. 跳过误删语句，继续重放或导出数据回填；
  5. 校验后切回。
- 没备份：
  - binlog 还在且 `binlog_row_image=FULL`：用 `binlog2sql` 反向生成 INSERT/UPDATE；
  - 有从库：从从库导出数据；
  - 都没有：只能尽量减少损失，评估能否从业务日志重建。
- 前提：binlog 没被清理；`binlog_format=ROW`。

**【追问】** 为什么 ROW 格式更容易恢复？
- 有完整的前/后镜像，可以精确生成反向 SQL；STATEMENT 依赖上下文，容易出错。

**【易错】** 在生产库直接重放；不先备份现场；忘记 `--stop-position`。

---

### L33 · 磁盘被 binlog 写满怎么办？⭐⭐⭐

**【一句话答】** 先确认从库消费位点，再用 `PURGE BINARY LOGS` 清理，或调小 `binlog_expire_logs_seconds`；不要直接 `rm` 文件。

**【展开】**
```sql
SHOW BINARY LOGS;
SHOW REPLICA STATUS\G   -- 确认从库已消费
PURGE BINARY LOGS BEFORE '2026-10-01 00:00:00';
SET GLOBAL binlog_expire_logs_seconds = 604800;  -- 7 天
```
- 长期方案：归档到对象存储、监控磁盘、合理设置 `max_binlog_size`。
- 紧急时也可以先切到从库/只读，避免继续写入。

**【追问】** 直接删 binlog 文件会怎样？
- `binlog.index` 与实际文件不一致；复制、PITR、重启都可能失败。

**【易错】** 不看从库位点就清理，导致从库无法继续复制。

---

### L34 · 主库宕机后从库 relay log 还在，怎么处理？⭐⭐⭐

**【一句话答】** 先确认从库能否继续应用已有 relay log；再根据 `relay_log_recovery`、GTID/位点和故障恢复策略决定是否提升从库。

**【展开】**
- 主库宕机不影响从库应用已经拉取的 relay log；`SHOW REPLICA STATUS` 看 SQL 线程是否还在跑。
- `relay_log_recovery=ON`：崩溃恢复时丢弃未应用的 relay log，重新从主库拉取（需要主库可达）。
- 若主库长时间不可用，可评估提升从库：
  - 确认从库已应用完 relay log；
  - 确认 GTID/位点；
  - `STOP REPLICA; RESET REPLICA ALL;` 提升为主；
  - 其他从库指向新主。
- 操作前备份现场。

**【追问】** 提升从库前最关键的一步是什么？
- 确认从库数据已经追平到可接受的位置（relay log 应用完、位点/GTID 确认），避免丢事务。

**【易错】** 盲目删除 relay log 或直接提升未追平的从库。

---

### L35 · 为什么从库会「relay log 已读完但数据不一致」？⭐⭐⭐

**【一句话答】** 可能是 SQL 线程报错后跳过、复制过滤、非确定性语句、从库被直接写入、schema 不一致或二进制日志损坏。

**【展开】**
- 先看 `SHOW REPLICA STATUS` 的 `Last_IO_Error`、`Last_SQL_Error`、`Replica_SQL_Running_State`。
- 常见原因：
  - `sql_slave_skip_counter` / GTID 跳过错误；
  - `replicate_do_db` / `replicate_ignore_db` 过滤；
  - STATEMENT 格式的非确定性语句；
  - 从库被直接写入（未开 `read_only`/`super_read_only`）；
  - 主从 schema/字符集/版本不一致；
  - binlog 损坏或 relay log 不完整。
- 校验工具：`pt-table-checksum`、`pt-table-sync`；修复前先定位差异。

**【追问】** 跳过复制错误有什么风险？
- 可能永久丢失一个事务，导致主从数据不一致；跳过前要评估影响并记录。

**【易错】** 把「跳过错误」当成修复；不定位根因，后面还会出问题。

---

## 附：高频追问速查

| 问题 | 关键结论 |
|---|---|
| redo 和 binlog 的区别 | 物理 vs 逻辑；InnoDB vs Server；恢复 vs 复制/PITR |
| 为什么需要 2PC | 两份日志粒度不同，必须保证崩溃时一致 |
| undo 的作用 | 回滚 + MVCC；崩溃恢复靠 redo |
| RR 能防幻读吗 | 快照读可以；当前读靠 next-key lock，特殊边界仍可能 |
| 长事务的危害 | undo 膨胀、锁等待、复制延迟、恢复变慢 |
| 双 1 | `sync_binlog=1` + `innodb_flush_log_at_trx_commit=1` |
| relay log 是什么 | 从库 IO 线程拉取的主库 binlog 副本，格式同 binlog |
| 慢日志没记录 | 检查 `long_query_time` 是否为会话级、`slow_query_log` 是否开 |
| general log 能常开吗 | 不能；开销大、可能泄露敏感 SQL |
| 误删恢复 | 全备 + binlog 重放；ROW 格式 + `binlog_row_image=FULL` 最好 |
