# Java 单机锁专题面试题 40 题

> **适用**：P6/P7 Java 后端面试。本专题只覆盖**单机 JVM 内**的锁与内存模型；MySQL 行锁/间隙锁/意向锁部分参见 [数据库答卷 Q13–Q15](../database/数据库面试真题答卷-经核实版-v1.md)（配套 SQL：`doc/database/sql/q03_lock.sql`），本文不重复展开。
>
> **标记说明**：⭐ 出现频率；**【一句话答】** 先给结论；**【展开】** 答题细节；**【追问】** 面试官可能继续问；**【易错】** 常见误区。
>
> **配套实验**：`src/main/java/edu/zjnu/interview/lock/` 下 Lab1 可见性 / Lab2 CAS 与原子类 / Lab3 AQS / Lab4 死锁 / Lab5 读写锁 / Lab6 StampedLock / Lab7 选型基准。
>
> **版本口径**：本文以 **JDK 17** 为基线（配套实验在 JDK 17 上实跑）；字节码层面的结论以可复核的 `javap` 输出为准并标注；JDK 8 与 JDK 17 的差异单独成段标注（如偏向锁、轻量级锁相关）；JDK 15/18/21 的行为变更写明对应 JEP / Bug 号。

## 一、内存模型与可见性

### LK01 · JMM 的三大特性是什么？分别靠什么保证？⭐⭐⭐⭐⭐

**【一句话答】** 原子性、可见性、有序性。三者都不是硬件自动给的，必须靠 JMM 规定的同步手段（锁、`volatile`、`final`、并发容器）显式保证。

**【展开】**

| 特性 | 含义 | 主要保证手段 | 典型反例 |
|---|---|---|---|
| 原子性 | 操作不可分割 | `synchronized` / CAS / 原子类 | `count++` 是「读-改-写」三步 |
| 可见性 | 一个线程的写对其他线程及时可见 | `volatile` / `synchronized` / `final` | 普通字段的写可能滞留在缓存中 |
| 有序性 | 编译期/CPU 重排后语义不变 | `volatile` / `synchronized` / happens-before | 指令重排、处理器乱序执行 |

- 硬件根源：多核 CPU 的每核 L1/L2 与共享 L3、Store Buffer、编译期指令重排、CPU 乱序执行。
- JMM 的定位：Java 规范不规定具体硬件行为，只规定「必须保证什么」，从而给编译器和 CPU 留出优化空间。x86 是相对强内存序（TSO），ARM 是弱内存序，JMM 屏蔽了这层差异。
- 三者有依赖关系：有序性部分依赖可见性；`synchronized` 三者都给；`volatile` 给可见性和特定的有序性，**不给原子性**。

**【追问】**
- JMM 和「Java 运行时数据区划分（堆/栈/方法区）」是一回事吗？
  - 不是。数据区讲的是内存位置，JMM 讲的是读写行为与可见性规则。
- `synchronized` 为什么三个特性都给？
  - 临界区内的读写有全序（有序性）、退出前刷新（可见性）、互斥进入（原子性）。

**【易错】**
- 把 JMM 等同于「运行时数据区划分」。
- 认为「原子性 = 不可见」——两者正交，见 LK05。

---

### LK02 · happens-before 的规则有哪些？怎么用它推导可见性？⭐⭐⭐⭐⭐

**【一句话答】** happens-before 是 JMM 中定义「A 的结果对 B 可见且 A 在 B 之前」的偏序关系。JSR-133 §17.4 给出 **8 条**规则：程序顺序、监视器锁、volatile 变量、线程启动、线程终止、线程中断、对象终结器，以及第 8 条「传递性」。

**【展开】**

| # | 规则 | 内容 | 例子 |
|---|---|---|---|
| 1 | 程序次序规则 | 同一线程内，前面的操作 hb 后面的 | `int x=1; int y=x;` |
| 2 | 监视器锁规则 | 对一个锁的 unlock hb 后续对它的 lock | `synchronized(m){}` 退出 → 另一线程进入 |
| 3 | volatile 变量规则 | 对 volatile 变量的写 hb 后续对它的读 | `volatile boolean flag` |
| 4 | 线程启动规则 | `Thread.start()` hb 该线程内的任何操作 | `start()` → 子线程读已初始化字段 |
| 5 | 线程终止规则 | 线程内所有操作 hb 另一线程 `join()` 返回后 | `t.join()` 返回后可见 t 的全部修改 |
| 6 | 线程中断规则 | `interrupt()` hb 被中断线程检测到中断事件 | `interrupt()` → `Thread.interrupted()` 为 true |
| 7 | 对象终结规则 | 对象构造完成 hb `finalize()` 开始 | 构造器发布的安全性依据 |
| 8 | **传递性** | A hb B 且 B hb C，则 A hb C | 链式推导 |

> 说明：JSR-133 §17.4 实际列出的是 **8 条**，第 8 条即「传递性」。流传的「7 条」说法是把传递性排除在外后的计数，本表按 8 条口径。

- **happens-before 不等于物理时间先后**：没有 hb 关系的操作物理上也可能有先后，只是没有可见性保证。
- 类初始化规则（补充）：类的静态初始化完成 hb 任何线程首次访问该类，这是静态内部类 Holder 单例能安全发布的依据。

**【追问】**
- volatile 变量规则 vs 监视器锁规则的区别？
  - 前者是「单个 volatile 变量的写-读」配对，不涉及临界区；后者是「锁的释放-获取」配对，临界区内所有写都被覆盖。
- 线程启动规则是不是意味着 `start()` 之前的所有写对子线程可见？
  - 必须能连成 hb 链（「构造器内写 → 构造完成 → start」），不是无条件可见。

**【易错】**
- 把 happens-before 理解成「物理时间上先发生」。
- 漏掉线程启动/终止规则，误以为 `start()` 之前的一切自动可见。

---

### LK03 · volatile 保证什么？不保证什么？⭐⭐⭐⭐⭐

**【一句话答】** volatile 保证**可见性**和**禁止特定重排带来的有序性**，并保证 volatile 变量的单次读写不可分割（`long`/`double`）；但**不保证复合操作的原子性**。

**【展开】**
- 四条常见语义归纳：
  1. 保证可见性：写线程对 volatile 变量的写，后续读线程一定能读到最新值。
  2. 禁止指令重排：volatile 写之后的操作不能重排到写之前；volatile 读之前的操作不能重排到读之后。
  3. 保证「写之前的数据先刷出去」：volatile 写之前的普通写不会被重排到 volatile 写之后。
  4. 保证 `long`/`double` 读写的原子性：JMM 规定非 volatile 的 `long`/`double` 读写可能被拆成两次 32 位操作，volatile 后不可再拆分。
- 三条保证小结：**可见性保证、有序性保证、单次读写原子性保证（针对 long/double）**。
- 底层依赖：volatile 读写两侧插入内存屏障 + CPU 一致性缓存协议（MESI）。HotSpot 中屏障的具体组合随版本实现不同，具体屏障序列需核实。
- 与 happens-before 的对应：JSR-133 §17.4 的 **8 条**规则中，第 3 条就是「volatile 变量规则」——对 volatile 变量的写 hb 后续对它的读，这是 volatile 可见性的规范依据；第 8 条传递性用于链式推导。

**【追问】**
- volatile 读写和普通字段读写，机器层面差在哪？
  - volatile 走内存屏障并绕过/失效 CPU 的寄存器与 Store Buffer 优化；普通读可能被 JIT 提升到寄存器，普通写可能滞留在线程私有缓存中。
- `volatile` 一定能代替锁吗？
  - 不能。只适合「单写者 + 状态标记 + 单次赋值」的场景；任何「读-改-写」都不行。

**【易错】**
- 认为 volatile 保证了「所有操作的原子性」。
- 只记得「禁止重排」，忘了可见性——没有可见性保证，重排问题无从谈起。

---

### LK04 · 为什么 volatile 不能保证 `count++` 的正确性？⭐⭐⭐⭐⭐

**【一句话答】** `count++` 是「读-改-写」复合操作，volatile 只保证每次读写本身正确，无法保证这三步之间不被打断，多线程下必然丢更新。

**【展开】**
```
线程 A                     线程 B
int v = count;   // 读到 5   int v = count;   // 读到 5
count = v + 1;   // 写回 6   count = v + 1;   // 写回 6
```
- 结果是 6，而不是 7。
- 修法：
  - `AtomicInteger.incrementAndGet()`（CAS 循环）——见 Lab2；
  - `synchronized` 包住整个读-改-写；
  - `LongAdder`（高竞争累加统计）——见 LK15。
- 正确用 volatile 的典型场景：`volatile boolean running`（单线程写、其他线程读）、`volatile` 引用指向一个**整体替换**的不可变对象。

**【追问】**
- volatile int 和 AtomicInteger 的差别在哪一层？
  - volatile int 只解决「看到的是不是最新值」；AtomicInteger 用 CAS 同时解决「读-改-写是否原子」。
- 单线程下 volatile 有额外开销吗？
  - 有：它阻止 JIT 把变量缓存到寄存器等优化，并引入屏障开销；只是量级远小于线程切换。

**【易错】**
- 用 volatile 修 `count++`，压测「大部分时候是对的」就以为安全。丢失更新是必然的，只是概率问题。
- 把「volatile 引用 + 整体替换不可变对象」误用成「volatile 引用 + 原地修改对象字段」——后者仍然不安全。

---

### LK05 · 可见性和原子性有什么区别？volatile 属于哪一种？⭐⭐⭐⭐

**【一句话答】** 原子性指操作不可分割，可见性指一个线程的写能被其他线程看到。两者正交：`synchronized` 两者都给，CAS 原子类两者都给，**volatile 只给可见性（加部分有序性），不给原子性**。

**【展开】**

| 手段 | 原子性 | 可见性 | 有序性 |
|---|---|---|---|
| `synchronized` | 有 | 有 | 有 |
| `volatile` | 无 | 有 | 部分（禁止特定重排） |
| CAS / `AtomicXxx` | 有 | 有 | 有（volatile 语义） |
| `final` 字段 | 无 | 有（构造完成后安全发布） | 有 |
| 普通字段 | 无 | 无 | 无 |
| `start()` / `join()` | — | 有 | 有 |

- 单线程内 `x = 1` 天然原子（引用或基本类型的单次赋值不会被撕裂）；多线程下「读到半个值」才是原子性要解决的问题。
- 原子性 ≠ 可见性：读-改-写复合操作即使每次读都拿到最新值，整体仍可能不原子（LK04）。
- 可见性 ≠ 原子性：volatile 标志位线程安全地传递「状态」，但不能用它做计数（LK04）。

**【追问】**
- 复合操作里的「原子性」是什么粒度？
  - JMM 不保证任何复合操作原子，需要同步手段把整个复合操作变成临界区，或用 CAS 循环。
- 「读到的一定是最新的」和「读到的是某个合法值」是同一件事吗？
  - 不是。原子性保证「值不撕裂」，可见性保证「值不滞后」。

**【易错】**
- 把 volatile 说成「保证了线程安全」。准确说法是：volatile 保证变量的读写对其他线程可见，不保证围绕它的复合逻辑正确。
- 认为没有可见性问题就说明代码没有并发风险。

---

### LK06 · 双重检查锁（DCL）创建单例，为什么实例变量必须 volatile？⭐⭐⭐⭐⭐

**【一句话答】** 因为 `new` 的「分配内存 → 初始化 → 引用赋值」三步可能被重排成「分配 → 引用赋值 → 初始化」，没有 volatile 时另一个线程会拿到「已分配但未初始化完成」的对象。

