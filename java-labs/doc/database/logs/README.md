# MySQL 日志体系专题

> **目标**：把 MySQL 8.0 的日志体系串成一张图，覆盖「层级 / 写入者 / 写入时机 / 用途 / 配置 / 故障场景」，能应对 P6/P7 面试中的事务、崩溃恢复、主从复制、误删恢复问题。
>
> **风格**：先给结论，再给实验证据；所有实验都在 MySQL 8.0 的 `interview_lab` 上可复现。

## 文件导航

| 文件 | 内容 |
|---|---|
| [interview-questions.md](./interview-questions.md) | 日志体系面试题 35 题（全景 / binlog / redo / undo / 2PC / 运维 / 复制 / 场景） |
| [lab-guide.md](./lab-guide.md) | 8 个实验教程（error / general / slow / binlog / redo / undo / relay / 崩溃恢复） |
| [../sql/q09_logs.sql](../sql/q09_logs.sql) | 配套 SQL：开关检查、事件观察、LSN 对比、MVCC 双会话 |
| [../diagrams/q09-mysql-logs.html](../diagrams/q09-mysql-logs.html) | 日志家族全景图（8 类日志 + 用途矩阵 + 2PC + 场景速查） |
| [../diagrams/q09-logs-workflow.html](../diagrams/q09-logs-workflow.html) | **三大日志工作图**（redo / undo / binlog · 10 步可交互：UPDATE → 2PC → 崩溃恢复 → 复制/PITR） |
| [../diagrams/q09-mysql-architecture.html](../diagrams/q09-mysql-architecture.html) | MySQL 整体架构与日志落点（4 层 + 2PC 时序 + 崩溃恢复判断） |

## 一张图

```text
SQL 请求
  │
  ├─ Server 层
  │    ├─ error log         启动/关闭/错误/警告，排障第一现场
  │    ├─ general log       所有客户端语句，调试/审计（开销大）
  │    ├─ slow log          超过 long_query_time 的语句，性能定位
  │    ├─ binlog            逻辑变更，复制 / PITR
  │    └─ relay log         从库 IO 线程收到的 binlog 副本
  │
  └─ InnoDB 层
       ├─ redo log          物理页变更，WAL，崩溃恢复
       ├─ undo log          旧版本 / 回滚信息，MVCC + 回滚
       └─ DDL log           原子 DDL 的元数据日志（内部使用）

复制链路：
主库 binlog ──▶ 从库 IO 线程 ──▶ relay log ──▶ 从库 SQL 线程 ──▶ 从库数据

事务提交：
redo prepare ──▶ binlog write/fsync ──▶ redo commit
        └──────── 2PC：保证两份日志一致 ────────┘
```

## 8 类日志速查矩阵

| # | 日志 | 层级 | 格式 | 写入者 | 触发时机 | 主要用途 | 默认 |
|---|---|---|---|---|---|---|---|
| 1 | **error log** | Server | 文本 | mysqld | 启动/关闭/错误/警告 | 排障、复制状态 | 开 |
| 2 | **general query log** | Server | 文本 / 表 | mysqld | 每条客户端语句 | 调试、审计 | 关 |
| 3 | **slow query log** | Server | 文本 / 表 | mysqld | 执行时间超过阈值 | 性能定位 | 关 |
| 4 | **binary log** | Server | 二进制 | mysqld | 事务提交 / DDL | 复制、PITR | 开（8.0 默认） |
| 5 | **relay log** | Server（从库） | 二进制 | IO 线程 | 收到主库事件 | 从库中继重放 | 从库自动 |
| 6 | **DDL log** | InnoDB | 内部 | InnoDB | DDL 执行 | 原子 DDL、崩溃一致性 | 内部 |
| 7 | **redo log** | InnoDB | 二进制（物理） | InnoDB | 事务执行/提交 | 崩溃恢复、持久性 | 常开 |
| 8 | **undo log** | InnoDB | 二进制（逻辑） | InnoDB | INSERT/UPDATE/DELETE | 回滚、MVCC | 常开 |

