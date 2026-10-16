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
- [x] SQL 练习脚本（5 套）
- [x] Java Lab（2 个）
- [ ] HTML 配图（Q12 MVCC / Q15 间隙锁 / Q25 连续登录）
- [ ] V2 进阶（Redis / 缓存三大问题 / 分布式锁）
- [ ] V3 大厂真题面经汇总（按公司：阿里/字节/腾讯/美团）

## 参考

- [Snailclimb/JavaGuide](https://github.com/Snailclimb/JavaGuide) ⭐150k+
- [doocs/advanced-java](https://github.com/doocs/advanced-java) ⭐75k+
- [CyC2018/CS-Notes](https://github.com/CyC2018/CS-Notes) ⭐170k+