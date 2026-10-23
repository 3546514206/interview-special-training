# Java 单机并发锁专题

> **目标**：把「单机并发锁」串成一条体系线——内存模型与可见性 → 锁的实现原理 → CAS 与原子类 → AQS 框架 → 可重入与公平性 → 读写锁 → StampedLock → 死锁 → 选型，覆盖 40 道面试题与 7 个可运行实验。
>
> **范围**：本专题只讨论**单 JVM 内**的并发控制。分布式锁不在本专题展开，参见文末说明。
>
> **风格**：结论先行；先说「是什么、为什么这么设计」，再说「源码在哪一步」；所有不确定的版本相关行为一律标注「需核实」。

## 文件导航

| 文件 | 内容 |
|---|---|
| [interview-questions.md](./interview-questions.md) | 面试题 40 题（LK01–LK40）：内存模型 / synchronized / CAS / AQS / 公平性 / 读写锁 / StampedLock / 死锁 / 选型；末尾附分布式锁占位表 D01–D10（待补） |
| [lab-guide.md](./lab-guide.md) | 实验教程 7 篇（Lab1 可见性 · Lab2 CAS 原子类 · Lab3 AQS · Lab4 死锁 · Lab5 读写锁 · Lab6 StampedLock · Lab7 选型基准） |
| [diagrams/lock-map.html](./diagrams/lock-map.html) | 单机锁全景交互图（四象限分类 + 点选查看原理 / 源码入口 / 适用 / 坑） |
| [diagrams/aqs-flow.html](./diagrams/aqs-flow.html) | AQS 获取释放流程交互图（独占 / 共享 / Condition 三条路径可切换高亮） |
| `src/main/java/edu/zjnu/interview/lock/` | 实验代码，按 `q<NN>/L<NN><Name>Lab.java` 组织，7 个实验对应 7 个可运行入口 |
| `src/main/java/edu/zjnu/interview/lock/q03/SimpleAqsStudy.java` | **附加动手篇**：简化版 AQS 骨架 + 简化版可重入锁（自己手写一遍，注释讲原理） |

## 一张图

```text
Java 单机并发锁
│
├─ 互斥锁（同一时刻只放行一个线程）
│   ├─ synchronized                 关键字 · JVM 内建 Monitor · 状态写在对象头 Mark Word
│   ├─ ReentrantLock                AQS 独占模式 · state 记录重入次数 · 可超时 / 可中断 / 可公平
│   └─ LockSupport                  park / unpark 原语，AQS 的底层阻塞唤醒机制，本身不提供互斥
│
├─ 读写锁（读读共享 · 读写互斥）
│   ├─ ReentrantReadWriteLock       AQS 共享 + 独占双计数 · 高 16 位读锁 / 低 16 位写锁
│   └─ StampedLock                   版本戳 + CAS · 乐观读不阻塞写 · 不可重入 · 需 validate
│
├─ 乐观锁（不预加锁 · 提交时校验冲突）
│   ├─ AtomicXXX / AtomicReference   volatile + CAS 自旋
│   ├─ AtomicStampedReference        引用 + 版本戳一起 CAS，解决 ABA
│   └─ LongAdder                     Cell 分段累加，高竞争下吞吐优于 AtomicLong
│
└─ 分段锁（切段 · 把全局竞争降为局部竞争）
    ├─ ConcurrentHashMap             JDK7 Segment + ReentrantLock 分段；JDK8 取消 Segment，改 CAS + 桶级 synchronized
    └─ Guava Striped                 散列到固定数量的锁（外部库，非 JDK）

共同底座
  AQS  AbstractQueuedSynchronizer —— state 状态位 + CLH 变体双向同步队列 + park / unpark 阻塞唤醒
  CAS  compare-and-swap —— 由 CPU 指令保证原子性，是乐观锁与 AQS 抢占的共同基础
  Monitor  JVM 内建的对象锁，入口 / 退出 / 阻塞唤醒逻辑与 AQS 高度同构（对应关系需核实源码）

作用域
  以上全部依赖单 JVM 内存语义，跨进程无效；跨进程互斥属于分布式锁，见文末说明
```

## 五类锁速查矩阵

