# Java 单机并发锁专题 · 实验教程

> 配套：[interview-questions.md](./interview-questions.md)（40 题 LK01–LK40）、[README.md](./README.md)（专题总览）
>
> 实验代码：`src/main/java/edu/zjnu/interview/lock/q<NN>/L<NN><Name>Lab.java`
>
> 目标：每个实验都能亲眼看到「现象是什么、为什么会这样、怎么验证、用哪套命令抓现场」。
>
> ⚠️ **范围限定**：本专题只覆盖**单 JVM 内**的并发控制。分布式锁不在本专题展开，参见 [README.md 文末说明](./README.md#分布式锁暂未展开)。

## 实验环境

| 项 | 值 | 说明 |
|---|---|---|
| JDK | **17.0.10 (LTS)** | 本教程所有「预期输出」均在 JDK 17.0.10 上实跑取得 |
| 构建 | Maven（`maven-compiler-plugin` 显式 `release=17`） | 仓库根目录 `pom.xml` 已锁定 17 |
| 外部依赖 | **无** | 不需要 MySQL / Redis / 网络；所有实验都是纯 JVM 内存操作 |
| 线程数 | 多数实验用 4–16 线程 | 基准类实验的数值与核数强相关 |
| 参考机器 | 8 逻辑核（macOS） | 换机器后所有耗时 / 吞吐 / 自旋次数都会变 |

**每次实验独立可跑**：7 个 Lab 各自是一个 `main` 入口，互不依赖，互不共享状态，可以单独运行、单独重跑。

**统一运行命令**（下文各 Lab 的「步骤」一节不再重复贴）：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home
mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.qXX.L0X...Lab
```

> `JAVA_HOME` 必须显式设置：仓库根 `pom.xml` 锁的是 Java 17，而部分机器的默认 `java` 仍是 JDK 8，直接 `mvn` 会报 `release version 17 not supported`。
>
> 首次运行前建议确认一次：`$JAVA_HOME/bin/java -version` 应输出 `17.0.10`。

### 怎么看 PASS / FAIL

每个实验的自检点会打印统一格式的一行：

```text
  [PASS] 标签 | 期望 = X | 实际 = Y
```

- 出现 `[PASS]` = 该断言成立；
- 出现 `[FAIL]` = 该断言不成立，**这一行右侧的「实际」就是你要排查的对象**；
- 自检失败**不会抛异常中断实验**，实验会继续跑完，所以一次运行就能看到全部断言的成败。

**标注约定**：本教程在「预期输出」里区分两类值——

- **【确定值】**：与机器无关，任何机器上都应一致（如 `期望 1000000 / 实际 1000000`、PASS 的条数与标签）。这类值不符 = 实验失败或环境不对。
- **【机器相关值】**：随 CPU 型号、核数、JIT 预热、负载而变（耗时、ns/op、吞吐、自旋次数、丢数、插队次数、等待时长、线程栈里的对象地址与 pid）。这类值**只做横向趋势比较，不要当绝对指标**。

---

## Lab 与面试题对应索引

| Lab | 实验 | 对应面试题 |
|---|---|---|
| Lab 1 | 可见性与有序性 | LK01、LK02、LK03、LK04、LK05、**LK06**（DCL） |
| Lab 2 | CAS 与原子类 | LK13、LK14、**LK15**（LongAdder）、LK16、LK17、LK18 |
| Lab 3 | AQS 与可重入锁 | **LK19**、LK20、LK21、LK22、LK23、LK24、LK25、LK26、LK27、LK28 |
| Lab 4 | 死锁 | **LK37**、LK38、LK39 |
| Lab 5 | 读写锁 | **LK29**、LK30、LK31、LK32 |
| Lab 6 | StampedLock | **LK33**、LK34、LK35、LK36 |
| Lab 7 | 锁选型基准 | LK18、**LK40** |

对应 [README.md 推荐学习路线](./README.md#推荐学习路线) 的阶段划分：Lab 1–2 属阶段 1/3，Lab 3/5 属阶段 4（重心），Lab 4/6/7 属阶段 5。

---

## Lab 0 · 先确认运行环境

**目标**：确认 JDK 版本、线程数、工具链可用，避免把「环境问题」误判成「实验结论」。

### 步骤

```bash
# 1. JDK 版本（必须是 17.x）
export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home
$JAVA_HOME/bin/java -version

# 2. Maven 是否可用
mvn -v

# 3. CPU 核数（决定基准类实验的吞吐量级）
sysctl -n hw.ncpu        # macOS
nproc                   # Linux

# 4. 线程栈工具是否在位（Lab 4 / Lab 7 要用）
ls $JAVA_HOME/bin/jcmd $JAVA_HOME/bin/jstack
```

### 预期

```text
java version "17.0.10" 2024-01-16 LTS
Java(TM) SE Runtime Environment (build 17.0.10+11-LTS-240)
Java HotSpot(TM) 64-Bit Server VM (build 17.0.10+11-LTS-240, mixed mode, sharing)
```

- **【确定值】** `java version "17.0.10"`；若显示 `1.8.x` 说明 `JAVA_HOME` 没设或设错了。
- **【确定值】** `jcmd` / `jstack` 两个文件都存在。缺任何一个，Lab 4 实验 3 与 Lab 7 实验 3b 会打印「jcmd / jstack 均不可用 … 本步跳过」并**继续执行**，不会崩。
- **【机器相关值】** 核数：8。

### 验证点

- `java -version` 是 17 而不是 8；
- `mvn -v` 能跑起来；
- `jcmd` 可执行（`ls` 只看存在性，实际能否 attach 取决于系统权限，Lab 4 会实测并回退 `jstack`）。

### 面试追问

- 为什么实验要锁死 JDK 版本？因为并发行为的**观测结果**依赖 JIT 编译时机与内存屏障实现，`Node.status` 位图、`signalNext`、偏向锁废弃都是版本相关的；换版本结论要重新核。
- 「JDK 17 有没有偏向锁？」——没有。偏向锁 JDK 15 起默认禁用并废弃（JEP 374），JDK 18 移除实现（JDK-8256425），JDK 19+ `UseBiasedLocking` 直接报 `Unrecognized VM option`。现代 JDK 的锁升级路径是「无锁 → 轻量级 → 重量级」。（详见 Lab 3 实验 6 末尾的补充段）
- 「虚拟线程（JDK 21，JEP 444）会改变这些结论吗？」——虚拟线程用 `ReentrantLock` 阻塞时不 pin 载体线程；`synchronized` 的 pinning 差异各版本不同，**需核实**。本专题实验全部是平台线程，不覆盖虚拟线程。

---

## Lab 1 · 可见性与有序性

**代码**：`src/main/java/edu/zjnu/interview/lock/q01/L01VisibilityLab.java`

**目标**：把「volatile 到底保证了什么」拆成可验证的几条，并建立 happens-before 的直觉。

### 步骤

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home
mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q01.L01VisibilityLab
```

一次运行包含 6 个子实验：

| 子实验 | 验证什么 |
|---|---|
| 1 | volatile 写对读线程可见；volatile 写**之前**的普通字段写入也随之可见 |
| 2 | 普通字段**没有**可见性保证（对照组） |
| 3 | volatile **不**保证 `i++` 的原子性 |
| 4 | volatile 三条语义各自的边界（纯文本输出） |
| 5 | DCL 单例必须给静态实例字段加 volatile |
| 6 | happens-before 八条规则的可执行子集（规则 2/3/4/5/6/8） |

### 预期输出

**子实验 1**（截取关键片段）：

```text
=== 实验1: volatile 写对读线程可见，且 volatile 写之前的普通字段写入也随之可见 ===
读线程自旋次数 = 139839568，等待耗时 = 0 ms，是否退出自旋 = true
读线程读到的 payload = 42（期望 42）
  [PASS] volatile 写之前的普通字段写入也可见 | 期望 = 42 | 实际 = 42
  [PASS] 读线程最终退出自旋（volatile 写一定可见） | 期望 = true | 实际 = true
```

- `payload = 42`：**【确定值】**。
- `自旋次数 = 139839568`、`等待耗时 = 0 ms`：**【机器相关值】**（自旋次数每次都不同）。

**子实验 2**（对照组）：

```text
=== 实验2: 普通字段没有可见性保证——JMM「不保证可见」，而不是「必然看不到」 ===
读线程自旋次数 = 23479716（预算 200000000），自旋期间是否观察到写入 = true
本次读线程在自旋期间读到了写线程的写入。注意：这是「碰巧可见」，JMM 并不承诺这一点，不能作为正确性依据（不同 JVM 版本、不同 JIT 编译时机结果可能不同）。
```

- **两种结果都算正常**，代码对两种情况都分别打印了解释：
  - `= true` → 打印「碰巧可见」的提示（本次实跑就是这一支）；
  - `= false` → 打印「自旋 200000000 次始终没有读到…这属于 JMM 允许的行为」。
- `自旋次数`：**【机器相关值】**。`预算 200000000`：**【确定值】**。

**子实验 3**（volatile 不保证原子性）：

```text
=== 实验3: volatile 不保证 i++ 的原子性，10 线程并发自增会丢数 ===
volatile int 实际值 = 422538（期望 1000000）
AtomicInteger 实际值 = 1000000（期望 1000000）
  [PASS] volatile int 出现丢数 | 期望 = true | 实际 = true
  [PASS] AtomicInteger 无丢数 | 期望 = 1000000 | 实际 = 1000000
丢失的更新次数 = 577462
```

- `AtomicInteger 实际值 = 1000000` 与两条 `[PASS]`：**【确定值】**。
- `volatile int 实际值 = 422538`、`丢失的更新次数 = 577462`：**【机器相关值】**（每次不同）。唯一稳定的性质是 **< 1000000**。

**子实验 5**（DCL）：

```text
volatile 版：16 个线程共拿到 1 个不同实例（期望 1）
非 volatile 版：16 个线程共拿到 1 个不同实例（期望 1，本次观测值仅供参考）
  [PASS] volatile 版 DCL 只创建一个实例 | 期望 = 1 | 实际 = 1
非 volatile 版本次没有复现多实例——这不代表它是安全的：JMM 不给它任何保证，是否复现取决于 CPU、内存布局、JIT 编译时机等偶然因素。
```

- volatile 版 `= 1`：**【确定值】**。
- 非 volatile 版的实例数：**【机器相关值】**，可能 > 1 也可能 = 1。**两种结果都不是「安全性证明」**。

**子实验 6**（happens-before）：6 条 `[PASS]`，标签固定：

```text
  [PASS] 规则2 volatile 写 happens-before 后续读（读到 payload=7） | 期望 = 7 | 实际 = 7
  [PASS] 规则3 解锁 happens-before 后续加锁（读到 99） | 期望 = 99 | 实际 = 99
  [PASS] 规则4 start() happens-before 线程体首个操作（读到 11） | 期望 = 11 | 实际 = 11
  [PASS] 规则5 join() 返回 happens-before 主线程读（读到 33） | 期望 = 33 | 实际 = 33
  [PASS] 规则6 interrupt() happens-before 中断检测（检测到中断） | 期望 = true | 实际 = true
  [PASS] 规则8 传递性（tA 的普通写经 tB 中转后对 tC 可见） | 期望 = 7 | 实际 = 7
```

- 全部 6 条：**【确定值】**。规则 1（程序顺序）、规则 7（终结器）只做文字陈述，不实机演示——`Object.finalize()` 在 JDK 17 已被废弃。

### 验证点

1. 子实验 1 里 `payload = 42` 证明的不只是「volatile 变量可见」，而是「volatile 写之前的**所有**写入都可见」——这正是 happens-before 传递性的直接应用。
2. 子实验 2 两种结果都要能解释。**「普通变量一定看不到」是错的**，正确表述是「不保证可见」。
3. 子实验 3 里 `AtomicInteger` 精确到 1000000 是**确定值**；`volatile int` 丢多少是**概率**。丢失是必然发生的，只是幅度随机。
4. 子实验 5 里 volatile 版**必然**只有 1 个实例；非 volatile 版「没复现」不能被当成「安全」。
5. 子实验 6 覆盖 6 条可执行规则；八条规则里的程序顺序、终结器是「陈述型」规则。

### 面试追问

- **volatile 保证什么、不保证什么？**（LK03、LK05）
  - 给：可见性 + 禁止特定重排带来的有序性 + 单次读写的原子性（针对 `long`/`double`）；
  - 不给：复合操作的原子性。答的时候「保证什么」和「不保证什么」必须成对说。
- **`volatile int i; i++` 为什么丢数？**（LK04）——`i++` 是「读 → 加一 → 写」三步，volatile 只保证每一步单独正确，不保证三步之间不被打断。
- **happens-before 有几条规则？**（LK02）——JSR-133 §17.4 列的是 **8 条**，第 8 条就是传递性。流传的「7 条」是排除传递性后的计数。
- **DCL 为什么必须加 volatile？**（LK06）——`new` 的「分配内存 → 初始化 → 引用赋值」三步中，②③ 可被重排；没有 volatile 时别的线程会看到 `instance != null` 但字段还是默认值（半构造对象）。加锁只解决「不重复创建」，volatile 才解决「重排」。
- **`final` 字段的安全发布保证是 volatile 的语义吗？**——不是，是 `final` 字段自己的「初始化安全保证」，构造器结束前的写入对通过该引用读到的线程可见。
- **为什么 `Thread.onSpinWait()` 放在普通字段自旋循环里？**——它是 intrinsic、不带内存屏障，因此 JIT 允许把循环内的普通字段读提升（hoist）到循环外，这正是普通字段「可能永远看不到」的实现层原因。

---

## Lab 2 · CAS 与原子类

**代码**：`src/main/java/edu/zjnu/interview/lock/q02/L02CasAtomicLab.java`

**目标**：看清 CAS 的语义边界（只保单变量）、ABA 的成因与解法、以及 `LongAdder` 的分段累加。

### 步骤

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home
mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q02.L02CasAtomicLab
```

一次运行包含 4 个子实验：

| 子实验 | 验证什么 |
|---|---|
| 1 | 手写 CAS 自旋 vs `AtomicInteger`（结果都对，手写重试多、慢） |
| 2 | ABA 复现 + `AtomicStampedReference` 解法 |
| 3 | `LongAdder` vs `AtomicLong`（1 线程 / 8 线程两种负载） |
| 4 | `AtomicIntegerFieldUpdater` + 反射观察 `Striped64` 的 `base` / `cells` 槽位分布 |

### 预期输出

**子实验 1**：

```text
=== 实验1: 手写 CAS 自旋与 AtomicInteger 等价，但多线程下重试次数更多、性能更低 ===
总操作数 = 1600000（8 线程 × 200000）
手写 CAS 自增器 实际值 = 1600000（期望 1600000）
AtomicInteger    实际值 = 1600000（期望 1600000）
手写 CAS 自增器  总耗时 = 169 ms, 平均 = 106.0 ns/op
AtomicInteger    总耗时 = 58 ms, 平均 = 36.5 ns/op（CAS 由 JDK 内部 Unsafe 完成，失败重试路径更短）
  [PASS] 手写 CAS 自增器结果正确（无丢失更新） | 期望 = 1600000 | 实际 = 1600000
  [PASS] AtomicInteger 结果正确（无丢失更新） | 期望 = 1600000 | 实际 = 1600000
```

- 两个「实际值 = 1600000」与两条 `[PASS]`：**【确定值】**。
- 耗时 / ns/op：**【机器相关值】**。**趋势**稳定：手写 CAS 比 `AtomicInteger` 慢（本次 169 ms vs 58 ms）；但**倍数不固定**。

**子实验 2**（ABA）：

```text
=== 实验2: ABA 问题——值从 A 变 B 再变回 A 后，CAS 会「误判」为未发生变化 ===
值经历 A → B → A 之后，读线程持有的期望值 = A，CAS(期望值 → "C") 结果 = true（期望 false，实际发生了误判）
  [PASS] AtomicReference 出现 ABA 误判（CAS 意外成功） | 期望 = true | 实际 = true
AtomicStampedReference: CAS 前的版本号 = 0，当前版本号 = 2，当前值 = A
AtomicStampedReference.compareAndSet 结果 = false（期望 false，版本号不同 → 拒绝修改）
  [PASS] AtomicStampedReference 通过版本号拒绝了 ABA 误判 | 期望 = false | 实际 = false
```

- 全部 4 行：**【确定值】**（版本号 0 → 2 也是确定的：中间做了一次 A→B→A，两次 CAS 各让 stamp 递增 1）。

**子实验 3**（LongAdder vs AtomicLong）：

```text
-- 线程数 = 1，总操作数 = 2000000 --
  AtomicLong : 10 ms, 5.2 ns/op, 最终值 = 2000000
  LongAdder  : 12 ms, 6.2 ns/op, 最终值 = 2000000
-- 线程数 = 8，总操作数 = 4000000 --
  AtomicLong : 136 ms, 34.1 ns/op, 最终值 = 4000000
  LongAdder  : 9 ms, 2.5 ns/op, 最终值 = 4000000
```

- `最终值`：**【确定值】**（等于总操作数）。
- 耗时 / ns/op：**【机器相关值】**。
- **趋势**（本实验要看的重点）：
  - 1 线程：两者接近，甚至 `LongAdder` 略慢（低竞争时 `LongAdder` 走 `base` 的 CAS，路径比 `AtomicLong` 长一点点）。
  - 8 线程：`LongAdder` 明显更快（本次 9 ms vs 136 ms）。**倍数随机器而变**，不要背数字。

**子实验 4**（槽位分布）：

```text
AtomicIntegerFieldUpdater: getAndIncrement 前 = 0，后 = 1（期望 0 → 1）
  [PASS] AtomicIntegerFieldUpdater 原子自增 | 期望 = 1 | 实际 = 1
LongAdder 累加结果 = 1600000（期望 1600000）
  [PASS] LongAdder 累加结果正确 | 期望 = 1600000 | 实际 = 1600000
Striped64.base = 928（未被 Cell 分摊掉的部分）
Striped64.cells 数组长度 = 8（Cell 槽位数量，CPU 核数 = 8）
各 Cell 槽位的值 = [201850, 200000, 199123, 199939, 200000, 198996, 199170, 199994]
  [PASS] LongAdder 把累加分散到了多个 Cell 槽位 | 期望 = true | 实际 = true
说明：base + 所有 Cell 的和 = 928 + 各槽位之和 = 1600000；线程数少于槽位数时，槽位不一定被全部占满。
```

- `累加结果 = 1600000`、两条 `[PASS]`：**【确定值】**。
- `base = 928`、各槽位数值：**【机器相关值】**（每次不同）。
- `cells 数组长度 = 8`：**【机器相关值】**，但等于 CPU 核数（本次 8 核）——`Striped64` 按核数定初始槽位规模。

### 验证点

1. 手写 CAS 与 `AtomicInteger` **结果都正确**：CAS 保证了「比较 + 写入」的原子性，不保证读-改-写序列只执行一次——这是「结果正确但会重试」的含义。
2. ABA：`AtomicReference` 的 CAS **成功**了（误判），`AtomicStampedReference` 的 CAS **失败**（版本号 0 ≠ 2）。两者对比是本实验的核心证据。
3. `LongAdder` 的性能优势**只在高竞争下出现**；1 线程时它并不更快。面试里说「LongAdder 比 AtomicLong 快」必须补上「高竞争写多场景」。
4. `LongAdder` 确实把累加**分散到了多个 Cell**（`cells` 数组长度 = 8，各槽位都有值），而不是全压在 `base` 上。
5. `AtomicIntegerFieldUpdater` 对已有 `volatile int` 字段提供了原子自增，且**不新增对象**。

### 面试追问

- **CAS 的三大缺点？**（LK13）——只能保证单变量；存在 ABA；高竞争下失败重试浪费 CPU。
- **ABA 怎么解？**（LK14）——`AtomicStampedReference` 给引用配单调递增版本戳，CAS 时同时比引用和版本号。也可以「不复用对象」或「改用锁」。
- **`LongAdder` 的 `sum()` 精确吗？**（LK15）——**不精确**，不是某一瞬间的强一致快照；`sumThenReset` 同理。需要精确值（如作为唯一 ID 的一部分）要用 `AtomicLong`。
- **`LongAdder` 为什么比 `AtomicLong` 快？**——`AtomicLong` 所有线程争同一个 cache line；`LongAdder` 把争抢分散到 `Cell[]` 的多个槽位，`Cell` 上带 `@Contended` 注解填充伪共享。扩容时用 `cellsBusy` 自旋锁串行化。
- **`AtomicIntegerFieldUpdater` 什么时候用？**（LK16）——海量小对象各带一个计数器时，比给每个对象分配一个 `AtomicInteger` 省内存、少 GC。约束：目标字段必须是 `volatile` 的 `int`、不能是 `final`、Updater 应为 `static final` 单例。
- **乐观锁 vs 悲观锁？**（LK18）——乐观锁假设冲突少（CAS、版本号、`LongAdder`），悲观锁假设冲突会发生（`synchronized`、`ReentrantLock`、`SELECT ... FOR UPDATE`）。高冲突下乐观锁的重试风暴可能比阻塞更贵。

---

## Lab 3 · AQS 与可重入锁

**代码**：`src/main/java/edu/zjnu/interview/lock/q03/L03AqsQueueLab.java`

**目标**：把 AQS 从「概念」落到「源码行 + 实机观测」——它是怎么排队的、怎么唤醒的、`state` 到底编码了什么。

### 步骤

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home
mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q03.L03AqsQueueLab
```

一次运行包含 6 个子实验：

| 子实验 | 验证什么 |
|---|---|
| 1 | 继承 AQS 手写可重入锁（互斥 / 可重入 / 非持有者解锁报错） |
| 2 | 反射读 `ReentrantLock` / `Semaphore` / `CountDownLatch` 的 `state`，看三种语义 |
| 3 | 打印 JDK 17.0.10 `src.zip` 里 AQS 的关键源码片段（带行号） |
| 4 | 公平锁 vs 非公平锁：插队次数 + 首轮获取顺序 |
| 5 | `Condition` 的 `await` / `signal` / `signalAll` / 中断 |
| 6 | AQS 队列节点的状态位（实机反射读 tail 节点） |

> 子实验 3 的源码片段是从**运行时的 JDK 17.0.10 `src.zip`** 里抽出来的，行号以该 `src.zip` 内文件为准。换 JDK 版本行号会变、片段也可能变。

### 预期输出

**子实验 1**：

```text
=== 实验1: 继承 AQS 手写可重入锁——互斥、可重入、非持有者解锁报错 ===
8 线程各加 10000 次，最终值 = 80000（期望 80000）
临界区同时进入的最大线程数 = 1（期望 1）
  [PASS] 自定义锁保证互斥 | 期望 = 1 | 实际 = 1
  [PASS] 自定义锁保证计数不丢失 | 期望 = 80000 | 实际 = 80000
同一线程加锁两次后 state = 2（期望 2）
  [PASS] 自定义锁可重入 | 期望 = 2 | 实际 = 2
解锁一次后 state = 1（期望 1）
解锁两次后 state = 0（期望 0）
  [PASS] 重入计数正确递减 | 期望 = 0 | 实际 = 0
非持有者线程调用 unlock() 的结果 = IllegalMonitorStateException（期望 IllegalMonitorStateException）
  [PASS] 非持有者解锁抛 IllegalMonitorStateException | 期望 = IllegalMonitorStateException | 实际 = IllegalMonitorStateException
```

- 全部：**【确定值】**（含 5 条 `[PASS]`）。

**子实验 2**（`state` 的三种语义）：

```text
ReentrantLock 的 sync 类型 = java.util.concurrent.locks.ReentrantLock$NonfairSync
  未加锁时 state = 0（语义：0 = 未加锁）
  加锁一次后 state = 1（语义：n = 被持有 n 次）
  重入一次后 state = 2
  解锁两次后 state = 0（回到 0 = 未加锁）
Semaphore(3) 的 state = 3（语义：可用许可数）
  acquire() 一次后 state = 2
  release() 一次后 state = 3
CountDownLatch(5) 的 state = 5（语义：剩余计数）
  countDown() 两次后 state = 3
```

- 全部数值：**【确定值】**。

**子实验 3**（JDK 17 AQS 源码，节选）：

```text
[2] acquire(int)：先试一次，失败才进队列（AQS.java 第 936-939 行）
public final void acquire(int arg) {
    if (!tryAcquire(arg))
        acquire(null, arg, false, false, false, 0L);
}

[3] release(int)：tryRelease 返回 true 后，由 AQS 负责唤醒后继（AQS.java 第 1006-1014 行）
public final boolean release(int arg) {
    if (tryRelease(arg)) {
        signalNext(head);
        return true;
    }
    return false;
}

[4] signalNext(Node)：JDK 17 的唤醒实现——清 WAITING 位 + unpark（AQS.java 第 607-613 行）
private static void signalNext(Node h) {
    Node s;
    if (h != null && (s = h.next) != null && s.status != 0) {
        s.getAndUnsetStatus(WAITING);
        LockSupport.unpark(s.waiter);
    }
}
```

- 源码片段本身：**【确定值】**（固定为 JDK 17.0.10 `src.zip` 的内容）。
- **行号**：绑定 JDK 17.0.10 的 `src.zip`。换 JDK 版本行号会变——**以你机器上 `src.zip` 的实际行号为准**。

**子实验 4**（公平 vs 非公平）：

```text
插队实验（每种模式 20 次）：
  公平锁：主线程插到已排队线程前面的次数 = 0（期望 0）
  非公平锁：主线程插到已排队线程前面的次数 = 20（期望 > 0）
  [PASS] 公平锁不插队 | 期望 = true | 实际 = true
  [PASS] 非公平锁允许插队 | 期望 = true | 实际 = true
10 线程同时起跑的首轮获取顺序：
  公平锁   = [9, 0, 2, 1, 3, 4, 5, 6, 7, 8]
  非公平锁 = [9, 0, 1, 2, 3, 4, 5, 6, 7, 8]
公平锁首轮顺序是否严格按线程启动顺序递增 = false；非公平锁 = false
```

- `公平锁插队 = 0`：**【确定值】**。
- `非公平锁插队次数 = 20`：**【机器相关值】**（实测区间常见 13–20），只要 **> 0** 即可。
- 两行「首轮获取顺序」数组：**【机器相关值】**，每次不同。**注意**：两者的第一项都是 `9`——起跑时最后一个被调度到的线程往往先抢到，谁先到 `lock()` 谁先拿。
- 「公平锁首轮顺序是否严格按线程启动顺序递增 = false」：**【确定值】**。公平锁保证的是「按进入 AQS 同步队列的先后」，不是「按线程启动顺序」。

**子实验 5**（Condition）：

```text
生产 5 个、消费 5 个，消费序列 = [0, 1, 2, 3, 4]
  [PASS] await/signal 完成生产者-消费者且顺序正确 | 期望 = [0, 1, 2, 3, 4] | 实际 = [0, 1, 2, 3, 4]
signalAll 后被唤醒的等待者数量 = 3 / 3（期望 3）
  [PASS] signalAll 唤醒全部等待者 | 期望 = true | 实际 = true
等待期间被中断的结果 = InterruptedException（getMessage() = null）
  [PASS] await 期间被中断抛 InterruptedException | 期望 = true | 实际 = true
```

- 全部：**【确定值】**（消费序列严格递增是 `signal` 的 FIFO 语义体现）。

**子实验 6**（节点状态位）：

```text
实机观察：等待线程已进入同步队列，tail 节点的 status = 1
  status & 1（WAITING）  = true（期望 true：线程已 park 等待唤醒）
  status & 2（COND）     = false（期望 false：它等的是锁，不是条件）
  status < 0（CANCELLED）= false（期望 false）
  [PASS] 排队中的节点带有 WAITING 位 | 期望 = true | 实际 = true
```

- `status = 1` 与三条判定：**【确定值】**。

程序末尾还有一段**偏向锁补充**（JDK 17 没有偏向锁阶段）：

```text
【补充：JDK 17 没有偏向锁阶段】
偏向锁是 JDK 6 引入、JDK 15 起废弃（JEP 374，默认禁用并废弃全部相关参数）、JDK 18 移除实现（JDK-8256425）的机制；JDK 19 起 UseBiasedLocking 直接是 Unrecognized VM option。
因此现代 JDK（JDK 17 实验基线）的锁升级路径是「无锁 → 轻量级 → 重量级」，没有偏向阶段；「无锁 → 偏向 → 轻量级 → 重量级」四阶段说法是 JDK 8 特有的历史机制。
```

### 验证点

1. 手写 AQS 子类只实现了 `tryAcquire` / `tryRelease` 两个方法，就拿到了互斥 + 可重入 + 正确的 owner 校验——证明 AQS 是**框架**不是锁。
2. `ReentrantLock` / `Semaphore` / `CountDownLatch` 三个子类的 `state` 语义完全不同，但底层是**同一个 `volatile int`**。
3. JDK 17 的唤醒路径是 `release()` → `signalNext(head)` → `LockSupport.unpark(s.waiter)`。**JDK 17 已经没有 `unparkSuccessor` 这个方法了**（JDK 8 特有，JDK 11 同），网上流传的「在 `tryRelease` 里调用 `unparkSuccessor`」的手写模板在 JDK 8 之后编译不过。
4. 公平锁插队 **0** 次、非公平锁插队 **> 0** 次——这就是 `hasQueuedPredecessors()` 这一个判断造成的全部差异。
5. Condition 的 `await` 会**原子地**释放锁并进条件队列，`signal` 只是把等待者搬回同步队列重新竞争（不保证立刻拿到锁）。
6. 实机读到 tail 节点 `status = 1`（WAITING 位），证明 JDK 17 用的是**位图**而不是 JDK 8 的 `waitStatus` 四值枚举。

### 面试追问

- **AQS 是什么？**（LK19）——框架不是锁。模板方法：固定 `acquire`/`release`/`acquireShared`/`releaseShared` 流程，变化点留给子类的 `tryAcquire`/`tryRelease`/`tryAcquireShared`/`tryReleaseShared`。
- **`state` 有哪几种语义？**（LK20）——独占=重入次数；共享=许可数/倒计数；读写锁=高 16 位读计数、低 16 位写计数。
- **`acquire` / `release` 的完整流程？**（LK23）——先 `tryAcquire`，失败则 `addWaiter` 入队 → `acquireQueued` 自旋若干次 → `LockSupport.park` 阻塞 → 被 `unpark` 唤醒后重新竞争 → 成功后 `setHead` 并唤醒后继。释放侧：`tryRelease` 成功后 `signalNext`。
- **`lock()` 会被中断吗？**——不会抛 `InterruptedException`，但中断标志被记住，拿到锁后由 `selfInterrupt` 恢复。要可中断用 `lockInterruptibly()`。
- **JDK 8 的 `waitStatus` 有哪些值？JDK 17 呢？**（LK22、LK24）——JDK 8：`SIGNAL=-1`、`CONDITION=-2`、`PROPAGATE=-3`、`CANCELLED=1`；JDK 17 改名 `status`，改用位：`WAITING=1`、`COND=2`、`CANCELLED=0x80000000`。**JDK 17 没有 `SIGNAL`/`CONDITION`/`PROPAGATE` 这三个常量**。
- **Condition 队列和同步队列的区别？**（LK25）——条件队列每个 `Condition` 一条、单向链、只放 `await` 的线程；同步队列是 CLH 双向队列、竞争锁。JDK 17 把条件队列节点拆成独立的 `ConditionNode`。
- **公平锁和非公平锁差在哪？**（LK27、LK28）——只差 `hasQueuedPredecessors()` 这一个判断。公平锁每次获取都要查队列、每次释放都要唤醒队首，吞吐更低；非公平锁允许 barging，有饥饿风险。
- **默认用哪个？**——默认非公平；只有观察到长期饥饿才改公平。

---

## Lab 4 · 死锁

**代码**：`src/main/java/edu/zjnu/interview/lock/q04/L04DeadlockLab.java`

**目标**：复现死锁 → 程序内自检 → **jcmd 抓现场** → 三种预防手段。

### 步骤

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home
mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q04.L04DeadlockLab
```

一次运行包含 6 个子实验：

| 子实验 | 验证什么 |
|---|---|
| 1 | 两张表交叉加锁 → 经典死锁（`synchronized`） |
| 2 | `ThreadMXBean.findDeadlockedThreads()` 程序内定位 |
| 3 | **`jcmd <pid> Thread.print` 抓死锁现场** |
| 4 | 预防手段一：全局固定加锁顺序 |
| 5 | 预防手段二：`tryLock` 超时 + 降级重试 |
| 6 | 预防手段三：合并临界区，消除嵌套锁 |

**死锁线程被设成守护线程**（`setDaemon(true)`），`main` 也不 `join` 它们——否则 JVM 退不出去，实验变成永久挂起。

### 预期输出

**子实验 1**：

```text
=== 实验1: 两张表交叉加锁 -> 经典死锁 ===
  [T1-先持表A] state=BLOCKED 等待的锁=LockInfo@36a42db9(表B) 持有=MonitorInfo@775d0be(表A)
      at edu.zjnu.interview.lock.q04.L04DeadlockLab.transfer(L04DeadlockLab.java:112)
      ...
  [T2-先持表B] state=BLOCKED 等待的锁=LockInfo@775d0be(表A) 持有=MonitorInfo@36a42db9(表B)
      at edu.zjnu.interview.lock.q04.L04DeadlockLab.transfer(L04DeadlockLab.java:112)
      ...
死锁线程状态: BLOCKED / BLOCKED (BLOCKED = 等待 monitor)
```

- `state=BLOCKED` / `BLOCKED / BLOCKED`：**【确定值】**。
- `LockInfo@36a42db9` / `MonitorInfo@775d0be` 这类**对象地址**：**【机器相关值】**，每次运行都不同。
- 栈帧里的 `L04DeadlockLab.java:112`：**【确定值】**（行号随源码改动而变，本文档对应当前提交）。
- **关键对照**：`T1` 等待的锁就是 `T2` 持有的锁，`T2` 等待的锁就是 `T1` 持有的锁——这就是环。

**子实验 2**：

```text
=== 实验2: ThreadMXBean.findDeadlockedThreads() 程序内定位死锁 ===
[兜底] 2000ms 后死锁仍未解除, 死锁线程数=2; main 不 join 死锁线程, 实验可继续, 守护线程保证 JVM 能退出
  期望: 检出 2 个死锁线程 -> 实际: 2
  死锁线程 T1-先持表A state=BLOCKED 等待的锁=LockInfo@36a42db9(表B)
      持有关系: MonitorInfo@775d0be(表A)
      阻塞点: edu.zjnu.interview.lock.q04.L04DeadlockLab.transfer(L04DeadlockLab.java:112)
  死锁线程 T2-先持表B state=BLOCKED 等待的锁=LockInfo@775d0be(表A)
      持有关系: MonitorInfo@36a42db9(表B)
      阻塞点: edu.zjnu.interview.lock.q04.L04DeadlockLab.transfer(L04DeadlockLab.java:112)
```

- `检出 2 个死锁线程`：**【确定值】**。
- 兜底行里的 `2000ms`：**【确定值】**（代码里写死的超时）。
- 对象地址：**【机器相关值】**。

**子实验 3（jcmd 抓现场，重点）**：

```text
=== 实验3: jcmd <pid> Thread.print 现场抓死锁现场 ===
  当前进程 pid = 24199
  使用工具: /Library/Java/JavaVirtualMachines/JDK17/Contents/Home/bin/jcmd
  期望: 输出包含 "Found one Java-level deadlock" -> 实际: true
  --- 关键片段 ---
  | Found one Java-level deadlock:
  | waiting to lock monitor 0x000000012608a080 (object 0x000000070bb10f48, a edu.zjnu.interview.lock.q04.L04DeadlockLab$Table),
  | waiting to lock monitor 0x000000012608a150 (object 0x000000070bb10f00, a edu.zjnu.interview.lock.q04.L04DeadlockLab$Table),
  | Java stack information for the threads listed above:
  | - waiting to lock <0x000000070bb10f48> (a edu.zjnu.interview.lock.q04.L04DeadlockLab$Table)
  | - locked <0x000000070bb10f00> (a edu.zjnu.interview.lock.q04.L04DeadlockLab$Table)
  | - waiting to lock <0x000000070bb10f00> (a edu.zjnu.interview.lock.q04.L04DeadlockLab$Table)
  | - locked <0x000000070bb10f48> (a edu.zjnu.interview.lock.q04.L04DeadlockLab$Table)
```

- `期望: 输出包含 "Found one Java-level deadlock" -> 实际: true`：**【确定值】**。这是本 Lab 最关键的一行。
- `pid`、`monitor 0x...`、`object 0x...`：**【机器相关值】**。
- **结构是确定的**：两行 `waiting to lock monitor`、四行 `- waiting to lock` / `- locked` 交替出现。第一个对象被 T1 等、被 T2 持；第二个对象被 T2 等、被 T1 持。

**手动复现这一招**（实验本身已经自动调用了 jcmd；这里演示线上排查的完整命令）：

```bash
# 终端 1：跑实验（死锁一旦形成就会一直保持）
export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home
mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q04.L04DeadlockLab

# 终端 2：拿 pid —— 三种方式任选
jps -l | grep L04DeadlockLab        # 最常用
# 或从实验输出里读「当前进程 pid = 24199」
# 或：pgrep -f L04DeadlockLab

# 终端 2：抓栈
jcmd <pid> Thread.print | grep -A 20 'Found one Java-level deadlock'
# 等价写法：
jstack -l <pid> | grep -A 20 'Found one Java-level deadlock'
# 进程无响应、attach 不上时（SIGQUIT，不终止进程）：
kill -3 <pid>
```

> `kill -3` 会让 JVM 向**标准输出**打印线程栈，进程不终止。在 IDE 里跑时输出可能跳到控制台，注意看终端而不是只看 IDE 控制台。

**子实验 4（固定加锁顺序）**：

```text
=== 实验4: 预防手段一 —— 全局固定加锁顺序 ===
  全局顺序: 先 表A 后 表B (由 identityHashCode 决定, 与线程无关)
  线程 id: T1=22, T2=23 (id 只用于观察先后, 不参与加锁顺序决策)
  反例: 若拿 Thread.getId() 当排序依据(T1.id < T2.id -> T1 先锁 表A, T2 先锁 表B), 就退回了实验1 的交叉加锁, 必然死锁;
  期望: 2 个线程全部完成, 无死锁 -> 实际: 完成 2 个
```

- `完成 2 个`：**【确定值】**。
- `线程 id: T1=22, T2=23`：**【机器相关值】**（线程 id 每次不同）。
- `全局顺序: 先 表A 后 表B`：**【机器相关值】**——由 `identityHashCode` 决定，每次运行可能是「先 表B 后 表A」。

**子实验 5（tryLock 超时）**：

```text
=== 实验5: 预防手段二 —— tryLock 超时, 失败走降级 ===
  [降级] tryLock(100ms) 返回 false, 放弃本次操作, 走补偿/重试路径
  [重试] 退避后重新 tryLock 成功
  期望: 1 次成功 + 1 次降级(超时) -> 实际: 成功 1 次, 降级 1 次
```

- `成功 1 次, 降级 1 次`：**【确定值】**。

**子实验 6（消除嵌套）**：

```text
=== 实验6: 预防手段三 —— 合并临界区, 消除嵌套锁 ===
  期望: 2 个线程全部完成 -> 实际: 完成 2 个
```

- `完成 2 个`：**【确定值】**。

### 验证点

1. 子实验 1 里两个线程都是 `BLOCKED`（等待 monitor）——**`BLOCKED` 只表示在等锁，不等于死锁**；判定死锁的依据是「形成了环」。
2. 子实验 2 证明程序内也能自检：`findDeadlockedThreads()` 返回线程 id，配 `getThreadInfo(id, true, true)` 才能同时拿到「持有谁」和「等谁」。
3. 子实验 3 的 `Found one Java-level deadlock` 是 JVM 级的死锁判定标志，**只有真死锁才有**。它同时给出「等哪把」和「持哪把」，一对照就能还原环路。
4. **JVM 检测到死锁后不会自动解开**，只是打印栈，线程仍然永久阻塞。
5. 子实验 4/5/6 三个预防手段分别打破死锁四条件中的**循环等待**（固定顺序）、**不可剥夺**（`tryLock` 超时后可放弃）、**持有并等待**（合并临界区）。
6. 固定加锁顺序的关键是「顺序由**全局一致**的规则决定」——用 `Thread.getId()` 这类线程私有信息决定顺序等于没定。

### 面试追问

- **死锁的四个必要条件？**（LK37）——互斥、持有并等待、不可剥夺、循环等待。打破任意一个即可预防。
- **`synchronized` 死锁和 `ReentrantLock` 死锁有区别吗？**——成因相同；区别在自救手段：`ReentrantLock` 可以 `tryLock` 带超时，`synchronized` 不能。
- **线上怎么定位死锁？**（LK39）——`jstack -l <pid>` / `jcmd <pid> Thread.print -l`；程序内用 `ThreadMXBean.findDeadlockedThreads()`（覆盖 monitor + `OwnableSynchronizer`），`findMonitorDeadlockedThreads()` 只覆盖 monitor。进程无响应时用 `kill -3 <pid>`。
- **预防手段有哪些？**（LK38）——固定加锁顺序；`tryLock` + 超时 + 回退（**失败分支必须释放已持有的锁**）；消除嵌套锁；缩短临界区、不在持锁时做 IO / 远程调用 / 外部回调。
- **`tryLock` 失败后立即重试会怎样？**——加剧竞争，可能活锁；应加随机 / 递增退避。
- **死锁和饥饿怎么区分？**（LK32）——死锁时 `jstack` 明确报 `Found one Java-level deadlock`；饥饿没有任何 JVM 级标记，只能靠长期观测线程等待时长。

---

## Lab 5 · 读写锁

**代码**：`src/main/java/edu/zjnu/interview/lock/q05/L05ReadWriteLockLab.java`

**目标**：读读共享 / 读写互斥的实机验证、锁降级、**锁升级为什么不支持**、写线程饥饿的真相。

### 步骤

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home
mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q05.L05ReadWriteLockLab
```

一次运行包含 7 个子实验：

| 子实验 | 验证什么 |
|---|---|
| 1 | 读读共享：两个读线程同时在临界区 |
| 2 | 读写互斥：写线程必须等读线程释放 |
| 3 | 锁降级（写 → 读）不丢写 |
| 4 | **锁升级（读 → 写）不支持** |
| 5 | 公平锁 vs 非公平锁的插队次数 |
| 6 | 写线程饥饿：读线程会不会一直插队 |
| 7 | `ReentrantReadWriteLock` 与 `StampedLock` 的定位对照表 |

### 预期输出

**子实验 1**：

```text
=== 实验1: 读读共享 —— 两个读线程同时进入临界区 ===
  期望: 同时在读临界区的线程数 = 2 -> 实际: 2
  读线程1 区间: [0.2ms, 155.3ms]
  读线程2 区间: [0.3ms, 155.3ms]
  期望: 两个区间重叠(证明并发进入) -> 实际: true
```

- `同时在读临界区的线程数 = 2`、重叠 `= true`：**【确定值】**。
- 两个区间的具体毫秒数：**【机器相关值】**。

**子实验 2**：

```text
=== 实验2: 读写互斥 —— 写线程必须等读线程释放读锁 ===
  读线程释放读锁: 305.7ms, 写线程获得写锁: 305.9ms
  期望: 写锁获得时刻晚于读锁释放时刻 -> 实际: true (间隔 0.1ms)
```

- `= true`（写锁获得时刻晚于读锁释放时刻）：**【确定值】**。
- 两个时刻与间隔：**【机器相关值】**。

**子实验 3（锁降级）**：

```text
=== 实验3: 锁降级 (持写锁 -> 取读锁 -> 释放写锁) ===
  期望: 读锁内读到 42(自己写的值, 不丢写) -> 实际: 42
  期望: 仍持有读锁 -> 实际: 读锁持有计数 = 1, 仍持有写锁: false
  对照(先释放写锁再取读锁): 期望读到 7 -> 实际: 7  <- 中间有窗口, 值可能已被其他线程改写, 这就是必须先取读锁的原因
```

- `读到 42`、`读锁持有计数 = 1`、`仍持有写锁: false`、对照读到 `7`：**【确定值】**。

**子实验 4（锁升级不支持，重点）**：

```text
=== 实验4: 锁升级 (持读锁再取写锁) —— 不支持 ===
  tryLock() 期望: 无法升级 -> 实际: 返回 false
  tryLock(100ms) 期望: 无法升级 -> 实际: 返回 false
  lock() 期望: 永久阻塞(无法升级) -> 实际: 线程仍存活 = true (true = 卡在等写锁, 即自我死锁)
```

- 三行结果：**【确定值】**。
- **注意**：`tryLock` 返回 `false`、**不抛 `IllegalMonitorStateException`**；`lock()` 是**永久阻塞**（不是抛异常）。`IllegalMonitorStateException` 是「未持有锁却去 unlock」才抛。

**子实验 5（公平 vs 非公平）**：

```text
=== 实验5: 公平锁 vs 非公平锁 ===
  非公平锁: 200 轮竞争, 后到线程插队成功 7 次
  公平锁  : 200 轮竞争, 后到线程插队成功 0 次
  期望: 公平锁插队 0 次(严格 FIFO), 非公平锁允许插队 -> 实际见上
```

- `公平锁插队 0 次`：**【确定值】**。
- `非公平锁插队 7 次`：**【机器相关值】**（实测区间常见 4–20），只要 **> 0** 即可。

**子实验 6（写线程饥饿）**：

```text
=== 实验6: 写线程饥饿 —— 读线程会不会一直插队 ===
  非公平锁: 写线程已入队 = true, 入队后仍完成的读操作次数 = 0, 写线程等待 31.539ms
  公平锁  : 写线程已入队 = true, 入队后仍完成的读操作次数 = 0, 写线程等待 34.977ms
  期望: 只要写线程已经排队, 新读线程就应被挡住(读操作次数接近 0) -> 实际见上
```

- `写线程已入队 = true`、`入队后仍完成的读操作次数 = 0`：**【确定值】**。
- `写线程等待 31.539ms` / `34.977ms`：**【机器相关值】**（≈ 当前读线程的剩余持有时长）。

**子实验 7**（对照表）：固定文本，含 7 行维度（可重入 / Condition / 乐观读 / 写→读降级 / 读→写升级 / API 复杂度）+ 面试点。

### 验证点

1. 子实验 1 的区间**重叠**是「读读共享」的证据（不是「读锁很慢」）。
2. 子实验 2 的时间戳**先后关系**是「读写互斥」的证据——写锁的等待时间包含读锁的持有时长。
3. 子实验 3 里「先取读锁再释放写锁」读到 `42`（自己写的值），而「先释放写锁再取读锁」读到 `7`（别人的值）——**这就是降级存在的理由**。
4. 子实验 4 是本 Lab 最容易被答错的点：`tryLock` 返回 `false`，`lock()` 永久阻塞，**都不是抛异常**。
5. 子实验 6 说明：**「读线程无限插队饿死写线程」不是常态**——非公平模式下 `readerShouldBlock()` = `apparentlyFirstQueuedIsExclusive()`，只要队首是等待中的写者，新读线程就排队。真正的插队窗口在写线程**尚未成为队首**时。
6. 公平锁与非公平锁在读写锁上的插队差异，与 Lab 3 里 `ReentrantLock` 的结论一致。

### 面试追问

- **读写锁的语义和状态编码？**（LK29）——读读共享、读写互斥、写写互斥；`state` 高 16 位是读计数、低 16 位是写计数（`SHARED_SHIFT=16`、`SHARED_UNIT=1<<16`、`EXCLUSIVE_MASK=0xffff`）。同一线程可同时持有写锁和读锁（降级的前提）。
- **什么是锁降级？**（LK30）——持写锁 → 取读锁 → 释放写锁。降级后仍持读锁，其他写者进不来，因此读到的一定是自己写完的最终值。
- **为什么不支持锁升级？**（LK31）——升级必须先释放读锁，「释放读锁 → 获得写锁」之间存在窗口，别的写者可能插入并改数据；等本线程拿到写锁，基于旧读出值的判断已失效。**不是 API 缺失，是正确性上做不到无窗口**。
- **`tryLock` 拿不到写锁时抛异常吗？**——不抛。实测返回 `false`（带超时的版本同样返回 `false`）；`lock()` 则是永久阻塞。
- **读写锁会饿死写线程吗？**（LK32）——非公平模式下新读线程在队首有写者时会排队，但存在写者尚未成为队首的插队窗口；公平模式下写线程有明确等待上界，代价是吞吐更低。
- **读写锁和 `StampedLock` 怎么选？**（LK36）——需要可重入 / `Condition` / `Lock` 接口集成 → `ReentrantReadWriteLock`；读极多写极少、读极短、能处理校验失败退化 → `StampedLock`。

---

## Lab 6 · StampedLock

**代码**：`src/main/java/edu/zjnu/interview/lock/q06/L06StampedLockLab.java`

**目标**：乐观读到底怎么用、什么时候必须退化、为什么不可重入、以及与读写锁的吞吐差异。

### 步骤

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home
mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q06.L06StampedLockLab
```

一次运行包含 5 个子实验：

| 子实验 | 验证什么 |
|---|---|
| 1 | 乐观读：不加锁直接读，`validate` 校验 |
| 2 | 读到旧值 → `validate` 失败 → 必须退化重读 |
| 3 | **不可重入**：同线程二次 `writeLock()` 永久阻塞 |
| 4 | 锁转换：写→读降级、乐观读→写升级 |
| 5 | 吞吐对比：`synchronized` / `ReentrantReadWriteLock` / `StampedLock` |

### 预期输出

**子实验 1**：

```text
=== 实验1: 乐观读 —— 不加锁直接读, 靠 validate 校验 ===
  期望: stamp 非 0 且 validate=true -> 实际: stamp=256, 读到值=100, validate=true
  期望: 乐观读期间不持有任何锁 -> 实际: isReadLocked=false, isWriteLocked=false
  期望: 同一时刻两次乐观读拿到相同 stamp(它不是锁) -> 实际: 256 vs 256, 相同=true
```

- `读到值=100`、`validate=true`、`isReadLocked=false`、`isWriteLocked=false`、`相同=true`：**【确定值】**。
- `stamp=256`：**【机器相关值】**（stamp 的低位编码读锁计数，值随状态变化；两次读到相同 stamp 才说明「乐观读不占锁」）。

**子实验 2（校验失败必须退化）**：

```text
=== 实验2: 读到的值是旧的, validate 失败 -> 必须重试 ===
  第 1 次: 读到的值=0, validate=false, 重试次数=0
  写锁持有期间 tryOptimisticRead() 返回 0 (0 = 获取失败, 连乐观读都拿不到 stamp)
  第 2 次: 读到的值=42, validate=true, 重试次数=1
  期望: 第 1 次校验失败, 重试后读到 42 且校验通过 -> 实际: true
```

- `第 1 次` 读到 `0`、`validate=false`、`重试次数=0`：**【确定值】**。
- `tryOptimisticRead()` 返回 `0` 表示获取失败：**【确定值】**（stamp 为 0 是「拿不到 stamp」的约定）。
- `第 2 次` 读到 `42`、`validate=true`、`重试次数=1`：**【确定值】**。

**子实验 3（不可重入，重点）**：

```text
=== 实验3: 不可重入 —— 同线程重复获取写锁会自锁 ===
  期望: 同一线程第二次 tryWriteLock() 失败 -> 实际: 返回 0 (0 = 失败, 因为写锁不可重入)
  期望: 持有写锁时 tryOptimisticRead 失败 -> 实际: 返回 0 (0 = 失败)
  [自锁线程] 已持有写锁, 再次调用 writeLock() ...
  期望: 同线程重复 writeLock() 永久阻塞 -> 实际: 线程仍存活 = true (true = 卡在自锁, 这就是"不可重入"的代价)
```

- 全部：**【确定值】**。注意 `tryWriteLock()` 返回 `0` 是**失败**而不是抛异常；重复 `writeLock()` 是**永久阻塞**。

**子实验 4（锁转换）**：

```text
=== 实验4: 锁转换 —— 写锁降级为读锁 / 乐观读升级为写锁 ===
  降级: tryConvertToReadLock 返回 513, 降级后 isWriteLocked=false, isReadLocked=true
  期望: 降级后仍读到 100(自己写的值, 不丢写) -> 实际: 100
  升级: tryConvertToWriteLock 成功, 持写锁把值加到 101
  对照(读写锁): 读锁升级写锁 -> 实际: 失败(不支持)
```

- `降级后 isWriteLocked=false, isReadLocked=true`、读到 `100`、升级成功加到 `101`、读写锁对照「失败(不支持)」：**【确定值】**。
- `tryConvertToReadLock 返回 513`：**【机器相关值】**（stamp 编码状态，数值随上下文变化）。

**子实验 5（吞吐对比）**：

```text
=== 实验5: 吞吐对比 —— 读多写少场景 ===
  锁实现                        耗时(ms)    吞吐(ops/s)
  synchronized                  33.9        23629692
  ReentrantReadWriteLock        50.4        15862930
  StampedLock(乐观读)           26.1        30687102
  期望: 读多写少时 StampedLock 吞吐最高 -> 实际: StampedLock 不低于读写锁
```

- **本次实测**：`synchronized` 33.9 ms、`ReentrantReadWriteLock` 50.4 ms、`StampedLock` 26.1 ms。
- 三个耗时 / 吞吐：**【机器相关值】**，换机器会变（实测出现过 `synchronized` 与 `StampedLock` 几乎持平的情况）。
- `实际: StampedLock 不低于读写锁`：**【确定值】**（代码比较的是 `stampedOps >= rwOps`，不满足时会打印「本次运行 StampedLock 反而更低」）。
- **本实验只做横向比较，不要当绝对指标。**

### 验证点

1. 子实验 1 的 `isReadLocked=false, isWriteLocked=false` 证明乐观读**真的不占锁**；两次乐观读拿到**同一个 stamp** 也印证了 stamp 不是锁、不会互斥。
2. 子实验 2 的 `validate=false` → 退化重读是**必须**的，不是可选优化；写锁持有期间 `tryOptimisticRead()` 连 stamp 都拿不到（返回 0）。
3. 子实验 3 是 `StampedLock` 最硬的约束：**不可重入**。同线程二次 `writeLock()` 把自己挂起，且因为没有 owner 字段，线程栈里看不出是哪把锁，排查成本高。
4. 子实验 4 说明 `StampedLock` **两个方向都支持转换**：写→读降级（不丢写）、乐观读→写升级（无窗口）。
5. 子实验 5 的吞吐差异主要来自**读路径的进入成本**（乐观读不进临界区）。

### 面试追问

- **乐观读原理？和悲观读的区别？**（LK33）——乐观读**不加任何锁**，只取一个版本戳（stamp）并直接读数据，读完用 `validate(stamp)` 校验期间是否发生过写；悲观读是共享锁，保证最新值。
- **`validate` 失败怎么办？**（LK34）——退化为悲观读重取数据，并**用同一个 stamp** 解锁。`validate` 失败是**正常路径**而非异常；失败率高说明写竞争激烈，乐观读收益已被退化开销吃掉。
- **为什么不可重入？**（LK35）——`StampedLock` 用「版本戳 + 计数」管理状态，**不记录哪个线程持有**，因此无法识别重入；二次获取就把自己挂起。
- **降级 / 升级分别是什么？**（LK35）——降级是写锁 → 读锁；升级是 `tryConvertToWriteLock` 在**不释放读锁**的前提下原地把读锁转成写锁；失败时只能「释放读锁 → 取写锁 → 重取读锁 → 重校验 stamp」，期间存在 ABA 风险。
- **什么时候用 `StampedLock` 而不是 `ReentrantReadWriteLock`？**（LK36）——读极多写极少、读极短、写以「整体替换对象」为主、延迟敏感；需要可重入 / `Condition` / `Lock` 接口时必须用读写锁。
- **常见错误？**——只 `tryOptimisticRead` 不 `validate`；解锁时传错 stamp（抛 `IllegalMonitorStateException`）；在写频繁的场景强行上 `StampedLock`（比 `synchronized` 还慢）。

---

## Lab 7 · 锁选型基准

**代码**：`src/main/java/edu/zjnu/interview/lock/q07/L07LockChoiceBench.java`

**目标**：在同一临界区、同一负载下横向对比四种锁；给出可落地的选型顺序。

### 步骤

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home
mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q07.L07LockChoiceBench
```

一次运行包含 3 个子实验：

| 子实验 | 负载 | 规模 |
|---|---|---|
| 1 | 读多写少（90% 读 / 10% 写） | 4 线程 × 100000 次 |
| 2 | 读写均衡（50% 读 / 50% 写） | 4 线程 × 100000 次 |
| 3 | 选型对照表 + `synchronized` 的三个优势（含 jstack 可读性对比） | — |

四种锁跑的是**同一个临界区**（读一个值 / 加一），且每次正式计时前都做了一次 `iterations / 10` 的**预热**让 JIT 先编译掉热点。

### 预期输出

**子实验 1**：

```text
=== 实验1: 读多写少 (90% 读 / 10% 写), 4 线程 x 100000 次 ===
  锁实现                          耗时(ms)      吞吐(ops/s)         计数校验
  synchronized                   31.1       12848739  PASS(40000)
  ReentrantLock                  21.8       18310162  PASS(40000)
  ReentrantReadWriteLock         33.1       12079589  PASS(40000)
  StampedLock                    11.1       35974594  PASS(40000)
```

- `计数校验` 列：**【确定值】**——`PASS(40000)` = 4 线程 × 100000 次 × 10%（每 10 次写 1 次）。若出现 `FAIL(x!=40000)`，说明该锁的实现有问题。
- 耗时 / 吞吐：**【机器相关值】**。

**同一环境下的两次运行**（证明数值不稳定、趋势稳定）：

| 运行 | synchronized | ReentrantLock | ReentrantReadWriteLock | StampedLock |
|---|---|---|---|---|
| 第 1 次 | 34.5 ms | 17.9 ms | 31.1 ms | 6.8 ms |
| 第 2 次 | 31.1 ms | 21.8 ms | 33.1 ms | 11.1 ms |

- **排序基本稳定**（`StampedLock` 最快、`ReentrantReadWriteLock` 偏慢），**数值明显波动**。这正是本节开头那条警告的实证。

**子实验 2**：

```text
=== 实验2: 读写均衡 (50% 读 / 50% 写), 4 线程 x 100000 次 ===
  锁实现                          耗时(ms)      吞吐(ops/s)         计数校验
  synchronized                   55.1        7254722 PASS(200000)
  ReentrantLock                   7.9       50944595 PASS(200000)
  ReentrantReadWriteLock         21.5       18596794 PASS(200000)
  StampedLock                     4.9       81625023 PASS(200000)
```

- `计数校验`：**【确定值】**——`PASS(200000)` = 4 线程 × 100000 次 × 50%。
- 耗时 / 吞吐：**【机器相关值】**。

**子实验 3（对照表）**：固定文本，7 个维度 × 4 种锁；紧接着是 `synchronized` 的三个优势，其中第 3 条用 jcmd 做了可读性对比：

```text
     [线程 A] 等 synchronized:
       "wait-on-synchronized" #81 daemon prio=5 os_prio=31 cpu=0.17ms elapsed=5.40s tid=0x0000000125d77c00 nid=0x8f43
       - waiting to lock <0x000000070bbf7498> (a java.lang.Object)
     [线程 B] 等 ReentrantLock:
       "wait-on-reentrantlock" #82 daemon prio=5 os_prio=31 cpu=0.03ms elapsed=5.40s tid=0x0000000125ffa200 nid=0x934
       - parking to wait for  <0x000000070bbf74b8> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)
```

- **两种等待描述的差异是确定的**：`synchronized` 是 `waiting to lock <对象> (a java.lang.Object)`，`ReentrantLock` 是 `parking to wait for <对象> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)`。
- 线程名、`tid`、`nid`、`cpu`、`elapsed`、对象地址：**【机器相关值】**。

### 验证点

1. 四行 `计数校验` 全部 `PASS`——说明四种实现**语义正确**，差异只在性能。
2. 读多写少场景：**趋势**是 `StampedLock` 最高、`ReentrantReadWriteLock` 可能反而**慢于** `synchronized`。原因是本实验临界区极短（纳秒级），读锁的 AQS 进入开销盖过了读并发带来的好处。
3. 读写均衡场景：写占比升高后差距显著收窄——**选型不能只看「读多写少」**。
4. jstack 可读性：`synchronized` 的等待描述指向 `java.lang.Object`（看不出业务对象是谁），`ReentrantLock` 指向具体的 `ReentrantLock$NonfairSync`。**这是 `synchronized` 排查友好度更高的具体证据**。

> ⚠️ **这些数字只能横向比较，不要当绝对指标。** 它们受核数、CPU 型号、JIT 预热、后台负载、临界区长度影响极大。真实选型要用**自己业务的临界区**复现一遍（把 `Counter` 换成实际逻辑），而不是照抄本实验的排名。

### 面试追问

- **完整的锁选型表？**（LK40）

  | 需求 | 首选 |
  |---|---|
  | 普通互斥、简单临界区 | `synchronized` |
  | 超时 / 可中断 / 公平 / `Condition` | `ReentrantLock` |
  | 高并发计数、只增统计 | `LongAdder` |
  | 单变量原子更新 | `AtomicInteger` / `AtomicReference` |
  | 读多写少 | `ReentrantReadWriteLock` |
  | 读极多写极少、读极短 | `StampedLock`（能处理校验失败退化） |
  | 任务编排 | `CountDownLatch` / `CyclicBarrier` / `Semaphore` |

- **为什么默认用 `synchronized`？**——JVM 内建优化、语法自动释放（不会忘记 `finally`）、jstack 可读性好。选型应从最简单、最容易被 JIT 优化的方案开始。
- **临界区里能做什么？**——不做 IO、远程调用、日志落盘、外部回调。临界区越长，并发度越低、锁升级与等待的概率越高。
- **线上排查锁问题的路径？**（LK40）——**先分类现象**（吞吐正常但请求挂住 → 死锁或长等待；CPU 高 → 自旋或 CAS 重试风暴；数据错乱 → 可见性/原子性问题）→ **抓栈**（`jstack -l` / `jcmd <pid> Thread.print -l`）→ **看热点**（JFR 的 `jdk.JavaMonitorEnter`、`jdk.ThreadPark`；async-profiler 锁模式）→ **检测告警**（`ThreadMXBean.findDeadlockedThreads()`）。

---

## 附录 · 常见坑速查表

按「跑实验时最容易踩的顺序」排列。

| # | 现象 | 原因 | 正确表述 / 处理 |
|---|---|---|---|
| 1 | 普通字段可见性实验（Lab 1 子实验 2）**可能永远看不到**写线程的写入 | 普通字段无可见性保证；JIT 可把循环内的读提升（hoist）到循环外，`Thread.onSpinWait()` 不带内存屏障挡不住 | 这是「**不保证**可见」，不是「**必然**看不到」。另一支结果（碰巧看到）同样正常。两种输出代码都会打印解释，都不算失败 |
| 2 | 同上，但读者看到 `= true` 以为「普通变量也可见」 | 碰巧可见 | 不能作为正确性依据。JMM 不给任何承诺，不同 JVM 版本 / JIT 编译时机结果可能不同 |
| 3 | `volatile int i; i++` 在 10 线程下丢数 | `i++` 是读-改-写三步；volatile 只保证单次读/写，不保证复合操作原子 | 用 `AtomicInteger` / `LongAdder` / 加锁。丢失的**幅度**是概率，**丢失本身**是必然 |
| 4 | DCL 非 volatile 版**没复现**多实例，以为「不加 volatile 也行」 | 是否复现取决于 CPU、内存布局、JIT 编译时机等偶然因素 | JMM 不给任何保证。工程上用静态内部类 Holder 或枚举，不要依赖运气 |
| 5 | 读写锁**读锁取写锁**：`tryLock` 返回 `false`、`lock()` **永久阻塞**，与「抛 `IllegalMonitorStateException`」的说法不符 | JDK 17 实测行为：写锁与读锁对同一线程互斥，读锁不释放就永远拿不到写锁 | `IllegalMonitorStateException` 是「**未持锁却 unlock**」才抛。正确做法：释放读锁再取写锁（中间有窗口），或 `tryLock` + 重试 |
| 6 | `StampedLock` 同线程二次 `writeLock()` **把自己挂起** | `StampedLock` 用「版本戳 + 计数」管理状态，**不记录 owner**，无法识别重入 | 不可重入是硬约束。递归路径上重复加锁 → 自死锁，且线程栈里看不出是哪把锁，排查成本高。需要可重入 / `Condition` 就用 `ReentrantReadWriteLock` |
| 7 | `StampedLock` 乐观读读到**旧值** | 乐观读不加锁，允许读到旧快照 | 读到旧值是正常现象；关键是 `validate(stamp)` 校验失败后**必须**退化为悲观读重取，并**用同一个 stamp** 解锁 |
| 8 | `jcmd` 抓死锁现场报「找不到进程 / attach 被拒绝」 | ① pid 拿错；② 跨用户 / 容器内 attach 受限；③ `jcmd` 不在 `JAVA_HOME/bin` | 拿 pid 三选一：`jps -l \| grep L04DeadlockLab`、实验输出里的「当前进程 pid = NNNNN」、`pgrep -f L04DeadlockLab`。attach 不上就换 `jstack -l <pid>`，再不行用 `kill -3 <pid>`（SIGQUIT，不终止进程） |
| 9 | `jcmd` / `jstack` 完全不可用，Lab 4 子实验 3 打印「本步跳过」 | JDK 的 `bin` 下缺这两个工具 | 不影响其他子实验。手动装 JDK 或用 `jstack` 替代 |
| 10 | 死锁实验跑完 JVM 退不出去 / 命令挂住 | 死锁线程是非守护线程，或 `main` 里 `join` 了它 | 实验里已把死锁线程设为守护线程且不 `join`；自己改代码时保持这个约定 |
| 11 | 基准实验（L06 / L07）**每次跑数值差很多** | 耗时受核数、CPU 型号、JIT 预热、后台负载影响；短临界区对调度极敏感 | 只看**相对趋势**与**计数校验**列。真实选型要用自己业务的临界区复现 |
| 12 | 读写锁在 Lab 7 里**比 `synchronized` 还慢** | 临界区极短（纳秒级）时，读锁的 AQS 进入开销盖过了读并发的好处 | 这是正常且正确的观测。只有当读逻辑本身较重、或线程数远大于核数时，读并发才转化成吞吐优势 |
| 13 | 「用 `jstack` 看到大量 `BLOCKED` 就断言死锁」 | `BLOCKED` 只表示在等锁 | 死锁的判定依据是**环**：JVM 会明确输出 `Found one Java-level deadlock` 并列出参与线程；饥饿没有任何 JVM 级标记 |
| 14 | `findMonitorDeadlockedThreads()` 查不出 `ReentrantLock` 死锁 | 该方法只覆盖 `ObjectMonitor`（`synchronized`） | 用 `findDeadlockedThreads()`，它同时覆盖 monitor 与 `OwnableSynchronizer`（含 `ReentrantLock`） |
| 15 | 换到 JDK 21 后 `UseBiasedLocking` 报 `Unrecognized VM option` | 偏向锁 JDK 15 默认禁用并废弃（JEP 374）、JDK 18 移除实现（JDK-8256425）、JDK 19+ 参数直接不认 | 现代 JDK 的锁升级路径是「无锁 → 轻量级 → 重量级」，**没有偏向阶段**。「无锁 → 偏向 → 轻量级 → 重量级」是 JDK 8 特有的历史机制 |
| 16 | 背 JDK 8 的 AQS 字段名，说 `Node.waitStatus` / `SIGNAL` / `PROPAGATE` | JDK 17 已改名并换成位图 | JDK 17：`Node.status`（`volatile int`），常量 `WAITING=1`、`COND=2`、`CANCELLED=0x80000000`。**没有** `SIGNAL`/`CONDITION`/`PROPAGATE`。JDK 17 的唤醒是私有的 `signalNext(Node)`，`unparkSuccessor` 已不存在 |
| 17 | 用 `unparkSuccessor(head)` 手写 AQS 子类，在 JDK 17 编译不过 | 它是 JDK 8/11 的 `private` 方法，子类不可调用 | JDK 17 用 `release()` 内部的私有 `signalNext(head)`；子类只实现 `tryAcquire`/`tryRelease` |

## 附录 · 实验记录模板

每个实验建议记录：

```text
实验：
时间：
JDK 版本（java -version）：
CPU 核数：
执行命令：
关键预期（确定值）：
实际输出：
确定值是否全部一致：
机器相关值（本次实测）：
结论：
对应面试题：
面试追问：
```

> 记录「预期 vs 实际」的差异，比只抄命令更有价值。
> 判断实验是否通过的口径：**所有 `[确定值]` 一致 + 所有 `[PASS]` 出现**；机器相关值只记录不判定。
