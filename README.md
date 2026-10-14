# 飞猪 P6 面试题配套实验代码

对应 `doc/飞猪P6-Java面试真题答卷-经核实版-v2.md` 的 25 题，纯 JDK 8 可运行，无需 MySQL/Redis/Kafka 等外部服务（中间件机制用最小模拟代码复现，跑起来看现象）。

统一运行方式（在项目根目录）：

```bash
mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.lab.q01.Q01HashMapLab
```

## 实验索引

| 题号 | 类 | 你会亲眼看到 |
|---|---|---|
| Q1 | `q01.Q01HashMapLab` | 链表≥8 但数组<64 时只扩容不树化；数组 64 后桶内变 TreeNode；扩容 = 原位 / 原位+oldCap |
| Q2 | `q02.Q02ConcurrentMapLab` | CHM 的 get+put 并发丢 5 万次更新；compute 零丢失；size 只是近似 |
| Q3 | `q03.Q03AqsLab` | 继承 AQS 20 行手写互斥锁；反射偷看 ReentrantLock/Semaphore/Latch 的 state 含义 |
| Q4 | `q04.Q04LockUpgradeLab` | JOL 看 mark word：无锁(001)→偏向(101)→轻量(00)→重量(10) 全链路 |
| Q5 | `q05.Q05VolatileLab` | volatile i++ 丢数 vs AtomicInteger 精确；DCL 为什么必须 volatile |
| Q6 | `q06.Q06ThreadLocalLab` | 线程池不 remove 串号；key=null 但 value 还在的泄漏尸检；0x61c88647 散列 |
| Q7 | `q07.Q07ThreadPoolLab` | 提交顺序实况：核心→入队→队列满才扩非核心→拒绝；4 种拒绝策略行为 |
| Q8 | `q08.Q08JvmMemoryLab` | 运行时内存池清单；爆栈深度；直接内存；字符串常量池 |
| Q9 | `q09.Q09GcLab` | 朝生夕死对象 Minor GC 全回收；长命对象逐轮晋升老年代 |
| Q10 | `q10.Q10ClassLoaderLab` | 双亲委派链打印；不同加载器加载同名类 instanceof 不认；准备(默认值) vs 初始化(<clinit>) |
| Q11 | `q11.Q11BPlusTreeLab` | 100 万主键树高 4 层 vs 二叉树 19 层；叶子链表范围查询 |
| Q12+Q13 | `q12.Q12MvccLab` | ReadView 5 条可见性规则可执行版：RC 不可重复读 / RR 可重复读 / 幻读边界 |
| Q14 | `q14.Q14RedisStructureLab` | SDS O(1) 长度+扩容策略；手写跳表 10 万 ZADD；embstr 44 字节数学 |
| Q15 | `q15.Q15SingleThreadLab` | 单线程串行(天然原子) vs 多线程 CAS 高竞争，Redis 单线程模型的道理 |
| Q16 | `q16.Q16RedisClusterLab` | 手写 CRC16-XMODEM（自校验 0x31C3）；hash tag 同槽；16384 的由来 |
| Q17 | `q17.Q17KafkaStorageLab` | 顺序写 vs 随机写实测差 1~2 个数量级；稀疏索引二分定位 |
| Q18 | `q18.Q18IdempotentProducerLab` | PID+seq 去重实况：重发被静默去重；重启换 PID 幂等失效；消费端唯一索引 |
| Q19 | `q19.Q19RebalanceLab` | Range 多 topic 先来者恒多；RoundRobin 均衡；C3 掉线 Sticky 只迁它的 |
| Q20 | `q20.Q20CircularDependencyLab` | 手写三级缓存：B 拿到的代理 == 容器最终暴露（一致 true）；二级缓存反例（一致 false） |
| Q21 | `q21.Q21BeanLifecycleLab` | 生命周期 9 回调顺序；JDK 动态代理；同类自调用切面拦截 0 次（事务失效现场） |
| Q22 | `q22.Q22AutoConfigureLab` | 条件装配三场景（没依赖跳过/用户优先/默认注册）；ServiceLoader 真 SPI |
| Q23 | `q23.Q23DubboSpiLab` | 读 META-INF/dubbo key=value 按需实例化；Wrapper 自动包裹（AOP 效果）；Adaptive 按 URL 路由 |
| Q24 | `q24.Q24DistributedLockLab` | 裸 DEL 删掉别人的锁→双持锁；Lua 原子比对删除；fencing token 拒绝旧写入 |
| Q25 | `q25.Q25SeckillLab` | 1000 人抢 100 件：裸 check-then-act **超卖 900 件**；synchronized/Atomic/Lua/MQ 四种救法 |

## 建议的练法

1. 先跑一遍看现象，对照 `>> 面试点` 注释行和文档里的题目互相印证
2. 把实验里的关键数字改掉再跑（如 Q1 的容量 64、Q25 的库存和并发数），观察结论变化
3. Q3/Q20/Q23 是"手写框架核心"类实验，适合合上代码自己重写一遍——这三题是 P6 面试区分度最高的