**【展开】**
```java
public final class Singleton {
    private static volatile Singleton instance;
    private Singleton() {}
    public static Singleton getInstance() {
        if (instance == null) {                 // 第一次检查：无锁快速路径
            synchronized (Singleton.class) {
                if (instance == null) {         // 第二次检查：持锁复查
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }
}
```
- 没有 volatile 的问题：`new Singleton()` 编译后是「分配内存 / 调用构造器 / 把引用指向内存」三步，②③ 可被重排。其他线程在第一次检查看到 `instance != null`，但对象字段还是默认值（0/null）。
- 加 volatile 的作用：volatile 写（引用赋值）hb 后续读（第一次检查），保证「发布」在「初始化」之后；同时禁止相关的指令重排。
- 版本差异：JDK 5 之后 volatile 的语义才足以支撑 DCL 的正确性；更早版本规范上无法保证。
- 工程上更推荐的替代：
  - 静态内部类 Holder：`private static class Holder { static final Singleton INSTANCE = new Singleton(); }`——靠类初始化保证线程安全，读取路径完全无锁；
  - 枚举：`enum Singleton { INSTANCE; }`——天然单例，天然防反射与反序列化重建。
- 对应实验：Lab1 可见性中用 DCL 的反例展示「未加 volatile 时读到半初始化对象」。

**【追问】**
- 静态内部类 Holder 为什么线程安全？
  - JVM 保证类的静态初始化（`<clinit>`）只执行一次并由类加载锁保护；类初始化完成 hb 任何线程对该类的首次访问（JSR-133 明确规定）。
- DCL 省下了什么？
  - 大多数读路径不走 `synchronized`，省掉阻塞与唤醒开销；读多写少时收益明显。

**【易错】**
- 只说「加 volatile 就行」，说不出重排细节——追问会立刻卡住。
- 忘记 volatile 就写 DCL。
- 用枚举/静态内部类时还额外加 volatile（无害但没必要）。

---

## 二、synchronized 与锁实现

### LK07 · `synchronized` 在字节码层面是怎么实现的？⭐⭐⭐⭐⭐

**【一句话答】** 同步代码块由编译器翻译成 `monitorenter` / `monitorexit` 字节码；`synchronized` 方法不生成这两条指令，而是给方法打上 `ACC_SYNCHRONIZED` 标志，由 JVM 在方法调用时隐式加锁。

**【展开】**
- 同步块字节码形态（`javap -c` 可验证）：
```
 0: aload_0
 1: getfield  lock
 4: dup
 5: astore_1
 6: monitorenter          // 进入临界区
 7: ... 临界区 ...        // monitorexit（正常出口）
    monitorexit
    goto  end
    astore_2
    monitorexit          // 异常出口，保证释放
    aload_2
    athrow
end:
```
- 同步方法：`public synchronized void m();` 编译后方法带 `flags: (0x0020) ACC_SYNCHRONIZED`，方法体内无 `monitorenter/monitorexit`；JVM 在方法入口获取 monitor、方法退出（含异常）时释放。
- JVM 规范上：`monitorenter` 返回 `void`；`monitorexit` 返回 `int`（0 正常退出，1 异常退出）。HotSpot 中这两条指令的具体 native 实现细节需核实。
- 编译器保证 `monitorexit` 在所有出口成对出现，这是 `synchronized` 能自动释放锁的基础；手写的 `Lock` 必须自己写 `finally`。
- 静态同步方法锁的是 `Class` 对象（`Foo.class`），与实例方法锁的 `this` 不是同一把锁，二者互不排斥。

**【追问】**
- 为什么同步方法不需要字节码指令？
  - 语义等价，但少两条指令和一段异常表，JVM 在方法调用层统一处理更简洁。
- 同一把锁的 `synchronized` 方法和同步块能互斥吗？
  - 能，只要锁的是同一个对象。

**【易错】**
- 认为 `synchronized` 方法是「方法体内加锁」——加锁发生在方法入口、释放在方法出口。
- 以为 `synchronized` 也要写 `finally` 释放。

---

### LK08 · `synchronized` 和 `ReentrantLock` 的可重入是怎么实现的？⭐⭐⭐⭐

**【一句话答】** 可重入指同一线程可重复获取同一把锁。`synchronized` 靠 HotSpot 在对象头/monitor 中维护重入计数，`ReentrantLock` 靠 AQS 的 `state` 充当重入计数。

**【展开】**
- `ReentrantLock`（**JDK 17 javap 字节码已复核**）：
  - 获取：JDK 17 把「首次获取 + 重入」都放在 `NonfairSync#initialTryLock()` 里——`state == 0` 时 CAS 到 1 并记录 owner；owner 是当前线程则 `state + 1`，超过上限抛 `Error("Maximum lock count exceeded")`。`NonfairSync#tryAcquire(int)` 只负责「非重入」的那条路径（`state == 0` 时 CAS），供 AQS 的 `acquire()` 调用。
  - 释放：`tryRelease` —— 校验 owner 是自己（否则抛 `IllegalMonitorStateException`），`state - 1`，减到 0 时清空 owner 并唤醒后继。
- `synchronized`：HotSpot 在轻量级锁状态下，mark word 指向栈上的 Lock Record，重入时追加新的 Lock Record 形成链；重量级锁（`ObjectMonitor`）侧有重入计数字段。字段归属与命名随 HotSpot 版本变化，具体实现需核实。
- **JDK 8 与 JDK 17 的差异**：JDK 8 的 `ReentrantLock$Sync#nonfairTryAcquire` 一个方法同时处理首次获取与重入；JDK 17 拆成 `Sync#initialTryLock()`（首次 + 重入预筛）与 `FairSync/NonfairSync#tryAcquire()`（AQS 队列里的重试路径）两步，语义不变、结构不同。
- 可重入的价值：避免「自己再拿自己需要的锁」导致的自锁死；也让 `synchronized` 方法之间可以安全互相调用，锁对象可以作为参数传递。
- 不可重入锁的失败模式：同一线程二次加锁会把自己挂起，等自己释放 → 永久自死锁。

**【追问】**
- 可重入会带来什么代价？
  - 每个线程要维护重入计数和 owner 信息（`synchronized` 在对象头/栈上，`ReentrantLock` 在 `state` + `exclusiveOwnerThread`）。
- 可重入和「可中断」是同一个维度吗？
  - 不是。可重入说的是「同一线程能否重复拿」，可中断说的是「等待中能否被中断唤醒」，两者独立。

**【易错】**
- 说 `synchronized` 不可重入——它是可重入的，只是实现藏在对象头里。
- 把「可重入」误解为「可以从任意线程释放」——只有持有者能释放。

---

### LK09 · 锁升级是怎么回事？偏向锁现在还有效吗？⭐⭐⭐⭐⭐

**【一句话答】** HotSpot 历史上锁有四种状态：无锁 → 偏向 → 轻量级 → 重量级，按需单向升级（不降级）。**偏向阶段在 JDK 15 默认禁用并废弃（JEP 374）、JDK 18 实现被移除（Obsolete Biased Locking），现代 JDK 的实际路径是「无锁 → 轻量级 → 重量级」，没有偏向阶段。**

**【展开】**

| 状态 | mark word 存什么 | 适用场景 | 竞争后走向 |
|---|---|---|---|
| 无锁 | 对象哈希、GC 年龄、偏向标志位 | 刚创建、无人访问 | 首个线程进入 → 偏向（JDK 15 前） |
| 偏向 | 偏向线程 ID + 时间戳 | 单线程反复进入同一对象 | 他线程进入 → 撤销偏向 → 轻量级 |
| 轻量级 | 指向栈中 Lock Record 的指针 | 线程交替进入、临界区短 | 同刻多线程竞争 → 重量级 |
| 重量级 | 指向 `ObjectMonitor` | 竞争激烈、临界区长 | 阻塞 + park/unpark |

- 升级路径单向：`偏向 → 轻量级 → 重量级`，不会降级。
- 触发条件：撤销偏向——其他线程访问被偏向的对象，撤销需在 safepoint 完成；轻量 → 重量——CAS 失败且对象头已被改成其他形态，或同刻多线程竞争 → 对象膨胀为 `ObjectMonitor`。
- **版本差异（已核实）**：
  - **JDK 15（JEP 374）**：偏向锁默认禁用，相关参数全部废弃；仍可用 `-XX:+UseBiasedLocking` 打开，但会打印 warning。
  - **JDK 17（本基线，已实测）**：`UseBiasedLocking` 默认 `false`；显式 `-XX:+UseBiasedLocking` 仍被接受，但打印 `Option UseBiasedLocking was deprecated in version 15.0 and will likely be removed in a future release.`；`BiasedLockingStartupDelay`(0)、`BiasedLockingBulkRebiasThreshold`(20)、`BiasedLockingBulkRevokeThreshold`(40)、`BiasedLockingDecayTime`(25000) 仍是产品级 flag 存在；`PrintBiasedLockingStatistics` 同样带 15.0 废弃警告。
  - **JDK 18（JDK-8256425，Obsolete Biased Locking）**：偏向锁实现被移除，相关 VM 参数标记为 obsolete。
  - **JDK 19+**：`UseBiasedLocking` 等参数直接报 `Unrecognized VM option`，JVM 起不来。
  - **现代 JDK 的当前路径**：无锁 → 轻量级 → 重量级，**无偏向阶段**。
- **JDK 15 废弃的偏向锁相关参数**：`UseBiasedLocking`、`BiasedLockingStartupDelay`、`BiasedLockingBulkRebiasThreshold`、`BiasedLockingBulkRevokeThreshold`、`BiasedLockingDecayTime`、`UseOptoBiasInlining`、`PrintBiasedLockingStatistics`、`PrintPreciseBiasedLockingStatistics`。

**【追问】**
- 重量级锁为什么慢？
  - 涉及内核态 `park/unpark`、线程上下文切换，且唤醒顺序不精确。
- 能避免锁升级吗？
  - 不能，升级是并发安全的必要代价；能做的是缩短临界区、减少竞争面。

**【易错】**
- 认为每次进入 `synchronized` 都要走一遍升级流程——只有首次与竞争点才升级。
- 认为偏向锁在当前 JDK 仍然默认开启——JDK 15 起默认禁用，JDK 19 起连参数都不认了。

---

### LK10 · 轻量级锁的 Lock Record 是什么？为什么不直接存线程 ID？⭐⭐⭐⭐

**【一句话答】** 轻量级锁把对象 mark word 替换成「指向当前线程栈帧中 Lock Record 的指针」，Lock Record 里保存被替换掉的原 mark word（displaced mark word）；不直接存线程 ID 是因为撤销时只需一次写回、无需区分 mark word 的多种含义。

**【展开】**
- Lock Record 是**线程栈上的本地变量**（不是堆对象），主要字段：
  - `displaced_mark_word`：进入临界区前的 mark word，解锁时写回对象头；
  - `owner`：锁对象引用；
  - `stack_lock`：栈锁标记位，标识这是栈上分配的锁记录。
- 简化流程：复制对象 mark word 到 `displaced_mark_word` → CAS 把 mark word 替换为「指向 Lock Record 的指针」（失败说明已有线程持有）→ 撤销时把 `displaced_mark_word` 写回对象头。
- 与重量级锁对比：重量级锁的 mark word 指向堆上的 `ObjectMonitor`（对象膨胀后产生），里面才有 owner、EntryList、WaitSet 等结构。
- 为什么用栈上 Lock Record：撤销只需「写回原值」，不必判断当前 mark word 是偏向锁时代的线程 ID 还是重量级指针；且不新增堆对象。
- 具体字段名与内存布局随 HotSpot 版本变化，源码字段名需核实。

**【追问】**
- 轻量级锁「不像重量级锁那样有真正的互斥」，安全靠什么？
  - 靠获取动作本身的原子 CAS：只有一个线程能成功把 mark word 替换成自己的 Lock Record 指针，CAS 的原子性就是互斥的本质。