| 维度 | `synchronized` | `ReentrantLock` | `ReentrantReadWriteLock` | `StampedLock` | `AtomicXXX` |
|---|---|---|---|---|---|
| 实现机制 | JVM 内建 Monitor，字节码 `monitorenter` / `monitorexit` | AQS 独占模式 | AQS 共享 + 独占双计数 | 版本戳 + CAS（不基于 AQS） | `volatile` 字段 + CAS 自旋 |
| 可重入 | 是，由 JVM 保证 | 是，`state` 计数 | 是，读锁与写锁各自可重入 | **否**，重复获取会自锁 | 不适用（单变量操作） |
| 公平性 | 不保证（偏向/轻量级路径下近似 barging） | 可选，默认非公平 | 可选，默认非公平 | 不提供 | 不适用 |
| 读写语义 | 纯互斥，读写都互斥 | 纯互斥 | 读读共享、读写互斥 | 读读共享；另有不占锁的乐观读 | 不适用 |
| 乐观读 | 无 | 无 | 无 | 有：`tryOptimisticRead` + `validate(stamp)` | 本身就是乐观更新 |
| 获取失败行为 | 阻塞等待，不可中断 | 阻塞；`lockInterruptibly` 可中断；`tryLock` 立即返回；`tryLock` + 超时可限时等待 | 同 `ReentrantLock`，另有 `readLock` / `writeLock` 之分 | 阻塞；`tryLock` 系列立即返回 | CAS 失败则自旋重试 |
| 中断与超时 | 不可中断、无超时 | 支持 `lockInterruptibly` / `tryLock(timeout)` | 同 `ReentrantLock` | 支持可中断获取：`readLockInterruptibly()` / `writeLockInterruptibly()`（JDK 8+ 就有）；`tryLock` 支持限时 | 不阻塞，无中断概念 |
| 适用场景 | 临界区简单、无需扩展能力的常规场景 | 需要超时 / 中断 / 公平 / 多条件队列 | 读多写少、临界区可读的数据结构 | 读远多于写且读操作很短的统计类场景 | 单变量自增、状态标记、无锁队列节点更新 |
| 主要风险 | 锁粒度写错即死锁；不可诊断 | `unlock` 必须配对且放 `finally` | 读锁不支持升级（持读锁取写锁：`tryLock` 返回 false，`lock()` 永久阻塞）；可能写饥饿 | 不可重入；乐观读必须 `validate`，否则读到撕裂数据 | 高竞争下 CAS 自旋浪费 CPU；复合操作不原子 |

> 补充说明：
> - `ReentrantReadWriteLock` 的读锁可重入，但**不支持锁升级**：持读锁再取写锁时 `tryLock` 返回 false，`lock()` 永久阻塞（实测另一线程处于 WAITING 且中断无效）；支持**锁降级**（写 → 读）。
> - `StampedLock` 的乐观读返回的是一个版本戳，读完必须 `validate(stamp)` 校验期间无写入，否则需要退化为悲观读锁重读。
> - `synchronized` 的偏向锁在 JDK 15 起默认禁用并废弃（JEP 374），JDK 18 移除实现（JDK-8256425），JDK 19+ 相关 VM 参数已不可用（`Unrecognized VM option`）；现代 JDK 的锁升级路径是「无锁 → 轻量级 → 重量级」。
> - `LongAdder.sum()` 不是某一瞬间的强一致快照，读取过程中并发写入会导致结果与真实值不严格对应。

## 推荐学习路线

| 阶段 | 主题 | 建议时间 | 对应题号 | 对应实验 |
|---|---|---|---|---|
| 1 | 内存模型与可见性 | 1 天 | LK01–LK06 | Lab1 可见性（LK06 DCL） |
| 2 | `synchronized` 的实现与锁优化 | 1 天 | LK07–LK12 | Lab1 可见性 |
| 3 | CAS 与原子类 | 1 天 | LK13–LK18 | Lab2 CAS 与原子类（LK15 LongAdder） |
| 4 | AQS 骨架、可重入、公平性与读写锁 | 2–3 天 | LK19–LK32 | Lab3 AQS（LK19）、Lab5 读写锁（LK29） |
| 5 | StampedLock、死锁与选型 | 2 天 | LK33–LK40 | Lab6 StampedLock（LK33）、Lab4 死锁（LK37）、Lab7 选型基准（LK40） |

阶段说明：

1. **阶段 1 先于一切**：可见性、原子性、有序性三个概念不澄清，后面的锁、原子类、AQS 都只是记结论。
2. **阶段 4 是本专题的重心**：AQS 是 `ReentrantLock` / `ReentrantReadWriteLock` / `CountDownLatch` / `Semaphore` 的共同底座，读写锁与可重入、公平性放在同一阶段对比着看。
3. **阶段 5 落到工程**：StampedLock 补齐读多写少的性能选项，随后是死锁的四个必要条件、预防手段与线上排查路径（`jstack` / `jcmd Thread.print` / JFR），最后落到「什么场景选哪把锁」的决策表。
4. 题号分布：⭐⭐⭐⭐⭐ 五星题 16 道（LK01/02/03/04/06/07/09/15/19/23/25/27/29/37/39/40），⭐⭐⭐⭐ 四星 17 道，⭐⭐⭐ 三星 5 道，⭐⭐ 二星 2 道。阶段 1、4、5 的五星题最密集。

