# java-labs

> 面试专项训练 · Java 实验子模块（JDK 8 + Maven）

本模块是聚合工程 `interview-special-training` 的一个子模块，承载全部 Java 实验代码与配套文档。

## 源码结构

| 包 | 内容 |
|---|---|
| `edu.zjnu.interview.lock` | 单机并发锁专题：可见性 / CAS / AQS / 死锁 / 读写锁 / StampedLock / 选型基准 / 线程通信 |
| `edu.zjnu.interview.fliggyp6` | P6 高并发专题：HashMap / AQS / 锁升级 / GC / MVCC / Kafka / Dubbo / 秒杀 等 25 个实验 |
| `edu.zjnu.interview.database` | 数据库专题：HikariCP 连接池 / 行锁间隙锁 / 回表对比 |
| `edu.zjnu.interview.kafka` | Kafka 生产者 / 消费者示例 |
| `edu.zjnu.interview.normal` | 多线程基础示例（线程启动 / 守护线程 / 中断 / join 等） |
| `edu.zjnu.interview.env` | `.env` 文件定位与解析（各子模块共用一份实现） |

## 文档

`doc/` 下按专题分目录：

- `doc/lock/` —— 锁与内存模型专题（[README](./doc/lock/README.md)、[实验教程](./doc/lock/lab-guide.md)、[面试题](./doc/lock/interview-questions.md)）
- `doc/fliggyp6/` —— P6 高并发答卷
- `doc/database/` —— 数据库专题（[README](./doc/database/README.md)）
- `doc/kafka/` —— Kafka 专题
- `doc/HCSD/` —— 高并发系统设计答卷
- `doc/ai/` —— AI 专题（含 RAG 实验）

## 运行方式

命令在 **仓库根目录** 执行（`-pl java-labs` 指定子模块，进程工作目录即本模块目录）：

```bash
mvn -q -pl java-labs compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q01.L01VisibilityLab
```

## 本地配置

`.env` 存放本机实验环境地址（数据库、Kafka 等），已被根 `.gitignore` 的 `**/.env` 忽略，不进版本库。

```bash
cp java-labs/.env.example java-labs/.env && $EDITOR java-labs/.env
```

配置读取顺序：系统环境变量 → `.env` → 代码内默认值。