- 什么情况下轻量级锁失效？
  - 多线程同刻竞争、持有锁期间执行了重量级操作、或 CAS 失败后无法安全撤销。

**【易错】**
- 说「轻量级锁在对象头存线程 ID」——存的是栈上 Lock Record 的指针（实现细节随版本变化，需核实）。
- 认为轻量级锁「不保证互斥」。

---

### LK11 · 什么是自适应自旋？为什么它能提升性能？⭐⭐⭐

**【一句话答】** 自适应自旋让竞争失败的线程先空转若干次再阻塞，避免「阻塞-唤醒」的线程切换开销；自旋次数由「上一次在同一个锁上的自旋结果」动态决定，HotSpot 从 JDK 6 开始引入。

**【展开】**
- 收益场景：临界区极短（几十纳秒级）时，线程切换成本远大于自旋等待成本。
- 自适应策略：
  - 上一次在某个锁上自旋**成功**过 → 本次允许更长的自旋（受最大自旋次数上限约束）；
  - 上一次自旋**失败**（发生了真正竞争）→ 本次几乎不自旋，直接阻塞。
- 相关参数（**已在产品版 JDK 17 实测**）：自适应自旋没有用户可见开关，`-XX:+UseAdaptiveSpinning`、`-XX:PreSpinSpin` 只存在于 develop/debug 构建，产品版直接报 `Unrecognized VM option`（JDK 8 上结论相同）；产品版可见的相近旋钮是 `-XX:PreInflateSpin`（默认 10）与 `-XX:SafepointSpinBeforeYield`（默认 2000）。自旋循环中会使用 `pause` 类指令降低功耗与流水线冲刷。
- 与「CAS 失败重试」不同：后者是无锁算法的语义重试，自旋是锁竞争时的等待手段。
- 现代 JDK 中竞争激烈时 AQS 直接 `park`，自旋只是优化。

**【追问】**
- 自旋很烧 CPU，为什么还值得？
  - 只在「持锁时间短、竞争线程少」时划算；自适应机制就是为了在不值得时快速放弃。
- 虚拟线程场景下还需要自旋吗？
  - 虚拟线程（JDK 21，JEP 444）阻塞时不占用载体线程，不需要靠自旋「占住」CPU；HotSpot 在虚拟线程下的具体实现需核实。

**【易错】**
- 认为自适应自旋是 JDK 6 之前就有的——JDK 6 才引入。
- 认为自旋一定优于阻塞——临界区长时自旋是纯浪费。

---

### LK12 · 锁消除和锁粗化是什么？谁做的？⭐⭐⭐⭐

**【一句话答】** 锁消除是 JIT 通过逃逸分析判定锁对象线程私有，直接删掉同步块；锁粗化是把连续针对同一对象的加锁解锁合并成一次，扩大临界区。两者都由 JIT 编译器在运行期完成。

**【展开】**
- **锁消除（Lock Elision）**：
  - 前提：逃逸分析证明锁对象是线程私有的（栈上分配、不逃逸出方法）。
  - 例子：方法内 `new Object()` 后立刻 `synchronized` 它——对象不可能被其他线程拿到，同步块被删除。
  - 消除的是「不可能产生竞争的同步」，不改变语义。
- **锁粗化（Lock Coarsening）**：
  - JIT 发现相邻同步块锁同一对象、且中间没有跳出同步块的分支，就合并。
  - `synchronized(a){x++} synchronized(a){y++}` → `synchronized(a){x++; y++;}`。
  - 目的：减少「加锁-解锁-加锁」的抖动与线程切换。
  - 代价：临界区变大、持锁时间变长，高竞争下可能更差。
- 两者都是 HotSpot C2 的编译期优化（**已在产品版 JDK 17 实测**）：`-XX:-EliminateLocks` 是产品版可用开关（`{C2 product}`，默认 true）；`-XX:-LockCoarsening` 在产品版不可用（报 `Unrecognized VM option`），锁粗化在产品构建中是默认行为，只有 develop/debug 构建才留了开关。
- 关键区别：**消除 = 锁消失；粗化 = 锁变大**。

**【追问】**
- 逃逸分析会误判导致线程不安全吗？
  - 不会。逃逸分析是保守优化：只有能证明对象线程私有才消除锁，不能证明就保留。
- 什么时候锁粗化帮倒忙？
  - 临界区变大后持锁时间变长，高竞争场景降低并发度。

**【易错】**
- 把锁消除和「对象不可变」混淆——不可变不等于线程私有。
- 认为 JIT 优化后 `synchronized` 就「没有开销」——只有能证明线程私有的场景才成立。

---

## 三、CAS 与原子类

### LK13 · CAS 是什么？它的缺点有哪些？ABA 怎么来的？⭐⭐⭐⭐

**【一句话答】** CAS 是 compareAndSwap：原子地比较内存值与期望值 V，相等则写入新值并返回 true，否则返回 false。它是乐观锁的基础，无锁算法靠 CAS + 重试实现；主要缺点是无法保证多变量一致、存在 ABA。

**【展开】**
- 语义：`compareAndSwap(expected, update)`。JDK 8 底层是 `sun.misc.Unsafe` 的 `compareAndSwapInt/Long/Object`（native，对应 CPU 原子指令）；**JDK 9 起官方推荐用 `java.lang.invoke.VarHandle` 的 `compareAndSet` 替代 Unsafe**。
- 典型用法（CAS 循环）：
```java
int prev, next;
do {
    prev = ref.get();
    next = prev + 1;
} while (!ref.compareAndSet(prev, next));
```
- **ABA 问题**：CAS 只比较「值」，不关心这个值被写过几次。
  - 经典场景：无锁栈顶 A → B → A。线程 T1 读到 A 准备 CAS(A→C)，中途 T2 弹出 A、弹出 B、又压回 A；T1 的 CAS 因值仍为 A 而成功，但它基于的是过期的假设，栈的中间状态已被破坏。
  - 在「值单调变化」的纯计数器场景，ABA 无害；在「指针/对象引用 + 对象复用」场景是真实风险。
- 其他缺点：只能保证单个变量；高竞争下失败重试浪费；不能覆盖多个变量的一致性。

**【追问】**
- 为什么 CAS 被称为「乐观锁」？
  - 假设临界区短、冲突少，先做后校验，失败再重试——与数据库的版本号乐观锁同源。
- 为什么不能直接用 `synchronized` 实现所有更新？
  - 无竞争时 `synchronized` 要走对象头状态变更与可能的线程状态切换；CAS 在低竞争下只有一次 CPU 原子指令，更轻。

**【易错】**
- 认为 CAS 能保证「多个字段一起更新」——只能一个变量。
- 忘了 ABA 在无锁栈/队列里的经典复现。

---

### LK14 · ABA 怎么解决？`AtomicStampedReference` 是怎么工作的？⭐⭐⭐⭐

**【一句话答】** 给引用配一个版本戳，CAS 时同时比较「引用」和「版本戳」；引用回到原样但版本号已变，CAS 就会失败。JDK 提供 `AtomicStampedReference`（带 int 版本戳）和 `AtomicMarkableReference`（带 boolean 标记）。

**【展开】**
- `AtomicStampedReference<T>`：
  - 内部把引用和版本戳放在同一个不可变对象（`Pair`）里，保证「引用+戳」作为一个整体被原子替换；
  - `getReference()` / `getStamp()` 分别读取；`compareAndSet(expectedRef, newRef, expectedStamp, newStamp)` 是四参数版本。
  - 读取时若需要引用和戳的一致快照，必须一次取到配对对象再取两个字段（API 细节随版本不同，需核实）。
- `AtomicMarkableReference<T>`：引用 + 一个 boolean 标记，适合「对象是否被逻辑删除/标记」这类场景。
- 版本戳实现要点：每次成功 CAS 把 stamp 递增；用 `int` 存在极端场景下的回绕问题。
- 其他规避 ABA 的思路：
  - 不复用对象（每次 new 新实例），让引用值不会回到旧值；
  - 用单调递增的版本号本身作为比较对象，不比较引用；
  - 直接用 `AtomicStampedReference`。
- 代价：多一次字段比较和更宽的读，热路径上比普通 CAS 慢。

**【追问】**
- 自己实现带版本戳的 CAS 该怎么做？
  - 必须把「引用 + 版本」打包进一个不可变对象再整体替换；用两个独立变量分别 CAS 会引入竞态。
- `AtomicMarkableReference` 适合什么场景？
  - 单个布尔状态标记（如节点是否已被删除）。

**【易错】**
- 只比较引用不比较版本，认为「引用变了才失败」——ABA 场景下引用没变但语义已变。
- 版本戳用 `int` 却不考虑回绕。

---

### LK15 · `LongAdder` 是怎么降低竞争的？`sum()` 精确吗？⭐⭐⭐⭐⭐

**【一句话答】** `LongAdder` 用「分段累加 + CAS」：低竞争时在 `base` 上直接 CAS 累加，竞争加剧时把累加分散到 `Cell[]` 数组的多个槽位，`sum()` 再把 base 和各 Cell 求和——**`sum()` 不是原子快照**。

**【展开】**
- 结构（继承 `java.util.concurrent.atomic.Striped64`）：
  - `base`：低竞争时的单值累加器，用 CAS 更新。
  - `cells`：`Cell[]` 数组，`Cell extends AtomicLong`，每个 Cell 是一个独立槽位。
  - `Cell` 上带 `@Contended` 注解，用于**填充伪共享**（false sharing），避免不同 Cell 落在同一 CPU Cache Line 上互相失效。
- 扩容：CAS 失败次数超过阈值 → 扩容 `cells`（按 2 的幂增长）；扩容时把 `base` 的值分摊到各 Cell，再让线程去争抢 Cell。
- 为什么比 `AtomicLong` 快：后者所有线程争同一个 cache line，高竞争下 CAS 失败率高；`LongAdder` 把争抢分散到多条 cache line。
- API：`add`、`increment`、`decrement`、`sum`、`sumThenReset`、`longValue`。
- **精度问题**：`sum()` 遍历 base 与各 Cell 相加，遍历期间别的线程可能在改某些 Cell，结果对应的是「不同时刻的各部分之和」，不是某一时刻的精确快照。
- 选型：写多读少的统计计数（QPS 埋点、指标）→ `LongAdder`；需要精确值（如作为唯一 ID 的组成部分）→ `AtomicLong`。对应实验：Lab2 CAS 与原子类。

**【追问】**
- 为什么 `LongAdder` 比 `AtomicLong` 更适合计数？
  - 计数场景只关心最终总量，不关心每次的精确读改写；分段累加把 CAS 竞争摊平到多个槽位。
- `sumThenReset` 也不是原子的吗？
  - 同样不是原子快照，只是减少了遍历次数；并发写入下仍可能偏。

**【易错】**
- 用 `LongAdder.sum()` 当作对账用的精确快照。
- 认为 `LongAdder` 是「每线程一个变量」——它是 Cell 槽位，多个线程可能争同一个 Cell。

---

### LK16 · `AtomicIntegerFieldUpdater` 有什么用？和 `AtomicInteger` 怎么选？⭐⭐⭐

**【一句话答】** 它给「已有的、`volatile` 修饰的 int 字段」提供原子的 CAS 更新能力，不需要把字段替换成 `AtomicInteger` 对象，从而省掉对象分配与 GC 压力。