## 面试速记 10 条

1. `synchronized` 是 JVM 内建锁，锁状态存在对象头 Mark Word 里；`ReentrantLock` 是 AQS 的独占实现，靠 `state` 计数实现可重入。
2. 两者都可重入，但 `synchronized` 不可中断、无超时、无公平选项，`ReentrantLock` 三样都有——这就是它存在的理由。
3. `ReentrantLock` 的 `unlock()` 必须在 `finally` 里调用，且次数要与 `lock()` 匹配，否则抛 `IllegalMonitorStateException` 或永久阻塞。
4. AQS 是框架不是锁：它把「获取失败入队 → 前驱释放后唤醒 → 重新竞争」这套流程固定下来，子类只实现 `tryAcquire` / `tryRelease`。
5. AQS 的 `state` 一个字段承载多种语义：独占模式是重入次数，共享模式是读锁计数，读写锁用高 16 位存读、低 16 位存写。
6. 公平与非公平的唯一差别在 `hasQueuedPredecessors()` 这个判断上：公平锁必须等队列里的前驱都轮完才允许插队，因此吞吐更低。
7. CAS 的三大问题是 ABA、只能保证单变量原子、高竞争下自旋浪费 CPU；ABA 用 `AtomicStampedReference` 的版本戳解决，高竞争用 `LongAdder` 的分段累加缓解。
8. `ReentrantReadWriteLock` 读锁可重入但**不支持升级**（持读锁取写锁：`tryLock` 返回 false，`lock()` 永久阻塞），支持**降级**（写→读），实现上就是 `state` 高低位拆分 + 独占 / 共享两套队列逻辑。
9. `StampedLock` 的乐观读靠版本戳判定：读之前拿戳，读完 `validate` 校验，校验失败说明期间有写入，必须重读；而且它不可重入、不支持 `Condition`。
10. 死锁排查靠 `jstack` / `jcmd Thread.print` 抓线程栈找环形等待，预防靠「固定加锁顺序 + 缩小锁粒度 + `tryLock` 限时」；选型上，常规临界区优先 `synchronized`，需要扩展能力才上 `ReentrantLock`。

## 分布式锁（暂未展开）

本专题暂不覆盖分布式锁。参考资料见 fliggyp6 专题 [Q24 分布式锁方案对比](../fliggyp6/飞猪P6-Java面试真题答卷-经核实版-v2.md) 与其配套实验 `src/main/java/edu/zjnu/interview/fliggyp6/q24/Q24DistributedLockLab.java`。

分布式锁专题计划覆盖的清单（编号与 [interview-questions.md 第八节](./interview-questions.md) 的占位表一一对应，全部待补，不列入学习阶段）：

| 编号 | 主题 | 状态 |
|---|---|---|
| D01 | 分布式锁的三个必要条件（互斥、防死锁、只能解自己持有的锁） | 待补 |
| D02 | Redis `SET NX EX` 实现与其原子性边界 | 待补 |
| D03 | Redisson 看门狗续期机制 | 待补 |
| D04 | 为什么需要续期？业务执行时间超过租期会怎样 | 待补 |
| D05 | ZooKeeper 临时节点 + Watch 机制的实现 | 待补 |
| D06 | 分布式锁的 fencing token 机制与必要性 | 待补 |
| D07 | 缓存与分布式锁的组合顺序问题 | 待补 |
| D08 | 分布式锁与本地锁的取舍边界 | 待补 |
| D09 | 主从切换 / 脑裂场景下分布式锁的正确性 | 待补 |
| D10 | 分布式锁的性能代价与替代方案（幂等、状态机、唯一约束） | 待补 |

## 参考

- JDK 源码：`java.util.concurrent.locks.AbstractQueuedSynchronizer`、`ReentrantLock`、`ReentrantReadWriteLock`、`StampedLock`、`java.util.concurrent.atomic` 包
- JVM 规范：`monitorenter` / `monitorexit` 指令语义与对象头 Mark Word 状态编码
- 偏向锁废弃与移除：[JEP 374（Deprecate and Disable Biased Locking）](https://openjdk.org/jeps/374)、[JDK-8256425（Remove Biased Locking in HotSpot）](https://bugs.openjdk.org/browse/JDK-8256425)
- 关联专题：[Q3 · AQS 原理全景](../fliggyp6/diagrams/q03-aqs.html)、[Q4 · 锁升级](../fliggyp6/diagrams/q04-lock-upgrade.html)