> 说明：
> - 第 6 类 DDL log 是 InnoDB 内部元数据日志，用户通常不需要手工维护；本专题的实验覆盖其余 7 类。
> - MySQL 8.0.30+ 的 redo 容量由 `innodb_redo_log_capacity` 控制（默认 100MB）；8.0.30 之前是 `innodb_log_file_size × innodb_log_files_in_group`。
> - 从库的 relay log 不是「开关」，只要配置了复制就会自动产生。

## 三件套怎么协作

| 维度 | redo log | undo log | binlog |
|---|---|---|---|
| 层级 | InnoDB | InnoDB | Server |
| 内容 | 物理页变更（页号 + 偏移 + 修改） | 逻辑反向操作 + 旧版本 | 逻辑变更（ROW 模式是行镜像） |
| 写入时机 | 事务执行中持续写，提交时刷盘 | DML 执行时 | 事务提交时（2PC 第二阶段） |
| 文件 | `#innodb_redo/` 循环写 | undo tablespace | `binlog.00000N` 追加写 |
| 用途 | 崩溃恢复 | 回滚 + MVCC | 复制 + PITR |
| 生命周期 | 容量固定，循环覆盖 | 由 purge 线程清理 | 按 `binlog_expire_logs_seconds` 过期 |

**一句话串起来**：

- **redo** 保证「已提交的不丢」；
- **undo** 保证「未提交的可回滚」+「读一致性」；
- **binlog** 保证「可以复制、可以按时间点恢复」；
- **2PC** 保证 redo 和 binlog 在崩溃时不会各说各话。

## 推荐学习路线

| 阶段 | 主题 | 建议时间 | 对应内容 |
|---|---|---|---|
| 1 | 全景与分层 | 0.5 天 | 本文 + `diagrams/q09-mysql-logs.html` |
| 2 | **事务三件套**（重点） | 2–3 天 | 面试题 L03–L24；实验 4/5/6 |
| 3 | 2PC 与崩溃恢复 | 0.5 天 | 面试题 L22–L24；实验 8 |
| 4 | 运维日志 | 1 天 | 面试题 L25–L28；实验 1/2/3 |
| 5 | 复制与 relay log | 1 天 | 面试题 L29–L31；实验 7 |
| 6 | 场景题与 PITR | 0.5 天 | 面试题 L32–L35；实验 4 |

## 实验环境

- MySQL **8.0+**（部分语法如 `SHOW REPLICA STATUS`、`performance_schema.data_locks` 需要 8.0.22+）
- 练习库：`interview_lab`（先执行 [`../sql/00_init.sql`](../sql/00_init.sql)）
- 需要 `SUPER` / `SYSTEM_VARIABLES_ADMIN` 权限来切换全局日志开关
- relay log 实验需要一台从库（可选）；没有从库时可以先跳过实验 7
- ⚠️ 不要在正式库上做 `kill -9`、切 `general_log=ON`、`long_query_time=0` 这类实验

## 面试 60 秒答题模板

> MySQL 的日志分三层。Server 层有 error、general、slow、binlog 和 relay；InnoDB 层有 redo 和 undo；另外还有内部的 DDL log。  
> 事务相关最核心的是三件套：redo 是物理日志，负责崩溃恢复；undo 是逻辑日志，负责回滚和 MVCC；binlog 是 Server 层逻辑日志，负责复制和 PITR。  
> redo 和 binlog 通过两阶段提交保证一致：先写 redo prepare，再写 binlog 并 fsync，最后提交 redo。崩溃恢复时，根据 redo 状态和 binlog 是否完整决定提交还是回滚。  
> 运维上，error log 排障、slow log 找慢 SQL、general log 只在调试时短开；relay log 是从库 IO 线程拉取主库 binlog 后落地的中继日志。  
> 生产上通常开 `sync_binlog=1` + `innodb_flush_log_at_trx_commit=1`，并给 binlog 设置合理的过期时间。

## 关联

- 主卷速记：[数据库面试真题答卷-经核实版-v1.md](../数据库面试真题答卷-经核实版-v1.md) 的 **Q16 / Q17**
- 事务与 MVCC：[q02_transaction.sql](../sql/q02_transaction.sql)
- 日志全景图：[q09-mysql-logs.html](../diagrams/q09-mysql-logs.html)
- 架构与 2PC：[q09-mysql-architecture.html](../diagrams/q09-mysql-architecture.html)