**【展开】**
```java
public class Counter {
    private volatile int count;
    private static final AtomicIntegerFieldUpdater<Counter> U =
        AtomicIntegerFieldUpdater.newUpdater(Counter.class, "count");
    public void inc() { U.incrementAndGet(this); }
}
```
- 约束：
  - 目标字段必须是 `volatile` 的 `int`（`long` 对应 `AtomicLongFieldUpdater`；支持的具体字段类型需核实）；
  - 不能是 `final` 字段；
  - 目标类必须对字段有访问权限（Updater 内部用反射定位字段偏移）；
  - `newUpdater` 会创建新实例，应定义为 `static final` 单例。
- 优点：
  - 零额外对象（一个 `AtomicInteger` 字段意味着一个对象 + 一个 volatile int；Updater 本身不新增对象）；
  - 适合「对象数量巨大、每个对象一个计数器」的场景（连接、任务、会话），避免为每个对象分配原子包装。
- 代价：Updater 通过反射/Unsafe 定位字段，单次操作比 `AtomicInteger` 略慢（具体实现随 JDK 版本变化，JDK 9+ 底层细节需核实）。
- 语义与 `AtomicInteger` 一致，只是更新路径不同。

**【追问】**
- 什么场景必须用 FieldUpdater？
  - 海量小对象上的计数，且对象生命周期短、创建频繁，内存与 GC 压力比单次操作性能更重要。
- 能完全替代 `AtomicInteger` 吗？
  - 语义上可以，但 API 面更窄（可用方法随版本不同，需核实），性能也不占优；只在内存敏感场景才值得。

**【易错】**
- 字段没加 `volatile` 就用 Updater——会抛运行时异常，因为无法建立可见性保证。
- 每次调用都 `newUpdater`，浪费且失去单例语义。

---

### LK17 · 原子类的内存语义是什么？多个原子操作能保证一致性吗？⭐⭐⭐⭐

**【一句话答】** `java.util.concurrent.atomic` 包的类都遵循：写对后续读可见（volatile 语义），读-改-写整体原子；但**多个原子操作组合起来不原子**。

**【展开】**
- 语义分层：
  - 原子变量（`AtomicInteger`/`AtomicLong`）：读是 volatile 读、写是 volatile 写 → 可见性 + 有序性；`getAndIncrement` 等 RMW 额外保证原子性。
  - 原子引用（`AtomicReference`）：引用替换原子，泛型 `V`。
  - `AtomicIntegerArray` / `AtomicReferenceArray`：数组元素的原子更新。
  - `lazySet`：只保证写入的有序性（写最终可见），不保证立即可见；适合「一次性发布」这类场景。`setRelease` / `setOpaque` 的语义分层随 JDK 版本不同，需核实。
- 组合操作不原子：`A.decrementAndGet()` 和 `B.incrementAndGet()` 各自原子，但两条之间可被其他线程插入，不构成原子转账。要么用锁，要么把状态收敛到单个对象上一次性更新。

**【追问】**
- `lazySet` 和 `set` 的差别？
  - `set` 是 volatile 写（立即对其他线程可见）；`lazySet` 只要顺序性，允许可见性延后。
- 原子类能替代锁吗？
  - 只能替代「单变量更新」场景；涉及多个共享状态或复杂临界区时仍需锁。

**【易错】**
- 用多个原子变量模拟「原子转账」而不加锁——中间状态对外可见。
- 认为原子类只保证原子性，不管可见性——它同时提供 volatile 语义。

---

### LK18 · 乐观锁和悲观锁分别是什么？各适合什么场景？⭐⭐⭐⭐

**【一句话答】** 悲观锁假定冲突会发生，先加锁再操作（`synchronized`、`ReentrantLock`、数据库 `FOR UPDATE`）；乐观锁假定冲突少，先做后校验，失败重试（CAS、版本号、`LongAdder`）。

**【展开】**

| 维度 | 悲观锁 | 乐观锁 |
|---|---|---|
| 假设 | 冲突必然发生 | 冲突很少发生 |
| 实现 | `synchronized` / `ReentrantLock` / `SELECT ... FOR UPDATE` | CAS / 版本号 / `AtomicXxx` / `LongAdder` |
| 冲突处理 | 阻塞等待 | 重试或回滚 |
| 吞吐 | 较低（上下文切换） | 较高（无阻塞） |
| 典型场景 | 账户扣款、库存扣减、临界区长 | 计数器、状态标志、无锁队列 |

- 数据库对应：悲观锁 = `SELECT ... FOR UPDATE`；乐观锁 = `UPDATE t SET v=?, ver=ver+1 WHERE id=? AND ver=?`，靠影响行数为 0 判断冲突并重试。
- 悲观锁代价：线程阻塞与上下文切换；临界区越长代价越大。
- 乐观锁代价：冲突激烈时重试风暴（活锁风险）；ABA。

**【追问】**
- 乐观锁冲突激烈怎么办？
  - 加随机/递增退避；限制重试次数后转悲观锁；换更细的粒度（分片计数、`LongAdder`）。
- 乐观锁在数据库里的正确用法？
  - 版本号列 + 条件更新 + 检查影响行数，失败后重试或返回冲突。

**【易错】**
- 把乐观锁等同于「数据库乐观锁」，忽略 CAS 也是乐观锁。
- 认为乐观锁「性能一定更好」——高冲突下重试开销可能超过阻塞。

---

## 四、AQS 与锁的骨架

### LK19 · AQS 是什么？为什么说它是框架而不是一把锁？⭐⭐⭐⭐⭐

**【一句话答】** AQS（`AbstractQueuedSynchronizer`，JDK 5 引入）是构建锁与同步器的**框架**，本身不提供 `lock()` 接口；它给出独占/共享两种模式的排队、阻塞、唤醒骨架，「能不能拿到锁」由子类实现的 `tryAcquire`/`tryRelease` 决定。

**【展开】**
- 设计模式是**模板方法**：AQS 固定流程（`acquire` / `release` / `acquireShared` / `releaseShared`），把变化点留给子类（`tryAcquire`、`tryRelease`、`tryAcquireShared`、`tryReleaseShared`），以及可选的 `isHeldExclusively`（供 `Condition` 使用）。
- 典型子类与语义：

| 子类 | 模式 | state 含义 |
|---|---|---|
| `ReentrantLock` | 独占 | 重入计数 |
| `ReentrantReadWriteLock` | 读共享 / 写独占 | 高 16 位读计数、低 16 位写计数 |
| `Semaphore` | 共享 | 许可数 |
| `CountDownLatch` | 共享 | 剩余计数 |

- `CyclicBarrier` 不是 AQS 子类，它用 `ReentrantLock` + `Condition` 实现。
- AQS 与 `synchronized` 是两条完全不同的路线：`synchronized` 由 JVM/C++ 的 monitor 实现，AQS 是纯 Java 的用户态队列。对应实验：Lab3 AQS。

**【追问】**
- 为什么 `synchronized` 不做成 AQS？
  - JVM 内建锁能利用 monitor 的膨胀、等待集等机制；AQS 是纯 Java 实现，语义可控性更强但缺少 JVM 级优化。
- AQS 提供了哪些观测 API？
  - `hasQueuedThreads()`、`hasQueuedPredecessors()`、`getQueueLength()`、`getFirstQueuedThread()`、`hasWaiters(Condition)` 等。

**【易错】**
- 说「AQS 就是一把可重入锁」——它是框架，锁是它的子类。
- 认为 AQS 只支持独占——它同时支持共享模式。

---

### LK20 · AQS 的 `state` 字段有哪几种语义？⭐⭐⭐⭐

**【一句话答】** AQS 只有一个 `volatile int state`，语义由子类赋予：独占锁用作重入计数，共享锁用作许可数或倒计数，读写锁把高 16 位当读计数、低 16 位当写计数。

**【展开】**
- 编码（`ReentrantReadWriteLock$Sync` 的字段已用 `javap` 复核）：`SHARED_SHIFT = 16`、`SHARED_UNIT = 1 << 16`、`EXCLUSIVE_MASK = 0xffff`；读计数在高位、写计数在低位。
- 三种典型语义：

| 子类 | state 语义 | 取值含义 |
|---|---|---|
| `ReentrantLock` | 重入次数 | 0 = 未加锁；n = 当前线程加锁 n 次 |
| `Semaphore` | 剩余许可数 | `acquire` 减 1；`release` 加 1 |
| `CountDownLatch` | 剩余计数 | `countDown` 减 1；归零后放行所有等待者 |

- state 的三个职责：表示同步状态、被 CAS 原子修改、独占模式下配合 `exclusiveOwnerThread` 记录持有者。
- `compareAndSetState` 是 CAS；`setState` 是普通 volatile 写，只用在「已持有锁、状态确定」的路径上（如重入计数递增、释放时递减）。
- 溢出保护：`ReentrantLock` 在重入次数超过 `MAX_COUNT` 时抛 `Error("Maximum lock count exceeded")`（JDK 8 字节码已复核）；读写锁因按 16 位分段，计数上限为 65535，超限处理（部分版本抛 `Error`）需核实。

**【追问】**
- 为什么只用一个 int 而不是多个字段？
  - 单个 volatile int 可以用一条 CAS 原子更新，避免多字段更新之间的中间态；`ownerThread` 因为在独占模式下修改不频繁，普通 volatile 写即可。
- `setState` 和 `compareAndSetState` 能互换吗？
  - 不能。前者没有原子性保证，只在已持锁的场景使用。

**【易错】**
- 以为 AQS 有多个状态字段——不同 JDK 版本的命名不同（`status`/`state`），语义一致。
- 在 `setState` 路径上误用非独占访问。

---

### LK21 · AQS 的独占模式和共享模式有什么区别？⭐⭐⭐⭐

**【一句话答】** 独占模式同一时刻只有一个线程能拿到（如写锁、`ReentrantLock`）；共享模式允许多个线程同时拿到（如读锁、`Semaphore`、`CountDownLatch`），但获取数量受 state 计数约束。

**【展开】**
- 独占模式：模板方法 `acquire(int)` / `release(int)`；子类 `tryAcquire` 返回 `true` 表示获取成功。
- 共享模式：模板方法 `acquireShared(int)` / `releaseShared(int)`；`tryAcquireShared` 的返回值有三层语义：
  - `> 0`：获取成功，且后续共享获取不必等待（还有剩余份额）；
  - `= 0`：获取成功，但后续共享获取可能需要阻塞；
  - `< 0`：获取失败，进入队列等待。
- 队列标记：共享模式在队列中用 `Node.SHARED` 哨兵节点标记一段连续的共享节点；`setHeadAndPropagate` 负责把共享语义向后传播，让连续的共享节点批量通过。
- 例子对比：`Semaphore(3)` 三个线程可同时拿到许可，第四个等待；`CountDownLatch(1)` 计数归零前所有线程等待；`ReentrantReadWriteLock` 读锁允许多线程同时持有。

**【追问】**
- 共享模式下如何避免「前面的线程还没拿到，后面的先拿到」？
  - AQS 在共享模式下会检查前驱是否为独占节点：若是必须等它先完成；共享节点被唤醒后通过传播机制连续放行后续共享节点。

**【易错】**
- 认为 `tryAcquireShared` 返回 `true` 就够了——返回值的符号还携带「是否需要继续传播」的信息。

---

### LK22 · AQS 的等待队列为什么叫「CLH 变体」？它和原始 CLH 差在哪？⭐⭐

**【一句话答】** 经典 CLH 是单向链表 + tail 指针；AQS 改成**双向**队列（节点同时持有 `prev` 和 `next`），并引入 head 哑元节点与状态位字段，所以叫 CLH 变体。JDK 17 的 AQS 源码注释里也明确自称 "a variant of a CLH lock queue"。

