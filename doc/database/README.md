# 数据库面试专题

> **目标**：大厂后端研发（P6/P7）
> **配套环境**：MySQL 8.0 容器 `<mysql-container>`（容器名，默认 `localhost:3306`，远程部署时改 `.env` 中 `DB_HOST`） → 库 `interview_lab`

## 目录

| 文件 | 内容 |
|---|---|
| [数据库面试真题答卷-经核实版-v1.md](./数据库面试真题答卷-经核实版-v1.md) | 25 题速记版答卷（基础/索引/事务/锁/日志/优化/架构/实战） |
| [sql/00_init.sql](./sql/00_init.sql) | 建库建表 + 种子数据（users / orders / login_logs） |
| [sql/q01_index.sql](./sql/q01_index.sql) | 索引 7 大失效场景 + 最左前缀 + 覆盖索引 |
| [sql/q02_transaction.sql](./sql/q02_transaction.sql) | 4 个隔离级别 + MVCC + 幻读 |
| [sql/q03_lock.sql](./sql/q03_lock.sql) | 行锁/表锁/间隙锁/共享/排他/死锁 |
| [sql/q04_explain.sql](./sql/q04_explain.sql) | EXPLAIN 12 字段 + 深分页优化 + 慢查询 |
| [sql/q05_window.sql](./sql/q05_window.sql) | 行转列 + TopN + 连续登录 + 留存率 |
| `src/main/java/edu/zjnu/interview/database/q01/PoolLab.java` | HikariCP 连接池实验（100 线程抢 10 连接） |
| `src/main/java/edu/zjnu/interview/database/q02/LockLab.java` | 行锁/间隙锁/死锁/当前读实验 |
| `src/main/java/edu/zjnu/interview/database/q05/BackToTableLab.java` | 回表 vs 覆盖索引 vs 全表扫 4 场景实验（100 万行 user_big） |
| [sql/q05_back_to_table.sql](./sql/q05_back_to_table.sql) | 配套 SQL：建表 + 灌 100 万行 + 4 条 EXPLAIN 演示 + 耗时对比 |
| [diagrams/q01-select-execution.html](./diagrams/q01-select-execution.html) | Q1 一行 SELECT 执行过程 · 11 帧动图 |
| [diagrams/q01-select-execution.gif](./diagrams/q01-select-execution.gif) | 同上 · 嵌 Markdown 用（26 秒循环） |
| [diagrams/q01-select-execution.png](./diagrams/q01-select-execution.png) | 同上 · 静态封面（看图须知帧） |
| [diagrams/q03-varchar-len.html](./diagrams/q03-varchar-len.html) | Q3 VARCHAR(100) vs VARCHAR(10) 4 层差异 · 7 帧动图 |
| [diagrams/q03-varchar-len.gif](./diagrams/q03-varchar-len.gif) | 同上 · 嵌 Markdown 用（17 秒循环） |
| [diagrams/q03-varchar-len.png](./diagrams/q03-varchar-len.png) | 同上 · 静态封面（看图须知帧） |

## 快速开始

```bash
# 0. 准备 .env (cp 模板后填实际值,.env 已在 .gitignore 中)
cp .env.example .env && $EDITOR .env

# 1. 初始化练习库 (199 用户 + 10 订单 + 31 登录日志,SQL 已自带 SET NAMES utf8mb4)
docker exec -i <mysql-container> mysql --default-character-set=utf8mb4 \
  -uroot -p"$DB_PASSWORD" interview_lab < doc/database/sql/00_init.sql

# 2. 跑任一练习脚本
docker exec -i <mysql-container> mysql --default-character-set=utf8mb4 \
  -uroot -p"$DB_PASSWORD" interview_lab < doc/database/sql/q05_window.sql

# 3. 跑 Java 实验 (依赖 .env 配置)
mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.database.q01.PoolLab
mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.database.q02.LockLab
```

> ⚠️ **关于字符集**：WSL/容器内 locale 不是 UTF-8 时,直接 `< file.sql` 重定向进 mysql
> 会触发"双重 UTF-8 编码"(中文变 latin1 字符)。两个修法:
>   (1) 0_init.sql 已带头部加 `SET NAMES utf8mb4;`,写入 OK
>   (2) 输出端加 `--default-character-set=utf8mb4` (推荐,所有 SQL 通用)

> 🔐 **关于敏感信息**：密码/IP 在 `.env` 中,不会 commit. Java Lab 用
> `edu.zjnu.interview.database.env.EnvLoader` 读取 `.env`. 模板见 `.env.example`.

## 进度

- [x] V1 经核实版答卷（25 题）
- [x] SQL 练习脚本（6 套：原 5 套 + q05 回表 100 万行实验）
- [x] Java Lab（3 个：q01 连接池 / q02 行锁 / q05 回表 4 场景）
- [x] HTML 配图 · Q1 一行 SELECT 执行过程（11 帧动图）
- [x] HTML 配图 · Q3 VARCHAR(100) vs VARCHAR(10) 4 层差异（7 帧动图）
- [ ] HTML 配图（Q12 MVCC / Q15 间隙锁 / Q25 连续登录）
- [x] ~~Q5/Q6 回表 4 帧动图~~ 改用 SQL + Java 实验直接验证（更准，比示意图靠谱）
- [ ] V2 进阶（Redis / 缓存三大问题 / 分布式锁）
- [ ] V3 大厂真题面经汇总（按公司：阿里/字节/腾讯/美团）

## Q5/Q6 · 回表 4 场景实验

> 关键澄清：「走 PK 索引树」歧义 —— 既可指 PK 条件定位（type=const/ref），也可指全表扫顺序遍历 PK 树（type=ALL, key=NULL）。`key=NULL` 不等于「没读 PK 树」。

**实验 SQL + Java 已就绪**（`user_big` 100 万行, age 均匀分布 20~60）：

| # | SQL | 预期 EXPLAIN | 物理路径 | 实测耗时参考 |
|---|---|---|---|---|
| ① | `SELECT * WHERE age > 20` | type=ALL, key=NULL | 顺序扫 PK 树 ~100 万行（97.5 万次回表 > 100 万行顺序 I/O） | 几秒 |
| ② | `SELECT * WHERE age = 25` | type=ref, key=idx_age, Extra=Using where | 扫 idx_age ~24400 叶子 + 24400 次回表 | 几百 ms |
| ③ | `SELECT id,age WHERE age = 25` | type=ref, key=idx_age, **Extra=Using index** | 扫 idx_age ~24400 叶子，**0 次回表** | 几十 ms |
| ④ | `SELECT * WHERE id = 12345` | type=const, key=PRIMARY, rows=1 | PK 树二分定位 1 行 | < 1ms |

跑法见 [`q05_back_to_table.sql`](./sql/q05_back_to_table.sql) 和 [`BackToTableLab.java`](../../src/main/java/edu/zjnu/interview/database/q05/BackToTableLab.java)。

## 参考

- [Snailclimb/JavaGuide](https://github.com/Snailclimb/JavaGuide) ⭐150k+
- [doocs/advanced-java](https://github.com/doocs/advanced-java) ⭐75k+
- [CyC2018/CS-Notes](https://github.com/CyC2018/CS-Notes) ⭐170k+