**【展开】**
- JDK 17 `AbstractQueuedSynchronizer$Node` 的字段（**JDK 17 javap 已复核**）：`volatile Node prev`、`volatile Node next`、`Thread waiter`（被唤醒的线程）、`volatile int status`（状态位）；配套 `casPrev`/`casNext`/`getAndUnsetStatus`/`setPrevRelaxed`/`setStatusRelaxed`/`clearStatus` 等原子操作方法。
- 为什么改成双向：
  - 节点取消（超时/中断）时需要知道前驱是谁，并把前驱指向自己的后继 → 需要 `prev`；
  - 入队时要在尾部接上新节点 → 需要 `next`。
- head 是哑元节点（dummy），代表「上一个已完成的持有者」；tail 指向最后一个节点。队列空时 head == tail。
- 批量唤醒：同模式连续节点被一次唤醒（`setHeadAndPropagate`），减少唤醒次数。
- **JDK 8 与 JDK 17 的差异**：
  - 字段名：JDK 8 是 `thread` + `volatile int waitStatus`；JDK 17 改为 `waiter`（普通内存模式）+ `volatile int status`（**`waitStatus` 这个名字在 JDK 17 已不存在**）。
  - 哨兵节点：JDK 8 的 `Node.SHARED` / `Node.EXCLUSIVE` 两个哨兵在 JDK 17 已删除。
  - 条件队列：JDK 8 把 `nextWaiter` 放在 `Node` 上；JDK 17 拆出独立的 `AbstractQueuedSynchronizer$ConditionNode extends Node`，`nextWaiter` 移到该子类上。

**【追问】**
- head 和 tail 是同一个节点吗？
  - 不一定：队列非空时 head 是哑元、tail 是最后一个等待节点。
- 条件队列是这条队列吗？
  - 不是。条件队列是每个 `Condition` 各自的一条独立单向链（JDK 17 里由 `ConditionObject` 的 `firstWaiter`/`lastWaiter` 串起 `ConditionNode`）。

**【易错】**
- 说 AQS 队列是「单向链表」——JDK 5 之后的 AQS 是双向。
- 把条件队列混进同步队列统计。
- 在 JDK 17 上照搬 JDK 8 的字段名——`waitStatus`、`thread`、`Node.SHARED` 都已经不存在了。

---

### LK23 · `acquire` / `release` 的完整流程是什么？模板方法体现在哪？⭐⭐⭐⭐⭐

**【一句话答】** `acquire` 先试 `tryAcquire`，失败则包装成节点入队，自旋若干次后再 `park` 阻塞，被唤醒后重新竞争；`release` 先 `tryRelease`，彻底释放后 `unparkSuccessor` 唤醒后继。流程由 AQS 固定，变化点交给子类。

**【展开】**
- `acquire(int)`（JDK 17 `AbstractQueuedSynchronizer#acquire`，已用 `javap -p` 确认该方法存在）：
  1. `tryAcquire(arg)` 成功 → 直接返回；
  2. 失败 → `addWaiter` 包装成 `Node` 入队，然后 `acquireQueued(node, arg)`：
     3. 先在队头自旋若干次尝试 `tryAcquire`；
     4. 仍失败 → `LockSupport.park(this)` 阻塞；
     5. 被 `unpark` 唤醒 → 若前驱是 head 且已完成 → 再次 `tryAcquire`；
     6. 成功后 `setHead`，并 `unparkSuccessor` 唤醒后继。
- `release(int)`：
  1. `tryRelease(arg)`（重入计数减 1，归零时清 owner）；
  2. 若已彻底释放 → `unparkSuccessor(head)`。
- `acquireQueued` 返回后，若调用的是不可中断的 `lock()`，AQS 会 `selfInterrupt()` 恢复被 park 期间吞掉的中断标志。
- 底层阻塞原语是 `LockSupport.park/unpark`（许可式，可精确唤醒指定线程），而不是 `Object.wait/notify`（`notify` 只能唤醒等待集中的任意一个，顺序不保证）。
- 共享模式的 `acquireShared`/`releaseShared` 流程结构相同，差别在传播唤醒。
- **JDK 8 与 JDK 17 的差异**：JDK 17 的 AQS 额外提供了 `acquire(Node, int, boolean, boolean, boolean, long)` 重载（`javap -p` 可见），把可中断/共享/超时等组合收敛到同一套队列操作；`acquire(int)`/`release(int)`/`acquireShared`/`releaseShared` 的主干流程与 JDK 8 一致。

**【追问】**
- `lock()` 被中断会抛异常吗？
  - 不会。`lock()` 不可中断：等待期间的中断不提前放行，但中断状态被记住，拿到锁后由 `selfInterrupt` 重新设置中断标志。要可中断用 `lockInterruptibly()`。
- 为什么要 `selfInterrupt`？
  - `park` 期间的中断会直接返回，若不恢复标志，调用方的中断语义就丢了。

**【易错】**
- 认为 `lock()` 会响应中断抛 `InterruptedException`——它不抛，但保留中断标志。

---

### LK24 · 节点的 `waitStatus` 有哪些取值？流转是怎样的？⭐⭐⭐⭐

**【一句话答】** 节点的状态字段决定「谁能被唤醒、是否需要传播」。**JDK 8** 叫 `waitStatus`，取值 `SIGNAL = -1`、`CONDITION = -2`、`PROPAGATE = -3`、`CANCELLED = 1`、`0`；**JDK 17** 改名为 `status`，常量整体换了一套：`WAITING = 1`、`COND = 2`、`CANCELLED = 0x80000000`，语义从「枚举状态值」变成「状态位」。

**【展开】**
- **JDK 8**（`AbstractQueuedSynchronizer$Node`，已用 `javap -constants` 复核）：

| 常量 | 值 | 含义 |
|---|---|---|
| `CANCELLED` | 1 | 节点超时或被中断而取消 |
| `SIGNAL` | -1 | 当前节点的后继需要被唤醒 |
| `CONDITION` | -2 | 节点在条件队列上等待（`await` 中） |
| `PROPAGATE` | -3 | 共享模式传播标记 |
| （初始值） | 0 | 无特殊含义 |

  - 典型流转：**入队**新节点 `waitStatus = 0`，前驱 CAS 自己的 `waitStatus` 为 `SIGNAL`（「我睡下后要叫醒后继」）；**被唤醒**后归 0 再次竞争，成功则成为新 head；**取消**置 `CANCELLED` 并由 `unlinkCancelled` 摘除，且必须向前驱传播唤醒；**条件队列**上 `waitStatus = CONDITION`，`signal` 时经 `transferAfterCancelledWait` 移回同步队列。

- **JDK 17**（`AbstractQueuedSynchronizer` 顶层常量 + `Node.status`，已用 `javap -constants` 与 src.zip 源码复核）：

| 常量 | 值 | 含义 |
|---|---|---|
| `WAITING` | 1 | 必须为 1；节点在等待 |
| `COND` | 2 | 节点在条件队列上等待 |
| `CANCELLED` | 0x80000000 | `Integer.MIN_VALUE`，必须为负数；节点已取消 |

  - 语义从「枚举」变成「状态位」：`status < 0` 表示该节点需要被 `LockSupport.unpark` 唤醒（或已取消）；`status == 0` 在 `cleanQueue` 中表示待清理的链接状态。
  - 原子操作换成位运算：`getAndUnsetStatus(int)`、`setStatusRelaxed(int)`、`clearStatus()`，配套 `casPrev`/`casNext`（用 `jdk.internal.misc.Unsafe` 的弱 CAS）。
  - JDK 17 **没有** `SIGNAL` / `CONDITION` / `PROPAGATE` 这三个常量，别照搬 JDK 8 的记忆。

**【追问】**
- 取消节点为什么要向前驱传播唤醒？
  - 若被取消的节点恰好负责唤醒后继（JDK 8 的 `SIGNAL`、JDK 17 的 `status < 0`），它不唤醒就会让后继永久阻塞。
- JDK 17 为什么把枚举换成状态位？
  - 位运算可以原子地「置位/清位/测试」，合并了 JDK 8 需要靠多个枚举值表达的多重语义，也省掉了对 `PROPAGATE` 这类传播标记的单独处理。

**【易错】**
- 记不住数值就乱写——上表两组数值分别来自 JDK 8 与 JDK 17 的 `javap -constants` 复核。
- 在 JDK 17 上说「`Node.waitStatus`」——该字段已改名为 `status`，`waitStatus` 这个名字不存在了。
- 把 `waitStatus` / `status` 当普通字段——都是 `volatile int`。

---

### LK25 · Condition 的条件队列和同步队列有什么区别？`await`/`signal` 做了什么？⭐⭐⭐⭐⭐

**【一句话答】** 每个 `Condition` 有一条独立的**单向条件队列**（只放 `await` 的线程），而锁的竞争者都在 AQS 的 **CLH 双向同步队列**里；`await` 把线程从同步队列移到条件队列尾部，`signal` 再把它移回同步队列重新参与竞争。

**【展开】**

| 维度 | 同步队列 | 条件队列 |
|---|---|---|
| 结构 | CLH 变体，双向（`prev`/`next`） | 单向链（`nextWaiter`） |
| 数量 | 每个 AQS 一条 | 每个 `Condition` 一条 |
| 节点类型 | JDK 17 是 `Node`；JDK 8 另有 `SHARED`/`EXCLUSIVE` 哨兵 | JDK 17 是 `ConditionNode`；JDK 8 直接用 `Node` |
| 节点状态 | JDK 8：`SIGNAL`/`PROPAGATE`/`CANCELLED`；JDK 17：`status` 位（`WAITING`/`CANCELLED`） | JDK 8：`CONDITION`；JDK 17：`COND` |
| 作用 | 竞争锁 | 等待条件成立 |
| 移动方向 | — | 同步队列 →（await）条件队列 →（signal）同步队列 |

- `await()` 流程：
  1. 检查是否持有锁，未持有抛 `IllegalMonitorStateException`；
  2. 加入条件队列尾部，状态置为「条件等待」（JDK 8 是 `waitStatus = CONDITION`，JDK 17 是 `status = COND`）；
  3. **完全释放锁**（`fullyRelease`，含重入次数）；
  4. `LockSupport.park` 阻塞等待；
  5. 被 `signal` 唤醒后**重新竞争锁**，拿到锁才返回。
- `signal()` 流程：从条件队列头部取一个节点，状态复位，移回同步队列并 `unpark`——**只是唤醒，不保证它立刻拿到锁**。
- `await` 必须写在 `while (条件不成立)` 循环里：要处理虚假唤醒，也要处理「条件被别的线程抢先满足」。
- `synchronized` 只有一个 waitSet，无法按条件精确唤醒；`Condition` 提供了多个条件队列，可以「按条件 signal」。
- **JDK 8 与 JDK 17 的差异**：JDK 8 的条件队列节点就是 `Node`（自带 `nextWaiter` 字段）；JDK 17 拆出独立的 `AbstractQueuedSynchronizer$ConditionNode extends Node`（自带 `nextWaiter`），由 `ConditionObject` 的 `firstWaiter`/`lastWaiter` 两个字段串成链，条件队列与同步队列的节点类型从此分开。

**【追问】**
- `await` 和 `Object.wait/notify` 的区别？
  - `wait` 释放 monitor 且只能在 `synchronized` 块内使用；`await` 释放的是 AQS 的锁（完全释放），且可以用多个 `Condition` 做精确唤醒。
- `await` 返回后为什么还要重新抢锁？
  - 保证临界区互斥语义：`await` 返回后代码假定自己仍独占这把锁。

**【易错】**
- 用 `if` 而不是 `while` 判断条件，丢掉虚假唤醒处理。
- 以为 `signal` 会把锁「直接交给」被唤醒的线程。

---

## 五、可重入、公平性、读写锁

### LK26 · `ReentrantLock` 的可重入具体怎么实现？⭐⭐⭐⭐

**【一句话答】** 用 AQS 的 `state` 当重入计数：获取时若 `state == 0` 则 CAS 到 1 并记录 owner，若 owner 是自己则 `state + 1`；释放时 `state - 1`，减到 0 才真正释放并清空 owner。

**【展开】**
- 获取（**JDK 17 javap 字节码已复核**）：
  - JDK 17 把「首次获取 + 重入」合并在 `NonfairSync#initialTryLock()`：CAS `0 → 1` 成功后 `setExclusiveOwnerThread(current)`；owner 是当前线程则 `state + 1`，计数溢出（`c < 0`）抛 `Error("Maximum lock count exceeded")`。
  - `NonfairSync#tryAcquire(int)` 只走「非重入」路径：`getState() == 0 && compareAndSetState(0, acquires)`，供 AQS `acquire()` 在队列中重试时调用。
- 释放（`ReentrantLock$Sync#tryRelease`，**JDK 17 javap 字节码已复核**）：
  - owner 不是自己 → 抛 `IllegalMonitorStateException`；
  - `state - 1`；结果为 0 时清空 owner 并置 `signal` 状态，随后 `unparkSuccessor`。
- 公平模式（`FairSync#tryAcquire`，**JDK 17 javap 字节码已复核**）额外先调 `hasQueuedPredecessors()`，保证按队列顺序获取。
- **JDK 8 与 JDK 17 的差异**：JDK 8 是 `ReentrantLock$Sync#nonfairTryAcquire` 一个方法同时干「首次获取」和「重入」两件事；JDK 17 拆成 `Sync#initialTryLock()`（入口预筛，含重入）与 `FairSync/NonfairSync#tryAcquire()`（AQS 队列里的重试）两步，语义等价、结构不同，面试时按 JDK 17 说。
- `synchronized` 侧的重入计数保存在 HotSpot 的对象头/monitor 中，具体字段归属随版本变化，需核实；工程上的行为语义与 `ReentrantLock` 一致。

**【追问】**
- `state` 同时承担「是否被占用」和「重入了几次」两个职责吗？
  - 是的，靠取值区分：0 表示未加锁，正数表示持有且重入了多少次。
- 重入次数会溢出吗？
  - 会，`MAX_COUNT` 检查会抛 `Error`；实际业务不可能触发，属于防御性保护。

**【易错】**
- 释放时忘记 owner 校验会怎样——`tryRelease` 已经拦住了，这是安全底线。
- 认为重入计数是 Java 层的普通字段——它在 AQS 的 `state` 里。

---

### LK27 · 公平锁和非公平锁有什么区别？什么时候用公平锁？⭐⭐⭐⭐⭐

**【一句话答】** 非公平锁允许新来的线程直接 CAS 抢锁（插队），吞吐高但可能饥饿；公平锁严格按等待队列 FIFO 获取，代价是每次获取都要检查队列、唤醒次数多。默认用非公平，只有出现长期饥饿才考虑公平。

**【展开】**

| 维度 | 非公平（默认） | 公平 |
|---|---|---|
| 获取顺序 | 无保证，可能插队 | 按入队顺序 FIFO |
| 吞吐 | 高 | 低 |
| 上下文切换 | 少 | 多 |
| 饥饿风险 | 有 | 无（插队型） |
| 实现 | `NonfairSync#tryAcquire`（JDK 17） | `FairSync#tryAcquire`（含 `hasQueuedPredecessors()`） |
| 构造 | `new ReentrantLock()` | `new ReentrantLock(true)` |

- 非公平为什么更快：锁释放后，下一个线程被 `unpark` 但还要重新 CAS 竞争；非公平允许这个「已经在 CPU 上、cache 热」的线程直接 CAS 成功，省掉一次唤醒 + 上下文切换。
- 公平锁的代价：每次释放都要 `unparkSuccessor`，被唤醒线程从 park 恢复要走上下文切换。
- 选型建议：默认非公平。只有当观察到某线程长期拿不到锁（饥饿）导致业务问题时才改公平。

**【追问】**
- 公平锁能保证「先到先得」吗？
  - 只能保证「按入队顺序」。入队本身是 CAS，线程「到达」的先后与入队先后之间仍有竞态。
- 公平锁的吞吐差距一定很大吗？
  - 低竞争下差距不大；高竞争下差距明显。

**【易错】**
- 认为公平锁「绝对不会有饥饿」——插队型饥饿被消除了，长临界区导致的等待公平锁也解决不了。
- 认为非公平锁「一定会饿死某个线程」——是概率性风险。

---

### LK28 · `hasQueuedPredecessors()` 是什么？公平与非公平的区别就靠它吗？⭐⭐⭐⭐

**【一句话答】** 它判断「当前线程前面是否还有别的线程在等锁」：有则返回 true。公平锁在获取锁前必须检查它；非公平锁**不检查**，这正是插队的来源。

**【展开】**
- 判定逻辑（`AbstractQueuedSynchronizer#hasQueuedPredecessors`，**JDK 17 javap 已复核方法存在**；实现为取 `head.next`，若存在、不等于当前线程、且其等待线程非 null，则返回 true）：
  - 队列为空 → false；
  - 队首等待者就是当前线程 → false；
  - 否则 → true。
- 公平锁（`FairSync#tryAcquire`，**JDK 17 javap 字节码已复核**）：`state == 0` 时必须 `!hasQueuedPredecessors()` 且 CAS 成功才获取。
- 非公平锁（`NonfairSync#tryAcquire`，**JDK 17 javap 字节码已复核**）：**不调用** `hasQueuedPredecessors()`，直接 CAS 抢锁。
- 为什么排除「当前线程自己」：已排队等待的线程再次尝试获取时，若自己就是队首应当被允许，否则会自阻塞。
- 观测队列状态的 API：`hasQueuedThread(thread)`、`getQueueLength()`、`getFirstQueuedThread()`、`getQueuedThreads()`。

**【追问】**
- 已经在队列里排第一的线程，非公平锁也会直接拿到吗？
  - 是。`hasQueuedPredecessors` 排除自身后返回 false，逻辑上与公平一致。
- `hasQueuedPredecessors` 能用来判断「锁是否被占用」吗？
  - 不能，它只回答「队列中是否有更早的等待者」，与 `isLocked()` 语义不同。

**【易错】**
- 认为非公平锁也调用 `hasQueuedPredecessors`——JDK 8 的 `nonfairTryAcquire` 并不调用（字节码可验证）。
- 忽略 `head.next.thread == null` 的哑元/已取消节点情况。

---

### LK29 · `ReentrantReadWriteLock` 的读写语义是什么？状态怎么编码？⭐⭐⭐⭐⭐

**【一句话答】** 读锁共享（多线程可同时持有）、写锁独占（与读写锁都互斥）；状态用一个 int 编码：高 16 位是读锁计数，低 16 位是写锁重入计数。同一线程可同时持有写锁和读锁（为支持降级）。

**【展开】**

| 组合 | 是否兼容 |
|---|---|
| 读 + 读 | 兼容 |
| 读 + 写 | 互斥 |
| 写 + 写 | 互斥 |
| 同一线程 写 + 读 | 允许（支持降级的前提） |

- 状态编码（`ReentrantReadWriteLock$Sync` 字段已用 `javap` 复核）：`SHARED_SHIFT = 16`、`SHARED_UNIT = 1 << 16`、`EXCLUSIVE_MASK = 0xffff`；高位是读计数、低位是写计数。
- 读锁实现要点：`tryAcquireShared` 先检查写锁是否被占用（`exclusiveCount`），再增加读计数；第一个获取读锁的线程用 `firstReader`/`firstReaderHoldCount` 优化，后续线程用 `ThreadLocalHoldCounter`（`ThreadLocal`）记录各自的读锁持有次数。
- 可重入性：读锁与写锁都可重入。
- 公平性：`new ReentrantReadWriteLock()` 默认**非公平**；`new ReentrantReadWriteLock(true)` 为公平。
- 溢出：读写锁按 16 位分段，计数上限 65535；超限时的处理（部分版本抛 `Error`）需核实。
- 适用场景：读多写少、读操作有实质耗时（缓存、配置热更新）。写占比高时读写锁未必优于普通互斥锁。对应实验：Lab5 读写锁。

**【追问】**
- 读锁为什么用 `ThreadLocal` 记录持有次数？
  - 需要知道「当前线程持有几把读锁」才能支持可重入与正确的释放；用 `ThreadLocal` 避免全局数组扫描。
- 读锁可以被升级为写锁吗？
  - 不能，见 LK31。

**【易错】**
- 认为读写锁性能一定优于 `synchronized`——写密集场景未必。
- 忘了默认是非公平锁。

---

### LK30 · 什么是锁降级？为什么读写锁支持降级？⭐⭐⭐⭐

**【一句话答】** 锁降级是「持有写锁时获取读锁，再释放写锁」。`ReentrantReadWriteLock` 支持降级，因为降级后本线程仍持有读锁，读锁挡住其他写者，保证读到的数据是「释放写锁那一刻」的最终值。

**【展开】**
```java
lock.writeLock().lock();     // 持有写锁
lock.readLock().lock();      // 获取读锁（此时仍持有写锁）
lock.writeLock().unlock();   // 释放写锁 —— 降级完成，仍持有读锁
// 读到的数据一定是写完后的最终值
lock.readLock().unlock();
```
- 为什么安全：降级后本线程持有读锁，其他写者无法获得写锁，也就无法修改数据；因此本线程在读锁保护下读到的值必然是自己写完的值。
- 为什么要降级：避免「释放写锁 → 再获取读锁」之间被别的写者抢占的窗口。
- 经典用途：写入后需要把结果返回给调用方，降级能保证返回的快照与写入一致。

**【追问】**
- 降级之后还能再升回去吗？
  - 不能（`ReentrantReadWriteLock` 不支持升级，见 LK31）。
- 降级和可重入是什么关系？
  - 降级依赖「同一线程可同时持有写锁和读锁」这一实现事实；写锁与读锁的互斥性对同线程例外。

**【易错】**
- 把「降级」误解成「释放写锁后重新加读锁」——那样中间有窗口，不是降级。
- 认为读写锁支持锁升级。

---

### LK31 · 为什么读写锁不支持锁升级（读锁 → 写锁）？⭐⭐⭐⭐

**【一句话答】** 升级必须先释放读锁，而「释放读锁 → 获得写锁」之间存在窗口期，其他写者可能插入并修改数据；等本线程拿到写锁时，基于旧读出值的判断已经失效。

**【展开】**
- 假想升级流程的问题点：
  1. 持有读锁，读到数据 V；
  2. 想升级 → 必须先释放读锁（写锁与读锁互斥，不释放就永远拿不到）；
  3. 释放读锁后，本线程处于「不持有任何锁」的状态；
  4. 期间别的写者可能把数据改成 V'；等本线程拿到写锁，看到的是 V'，基于 V 的业务判断失效。
- 实现层面的原因：读锁与写锁对同一对象互斥，读锁未释放时写锁一定拿不到。
- 替代方案：
  - `StampedLock#tryConvertToWriteLock`：在**还没释放读锁**的前提下尝试原地把读锁转成写锁；成功则没有窗口，失败则只能走「释放读锁 → 取写锁 → 重取读锁 → 重校验 stamp」，期间存在数据被改的风险（类似 ABA）。
  - 业务改法：读阶段记录版本号；写阶段用「写锁 + 重新校验版本 + 不一致则重试」的乐观流程。
- 结论：升级不是 API 缺失，而是**正确性上做不到无窗口**。

**【追问】**
- `tryConvertToWriteLock` 一定成功吗？
  - 不一定。存在写竞争时可能失败，必须按文档处理（释放读锁、获取写锁、重新获取读锁并校验 stamp）。

**【易错】**
- 认为「升级不了只是没提供 API」——存在正确性窗口，不是简单缺失。

---

### LK32 · 什么是锁饥饿？怎么发现和缓解？⭐⭐⭐

**【一句话答】** 饥饿指某个线程长期拿不到锁，而系统其他部分仍在推进（区别于死锁）。插队型饥饿用公平锁缓解，长临界区型饥饿靠缩短临界区、减小锁粒度。

**【展开】**
- 饥饿 vs 死锁：

| | 死锁 | 饥饿 |
|---|---|---|
| 现象 | 所有涉及线程互相等待，整体停滞 | 系统仍在推进，个别线程长期拿不到资源 |
| 典型原因 | 循环等待 | 插队、临界区过长、调度优先级 |
| 表现 | `jstack` 明确报 deadlock | 无明确标记，靠长期观测 |

- 成因与对策：

| 成因 | 对策 |
|---|---|
| 非公平锁插队 | 改用公平锁 |
| 临界区过长（含 IO） | 缩短临界区，把耗时操作移出锁 |
| 锁粒度过粗 | 分段锁、按 key 加锁、`LongAdder` 分槽 |
| 线程长期排队 | `tryLock(timeout)` + 退避重试、`Semaphore` 限流 |

- 观测手段：
  - `jstack -l` / `jcmd <pid> Thread.print` 观察大量线程 `BLOCKED`/`WAITING` 在同一把锁上；
  - JDK 16+ JFR 的 `jdk.JavaMonitorEnter`、`jdk.ThreadPark` 事件；
  - async-profiler 的锁模式（`-e lock`）。
- 对应实验：Lab4 死锁与饥饿现象对比。

**【追问】**
- 怎么区分线上是死锁还是饥饿？
  - 死锁时 `jstack` 会输出 "Found one Java-level deadlock" 并列出参与线程；饥饿没有任何 JVM 级标记，只能靠线程状态长期观测和锁等待时长的统计。

**【易错】**
- 用公平锁去解决「长临界区」导致的饥饿——公平锁只解决插队。
- 把大量线程 `WAITING` 直接判定为死锁。

---

## 六、StampedLock 与不可变性优化

### LK33 · `StampedLock` 的乐观读原理是什么？和悲观读有什么区别？⭐⭐⭐⭐

**【一句话答】** `StampedLock`（JDK 8 引入）有写锁、悲观读锁、乐观读三种模式；乐观读**不加任何锁**，只取一个版本戳（stamp）并直接读数据，读完用 `validate(stamp)` 校验期间是否发生过写。

**【展开】**

| 模式 | API | 是否加锁 | 读到什么 |
|---|---|---|---|
| 写锁 | `writeLock()` | 是（独占） | 最新 |
| 悲观读 | `readLock()` | 是（共享） | 最新 |
| 乐观读 | `tryOptimisticRead()` | 否 | 可能是旧快照 |

- 乐观读的标准写法：
```java
long stamp = sl.tryOptimisticRead();
int a = x, b = y;              // 读数据，不阻塞写者
if (!sl.validate(stamp)) {     // 校验：期间有没有写
    stamp = sl.readLock();     // 失败 → 退化为悲观读
    try { a = x; b = y; } finally { sl.unlockRead(stamp); }
}
```
- 实现原理：`StampedLock` 用一个 `volatile long state` 编码写锁计数与读锁计数（`readerOverflow` 记录超出计数位的读者数，字段已用 `javap` 复核）；每次写操作都会改变 stamp，乐观读拿到的 stamp 与当前状态不符即说明期间发生过写。
- 优点：读路径完全无锁、无原子操作、无内存屏障开销，读多写少时吞吐显著高于 `ReentrantReadWriteLock`。
- 代价：乐观读**不保证最新值**，必须校验；校验失败要退化重读。对应实验：Lab6 StampedLock。

**【追问】**
- 乐观读读到的多个字段可能互相不一致吗？
  - 可能。写发生在读取期间，读到的字段来自不同时刻——这正是 `validate` 存在的理由。
- `StampedLock` 能配 `Condition` 用吗？
  - 不能。`StampedLock` 不实现 `Lock` 接口，没有条件队列支持。

**【易错】**
- 把 `tryOptimisticRead` 当成「无锁地读到最新值」。
- 忘了 `validate` 失败后必须用悲观读重新取数。

---

### LK34 · `validate(stamp)` 是怎么判断的？校验失败怎么办？⭐⭐⭐

**【一句话答】** `validate` 比较「拿到 stamp 时」与「现在」的锁状态编码是否一致；不一致说明期间发生过写或锁状态变化，读到的数据可能已过期，必须退化为悲观读。

**【展开】**
- 判定依据：stamp 里编码了写锁计数与读锁计数；任何写操作（获取/释放写锁、锁状态变化）都会改变 `state`，从而让旧 stamp 失效。
- 失败处理范式（务必用悲观读重取，并保证解锁用同一个 stamp）：
```java
long stamp = sl.tryOptimisticRead();
int a = data.a, b = data.b;
if (!sl.validate(stamp)) {
    stamp = sl.readLock();
    try { a = data.a; b = data.b; } finally { sl.unlockRead(stamp); }
}
```
- `validate` 失败是**正常路径**而非异常：设计目标就是「先无锁读，失败再退化为加锁读」，用少量退化换取多数情况下的高吞吐。
- 校验通过 ≠ 业务数据一定一致：只有当**所有写入都经过这把锁**时，「期间无写」才能推出「读到的是最新且自洽的」。
- `validate` 应紧跟读取之后，中间不要插入其他逻辑。
- 校验失败率高说明写竞争激烈，此时乐观读收益已被退化开销吃掉，应考虑改用悲观读锁或换数据结构。

**【追问】**
- `validate` 通过后还需要检查业务不变量吗？
  - 需要。两个字段之间的约束关系可能因为别的线程通过非本锁路径写入而被破坏。
- 可以对多个变量用同一个 stamp 一起校验吗？
  - 可以，这正是推荐用法：一次 `tryOptimisticRead` 取 stamp，读完多个变量后一次 `validate`。

**【易错】**
- 只 `tryOptimisticRead` 不 `validate`，直接使用可能过期的数据。
- `validate` 通过就断言「读到的一定最新」——前提是所有写都受这把锁保护。

---

### LK35 · `StampedLock` 不可重入和锁降级分别是什么意思？⭐⭐⭐

**【一句话答】** 不可重入指同一线程二次获取同一把锁会自死锁（`StampedLock` 没有 owner 字段，无法识别重入）；但它支持降级（写锁 → 读锁），并提供 `tryConvertToWriteLock` 在不释放读锁的前提下尝试原地升级。

**【展开】**
- 不可重入：
  - `writeLock().lock()` 在已持有写锁时再调用会阻塞自己；`readLock().lock()` 同理不可重入。
  - 原因：`StampedLock` 用「版本戳 + 计数」管理状态，不记录「哪个线程持有」，因此无法识别重入。
  - 后果：递归调用中重复加锁 → 自死锁；且线程栈里不像 `synchronized` 那样能直接看出是哪把锁，排查更困难。
- 降级：
```java
long ws = sl.writeLock();
try {
    // 写数据
    long rs = sl.readLock();   // 写锁 → 读锁：降级
    sl.unlockWrite(ws);        // 释放写锁，仍持有读锁
    // 读到的数据一定是写完后的最终值
    sl.unlockRead(rs);
}
```
- 升级（`tryConvertToWriteLock`）：
  - 持有读锁时调用，若无其他写者竞争，可原地把读锁转成写锁，**中间没有窗口**；
  - 失败时只能「释放读锁 → 获取写锁 → 重新获取读锁 → 重新校验 stamp」，这段时间数据可能被改写（类似 ABA）。
- 相关 API（已用 `javap` 复核 JDK 8 存在）：`tryOptimisticRead`、`validate`、`unlockRead`、`unlockWrite`、`unlock`、`tryConvertToWriteLock`、`tryConvertToReadLock`、`tryConvertToOptimisticRead`。

**【追问】**
- 不可重入带来什么实际风险？
  - 递归路径上的自死锁，且缺乏 owner 信息，排查困难。
- 什么场景必须用 `ReentrantReadWriteLock` 而不是 `StampedLock`？
  - 需要可重入、需要 `Condition`、需要与 `Lock` 接口集成（如 `ReentrantLock` 的 `newCondition()`）。

**【易错】**
- 把 `StampedLock` 当成 `ReentrantReadWriteLock` 的直接替代品——不可重入是硬约束。
- 解锁时传错 stamp，会抛 `IllegalMonitorStateException`。

---

### LK36 · `StampedLock` 的适用场景和风险是什么？怎么和读写锁选？⭐⭐

**【一句话答】** 适合「读远多于写、读操作极短、数据以整体替换为主」的场景；风险是乐观读可能读到不一致快照、不可重入、升级路径存在 ABA、以及 stamp 传递不当会抛异常。

**【展开】**
- 适合：读极多写极少的配置/状态数据；写操作以「整体替换对象」为主（此时乐观读几乎不会失败）；对延迟敏感的路径。
- 不适合：写频繁（乐观读不断失败，退化开销反而更大）；需要可重入；需要条件队列；需要 `Lock` 接口。
- 风险清单：
  - 乐观读必须显式 `validate`，漏掉就是用脏数据；
  - 不可重入 → 自死锁；
  - `tryConvertToWriteLock` 失败路径存在 ABA 风险；
  - stamp 是 long，解锁必须传当初那个值，传错抛 `IllegalMonitorStateException`。

| 维度 | `ReentrantReadWriteLock` | `StampedLock` |
|---|---|---|
| 乐观读 | 无 | 有 |
| 可重入 | 是 | 否 |
| `Condition` | 支持 | 不支持 |
| 读吞吐 | 中（有锁开销） | 高（乐观读无锁） |
| 写多场景 | 优于 `StampedLock` | 明显退化 |
| 适用 | 通用读写 | 读极多写极少 |

- 替代方案：`CopyOnWriteArrayList` 适合「读远多于写、集合不大」；`StampedLock` 适合「原地更新、读多写少、元素多」。

**【追问】**
- 和 `CopyOnWriteArrayList` 怎么选？
  - 后者是写时整体复制，内存随写次数增长；`StampedLock` 是原地更新 + 无锁读，适合元素多、更新不频繁的场景。
- 数据一致性要求高怎么办？
  - 不要用乐观读；用悲观读锁或干脆用不可变对象 + 整体替换，从设计上消除并发写。

**【易错】**
- 在写多的场景强行上 `StampedLock`，结果比 `synchronized` 还慢。
- 以为 `StampedLock` 可以配合 `Condition` 使用。

---

## 七、死锁与工程实践

### LK37 · 死锁的四个必要条件是什么？Java 中常见的死锁来源有哪些？⭐⭐⭐⭐⭐

**【一句话答】** 死锁需要同时满足互斥、持有并等待、不可剥夺、循环等待四个条件；打破任意一个即可预防。Java 中最常见的是「嵌套加锁顺序不一致」。

**【展开】**

| 条件 | 含义 | 打破办法 |
|---|---|---|
| 互斥 | 资源同一时刻只能被一个线程占有 | 用消息传递、原子变量等无共享结构替代 |
| 持有并等待 | 持有资源 A 的同时等待资源 B | 一次性申请全部资源，避免嵌套 |
| 不可剥夺 | 不能强行夺走他人已持有的资源 | 用 `tryLock` 带超时，失败就释放已有锁 |
| 循环等待 | 存在等待环 | 全局统一加锁顺序 |

- 经典例子：两个线程分别 `synchronized(a)` 后再 `synchronized(b)`，一个顺序是 a→b，另一个是 b→a，形成环。
- Java 环境中的额外来源：数据库锁 + 分布式锁的组合顺序不一致；线程池任务中加锁顺序不一致；连接池/对象池的内部锁与外部业务锁顺序相反；持锁时调用外部回调，回调再次尝试加锁。
- 对应实验：Lab4 死锁（复现两线程交叉加锁并用 `jstack` 观察）。

**【追问】**
- `synchronized` 死锁和 `ReentrantLock` 死锁有区别吗？
  - 成因相同；区别在自救手段：`ReentrantLock` 可以 `tryLock` 带超时，`synchronized` 不能。
- JVM 能自动解开死锁吗？
  - 不能。JVM 只能检测并打印栈，线程仍然永久阻塞。

**【易错】**
- 认为「Java 有死锁检测机制所以不会死锁」——检测到也只意味着已经卡住。
- 只考虑 `synchronized` 之间的死锁，忽略「锁 + 外部资源（DB/Redis/HTTP）」的组合。

---

### LK38 · 怎么预防死锁？有哪些具体手段？⭐⭐⭐⭐

**【一句话答】** 三类主要手段：固定加锁顺序消除循环等待、用 `tryLock` 带超时避免无限等待、把嵌套加锁改成一次性申请以消除持有并等待。

**【展开】**
- 手段 1：**全局统一加锁顺序**。
  - 给每把锁一个全局唯一 ID，加锁时按 ID 升序；
  - 用 `System.identityHashCode` 排序并不稳妥（不保证唯一、多次调用取值可能不同，需核实），更可靠的是显式分配 ID 字段。
- 手段 2：**`tryLock` + 超时 + 回退**：
```java
if (!lock1.tryLock(3, TimeUnit.SECONDS)) { return fail(); }
try {
    if (!lock2.tryLock(3, TimeUnit.SECONDS)) { lock1.unlock(); return fail(); }
    try { doWork(); } finally { lock2.unlock(); }
} finally { lock1.unlock(); }
```
  - 拿不到就释放已持有的锁并退避重试，把「永久死锁」降级为「偶发失败」。
- 手段 3：**消除嵌套**：把「先拿 A 再拿 B」改成一次原子操作（如用 `ConcurrentHashMap#compute`、单线程执行器串行化冲突操作）。
- 手段 4（辅助）：缩短临界区、不在持锁时做 IO/远程调用、避免持锁调用外部回调代码（回调可能再次加锁）。
- 手段 5（兜底）：死锁检测 + 告警（`ThreadMXBean.findDeadlockedThreads()`、JFR），配合线程池的任务超时与重启策略。

**【追问】**
- 锁对象很多时，统一顺序好维护吗？
  - 难。实践中常用「分层加锁」（按层固定顺序）或把加锁入口收敛到一个组件里，避免业务代码自由组合锁。
- `tryLock` 失败后立即重试会怎样？
  - 会加剧竞争，形成活锁；应加随机/递增退避。

**【易错】**
- 只加 `tryLock` 而忘了在失败分支释放已持有的锁——死锁依旧。
- 把「加锁超时」当成死锁预防——超时只是止损。

---

### LK39 · 线上怎么定位死锁？有哪些工具和手段？⭐⭐⭐⭐⭐

**【一句话答】** 用 `jstack -l <pid>` 或 `jcmd <pid> Thread.print -l` 抓线程栈，JVM 会输出 "Found one Java-level deadlock" 并列出参与线程及各自的锁；程序内可用 `ThreadMXBean.findDeadlockedThreads()` 在线检测。

**【展开】**
- 命令行排查：
```bash
jstack -l <pid>                  # 线程栈 + 锁信息；死锁会明确报告
jcmd <pid> Thread.print -l       # 等价能力，JDK 9+ 更常用
jcmd <pid> Thread.print -l > thread.txt
```
  - 输出关键段落：`Found one Java-level deadlock:`，随后是各线程的 `waiting to lock` / `locked <0x...> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)`，以及 `Java stack information for the threads listed above`。
  - `-l` 会额外打印持有锁与等待锁的对象信息。
  - 进程无响应时可用 `kill -3 <pid>` 让 JVM 向标准输出打印线程栈（不终止进程）。
- 图形化工具：`jconsole`、VisualVM、IDE 的线程 dump 功能。
- 程序内检测：
```java
ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
long[] ids = tmx.findDeadlockedThreads();        // monitor + ReentrantLock 都覆盖
long[] mIds = tmx.findMonitorDeadlockedThreads(); // 只覆盖 synchronized
```
  - `findDeadlockedThreads()` 同时覆盖 `ObjectMonitor` 与 `OwnableSynchronizer`（如 `ReentrantLock`）死锁；`findMonitorDeadlockedThreads()` 只覆盖 monitor。
  - 检测有开销（要遍历线程的锁关系），不适合在高频路径反复调用。
- 长时间存活的卡死但非死锁：JFR 的 `jdk.JavaMonitorEnter` / `jdk.ThreadPark` 事件，或 async-profiler 的锁模式采样。

**【追问】**
- JVM 检测到死锁后会自动解开吗？
  - 不会，只能打印栈，需要人工介入或重启进程。
- `jstack` 打不出来怎么办？
  - 换 `jcmd` / `kill -3`，或使用 `jhsdb` 附加到进程（需权限）。

**【易错】**
- 只看到 `BLOCKED` 状态就断定是死锁——`BLOCKED` 只表示在等锁。
- 用 `findMonitorDeadlockedThreads()` 去检测 `ReentrantLock` 死锁——它检测不到。

---

### LK40 · 线上排查锁问题的完整路径是什么？锁怎么选型？⭐⭐⭐⭐⭐

**【一句话答】** 先分清现象类型（可见性/原子性/死锁/饥饿/性能），再抓栈定位、看锁竞争热点；选型上默认 `synchronized`，需要超时/公平/可中断/条件队列时用 `ReentrantLock`，高并发计数用 `LongAdder`，读多写少用 `ReentrantReadWriteLock`，读极多写极少才考虑 `StampedLock`。

**【展开】**
- 排查路径：**先分类现象**（吞吐正常但请求挂住 → 死锁或长等待；CPU 高 → 自旋或 CAS 重试风暴；数据错乱 → 可见性/原子性问题）→ **抓栈**（`jstack -l` / `jcmd <pid> Thread.print -l`，看 `BLOCKED` 线程等哪把锁）→ **看热点**（JFR 的 `jdk.JavaMonitorEnter`、`jdk.ThreadPark`，async-profiler 锁模式）→ **检测告警**（`ThreadMXBean.findDeadlockedThreads()`）。
- 选型表：

| 需求 | 首选 |
|---|---|
| 普通互斥、简单临界区 | `synchronized` |
| 超时 / 可中断 / 公平 / `Condition` | `ReentrantLock` |
| 高并发计数、只增统计 | `LongAdder` |
| 单变量原子更新 | `AtomicInteger` / `AtomicReference` |
| 读多写少 | `ReentrantReadWriteLock` |
| 读极多写极少、读极短 | `StampedLock`（能处理校验失败退化） |
| 任务编排 | `CountDownLatch` / `CyclicBarrier` / `Semaphore` |

- 原则：**优先用不可变设计与消息传递减少共享状态，其次才是加锁**；临界区内不做 IO、远程调用与日志落盘。
- 版本提示：JDK 21 正式发布虚拟线程（JEP 444），虚拟线程用 `ReentrantLock` 阻塞时不 pin 载体线程；各版本对 `synchronized` 的 pinning 差异需核实。
- 对应实验：Lab7 选型基准。

**【追问】**
- 什么时候默认用 `synchronized`？
  - 绝大多数情况。它有 JVM 层优化、代码简洁、异常自动释放；只有需要超时、公平、条件队列时才升级到 `ReentrantLock`。
- 怎么验证选型是对的？
  - 用 Lab7 的基准或线上 JFR 采样对比，而不是凭经验猜。

**【易错】**
- 一上来就用最复杂的锁。选型应从最简单、最容易被 JIT 优化的方案开始。
- 在锁里做远程调用、文件 IO，把临界区撑大。
- 认为「加锁了就一定线程安全」——锁之外的共享状态同样需要处理。

---

## 八、分布式锁（暂未展开）

本节暂留空，待单机锁部分掌握后补充；参考资料见 [fliggyp6 专题 Q24 分布式锁实验](../fliggyp6/飞猪P6-Java面试真题答卷-经核实版-v2.md)。

| 编号占位 | 题目 | 状态 |
|---|---|---|
| D01 | 分布式锁的三个必要条件（互斥、防死锁、只能解自己持有的锁） | 待补 |
| D02 | Redis SET NX EX 实现与其原子性边界 | 待补 |
| D03 | Redisson 看门狗续期机制 | 待补 |
| D04 | 为什么需要续期？业务执行时间超过租期会怎样 | 待补 |
| D05 | ZooKeeper 临时节点 + Watch 机制的实现 | 待补 |
| D06 | 分布式锁的 fencing token 机制与必要性 | 待补 |
| D07 | 缓存与分布式锁的组合顺序问题 | 待补 |
| D08 | 分布式锁与本地锁的取舍边界 | 待补 |
| D09 | 主从切换/脑裂场景下分布式锁的正确性 | 待补 |
| D10 | 分布式锁的性能代价与替代方案（幂等、状态机、唯一约束） | 待补 |

---

## 附：高频追问速查

| 问题 | 关键结论 |
|---|---|
| JMM 三大特性 | 原子性、可见性、有序性；靠锁/volatile/final 保证 |
| happens-before 规则 | JSR-133 §17.4 共 **8 条**：程序顺序/锁/volatile/启动/终止/中断/终结器/传递性 |
| volatile 保证什么 | 可见性 + 有序性 + 单次读写原子性；**不保证复合操作原子性** |
| DCL 必须 volatile | 否则引用赋值可能重排到构造之前 |
| synchronized 字节码 | 同步块是 `monitorenter/monitorexit`；同步方法是 `ACC_SYNCHRONIZED` |
| 锁升级路径 | 历史四态：无锁 → 偏向 → 轻量 → 重量；JDK 15 废弃（JEP 374）、JDK 18 移除、JDK 19+ 参数不识别；现代路径无锁 → 轻量 → 重量 |
| 轻量级锁 | mark word 指向栈上 Lock Record；靠 CAS 保证互斥 |
| CAS 的缺点 | 单变量、ABA、高竞争重试浪费 |
| ABA 解法 | `AtomicStampedReference`（引用 + 版本戳） |
| `LongAdder` | base + `Cell[]` 分槽累加；`sum()` 非原子快照 |
| AQS 是什么 | 框架不是锁；模板方法 + 独占/共享两模式 |
| AQS state | 独占=重入数；共享=许可/计数；读写锁高 16 位读、低 16 位写 |
| `waitStatus` | `SIGNAL=-1`、`CONDITION=-2`、`PROPAGATE=-3`、`CANCELLED=1`（JDK 8） |
| Condition 队列 | 每个 Condition 一条单向链；`await` 进、`signal` 出 |
| 公平 vs 非公平 | 公平按 FIFO 无饥饿但吞吐低；默认非公平 |
| `hasQueuedPredecessors` | 公平锁获取前必查；非公平锁**不查**（JDK 8 字节码可验证） |
| 锁降级 | 写锁 → 读锁 → 释放写锁；读锁支持，升级不支持 |
| `StampedLock` | 乐观读无锁 + `validate` 校验；不可重入、无 `Condition`；JDK 8+ |
| 死锁四要素 | 互斥、持有并等待、不可剥夺、循环等待 |
| 死锁定位 | `jstack -l` / `jcmd Thread.print -l`；`ThreadMXBean.findDeadlockedThreads()` |
