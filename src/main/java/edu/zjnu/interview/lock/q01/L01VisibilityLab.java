package edu.zjnu.interview.lock.q01;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 锁专题 Lab 1：可见性、有序性、volatile 的语义边界，以及 happens-before 规则。
 *
 * <p>运行命令（默认 java 是 JDK 8，必须显式指定 JDK 17）：
 * <pre>
 * export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home
 * mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q01.L01VisibilityLab
 * </pre>
 *
 * <p>验证面试点：
 * <ol>
 *   <li>volatile 保证可见性：写线程对 volatile 字段的写入对读线程可见；</li>
 *   <li>volatile 写之前发生的普通字段写入，随该 volatile 写一并对读线程可见（happens-before 的推论）；</li>
 *   <li>普通字段不保证可见性——是「不保证」，不是「必然看不到」；</li>
 *   <li>volatile 不保证复合操作（i++）的原子性；</li>
 *   <li>volatile 三条语义（可见性 / 禁止重排 / 不保证原子性）各自的边界；</li>
 *   <li>DCL 单例必须给静态实例字段加 volatile 的原因；</li>
 *   <li>happens-before 的八条规则（含传递性）。</li>
 * </ol>
 */
public class L01VisibilityLab {

    public static void main(String[] args) throws Exception {
        step1_volatileVisibility();
        step2_nonVolatileVisibility();
        step3_volatileAtomicity();
        step4_volatileSemantics();
        step5_dclSingleton();
        step6_happensBefore();
    }

    // ------------------------------------------------------------------
    // 实验 1：volatile 的可见性
    // ------------------------------------------------------------------

    /** 写线程与读线程共享的数据：payload 是普通字段，ready 是 volatile 字段。 */
    private static final class VolatileSignal {
        int payload;               // 普通字段
        volatile boolean ready;    // volatile 字段
    }

    private static void step1_volatileVisibility() throws InterruptedException {
        System.out.println("=== 实验1: volatile 写对读线程可见，且 volatile 写之前的普通字段写入也随之可见 ===");

        VolatileSignal data = new VolatileSignal();
        CountDownLatch readerFinished = new CountDownLatch(1);
        long[] observed = new long[1];
        long[] spins = new long[1];

        Thread reader = new Thread(() -> {
            long n = 0;
            while (!data.ready) {          // volatile 读：读到 true 即与写线程的 volatile 写 synchronizes-with
                n++;
                Thread.onSpinWait();
            }
            spins[0] = n;
            observed[0] = data.payload;    // volatile 读之后的普通字段读
            readerFinished.countDown();
        }, "reader-volatile");
        reader.start();

        Thread.sleep(50);                  // 让读线程先进入自旋
        long t0 = System.nanoTime();
        data.payload = 42;                // 普通写
        data.ready = true;                // volatile 写
        boolean finished = readerFinished.await(5, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        System.out.println("读线程自旋次数 = " + spins[0] + "，等待耗时 = " + elapsedMs + " ms，是否退出自旋 = " + finished);
        System.out.println("读线程读到的 payload = " + observed[0] + "（期望 42）");
        check("volatile 写之前的普通字段写入也可见", 42L, observed[0]);
        check("读线程最终退出自旋（volatile 写一定可见）", Boolean.TRUE, finished);

        System.out.println(">> 面试点: volatile 写与后续的 volatile 读构成 happens-before；读线程一旦读到 true，"
                + "就能保证看到写线程在这次 volatile 写之前的所有写入（含普通字段 payload）。");
    }

    // ------------------------------------------------------------------
    // 实验 2：普通字段不保证可见性（对照组）
    // ------------------------------------------------------------------

    /** 与 VolatileSignal 结构相同，唯一的差别是 ready 是普通字段。 */
    private static final class PlainSignal {
        int payload;
        boolean ready;                     // 普通字段，没有 volatile
    }

    /** 普通字段读线程的自旋预算上限，防止无限自旋把实验挂死。 */
    private static final long SPIN_BUDGET = 200_000_000L;

    /** 传递性演示用的共享数据：payload 是普通字段，v1 / v2 是 volatile 字段。 */
    private static final class HbChain {
        int payload;                    // 普通字段
        volatile boolean v1;            // volatile 字段
        volatile boolean v2;            // volatile 字段
    }

    private static void step2_nonVolatileVisibility() throws InterruptedException {
        System.out.println("=== 实验2: 普通字段没有可见性保证——JMM「不保证可见」，而不是「必然看不到」 ===");

        PlainSignal data = new PlainSignal();
        long[] observed = new long[]{-1L};
        long[] spins = new long[1];

        Thread reader = new Thread(() -> {
            long n = 0;
            boolean sawInLoop = false;
            // 固定的循环预算。循环体内只有 Thread.onSpinWait()（intrinsic，无内存屏障），
            // JIT 因此允许把这个普通字段的读提升（hoist）到循环外，
            // 此时读线程即使一直在自旋，也可能永远看不到写线程的写入——这正是 JMM 允许的行为。
            while (n < SPIN_BUDGET) {
                if (data.ready) {          // 普通读，没有可见性保证
                    sawInLoop = true;
                    break;
                }
                n++;
                Thread.onSpinWait();
            }
            spins[0] = n;
            observed[0] = sawInLoop ? 1L : 0L;
        }, "reader-plain");
        reader.start();

        Thread.sleep(50);
        data.payload = 42;
        data.ready = true;                 // 普通写
        reader.join(10_000);

        boolean saw = observed[0] == 1L;
        System.out.println("读线程自旋次数 = " + spins[0] + "（预算 " + SPIN_BUDGET + "），"
                + "自旋期间是否观察到写入 = " + saw);
        if (saw) {
            System.out.println("本次读线程在自旋期间读到了写线程的写入。注意：这是「碰巧可见」，JMM 并不承诺这一点，"
                    + "不能作为正确性依据（不同 JVM 版本、不同 JIT 编译时机结果可能不同）。");
        } else {
            System.out.println("本次读线程自旋 " + SPIN_BUDGET + " 次始终没有读到写线程的写入（循环预算耗尽也没退出）。"
                    + "这属于 JMM 允许的行为：普通字段没有可见性保证，"
                    + "编译器/JIT 可以把这个读提升到循环外而不再重新读取。");
        }
        System.out.println(">> 面试点: 正确表述是「volatile 保证可见，普通变量不保证可见」；"
                + "绝对不要说成「普通变量一定看不到另一个线程的修改」——那不成立。");
    }

    // ------------------------------------------------------------------
    // 实验 3：volatile 不保证原子性
    // ------------------------------------------------------------------

    private static volatile int volatileCounter = 0;          // volatile，但 i++ 仍是读-改-写
    private static final AtomicInteger ATOMIC_COUNTER = new AtomicInteger(0);

    private static void step3_volatileAtomicity() throws InterruptedException {
        System.out.println("=== 实验3: volatile 不保证 i++ 的原子性，10 线程并发自增会丢数 ===");

        final int threads = 10;
        final int loops = 100_000;
        final int expected = threads * loops;

        volatileCounter = 0;
        ATOMIC_COUNTER.set(0);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < loops; i++) {
                        volatileCounter++;                 // 读 → 加一 → 写，三步，非原子
                        ATOMIC_COUNTER.incrementAndGet();  // CAS 循环，原子
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "counter-" + t).start();
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);

        System.out.println("volatile int 实际值 = " + volatileCounter + "（期望 " + expected + "）");
        System.out.println("AtomicInteger 实际值 = " + ATOMIC_COUNTER.get() + "（期望 " + expected + "）");
        check("volatile int 出现丢数", Boolean.TRUE, volatileCounter < expected);
        check("AtomicInteger 无丢数", expected, ATOMIC_COUNTER.get());
        System.out.println("丢失的更新次数 = " + (expected - volatileCounter));

        System.out.println(">> 面试点: volatile 只保证「单次读/写」的可见性与有序性，"
                + "不保证读-改-写复合操作的原子性；i++ 需要 AtomicInteger 或加锁。");
    }

    // ------------------------------------------------------------------
    // 实验 4：volatile 三条语义的边界
    // ------------------------------------------------------------------

    private static void step4_volatileSemantics() {
        System.out.println("=== 实验4: volatile 的三条语义——可见性、禁止重排、不保证原子性，各有明确边界 ===");

        System.out.println("1) 可见性：写线程对 volatile 字段的写入，保证对「之后读到该写入」的读线程可见。");
        System.out.println("   边界：只对该 volatile 变量以及它之前发生的写入生效；不保证复合操作原子；不提供「写-写」「读-读」的额外约束之外的东西。");
        System.out.println("2) 禁止重排：volatile 写之前的操作不会被重排到该 volatile 写之后；volatile 读之后的操作不会被重排到该 volatile 读之前。");
        System.out.println("   边界：约束的是「本线程内相对于该 volatile 访问的程序顺序」，硬件层的重排由 CPU 内存屏障（StoreStore/LoadLoad/StoreLoad）阻断，"
                + "而不是靠 volatile 关键字本身「禁止 CPU 重排」。");
        System.out.println("3) 不保证原子性：i++、x = y + 1 这类读-改-写复合操作在 volatile 字段上依然会丢数（见实验 3）。");
        System.out.println("   边界：单个 volatile 字段的单次读或单次写是原子的；把多个变量组合成一次不变式更新时必须换手段（锁 / Atomic 类 / VarHandle CAS）。");
        System.out.println("补充：final 字段还有「初始化安全保证」（构造器结束前的写入对通过该引用读到的线程可见），"
                + "这是 final 字段的额外语义，不是 volatile 的语义。");

        System.out.println(">> 面试点: volatile 的三条语义要成对回答——「保证了什么」和「没保证什么」一起说，"
                + "才算答完整；只背「volatile 保证可见性和有序性」会被追问 i++ 打穿。");
    }

    // ------------------------------------------------------------------
    // 实验 5：DCL 单例
    // ------------------------------------------------------------------

    /** 正确的 DCL：静态实例字段必须是 volatile。 */
    private static final class SafeDcl {
        private static volatile SafeDcl instance;   // volatile 必需
        private final int value;

        private SafeDcl() {
            this.value = 42;
        }

        static SafeDcl getInstance() {
            SafeDcl r = instance;                   // 第一次判空读的是 volatile 读
            if (r == null) {
                synchronized (SafeDcl.class) {
                    r = instance;                   // 第二次判空在锁内，避免重复创建
                    if (r == null) {
                        instance = r = new SafeDcl();
                    }
                }
            }
            return r;
        }
    }

    /** 去掉 volatile 的 DCL：仅用于演示，不建议在真实代码中使用。 */
    private static final class UnsafeDcl {
        private static UnsafeDcl instance;          // 缺少 volatile
        private final byte[] payload;

        private UnsafeDcl() {
            // 拉长构造时间，扩大「对象还没构造完就被别的线程看到」的窗口
            this.payload = new byte[1 << 20];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) i;
            }
        }

        static UnsafeDcl getInstance() {
            if (instance == null) {
                synchronized (UnsafeDcl.class) {
                    if (instance == null) {
                        instance = new UnsafeDcl();
                    }
                }
            }
            return instance;
        }
    }

    private static void step5_dclSingleton() throws InterruptedException {
        System.out.println("=== 实验5: DCL 单例必须给静态实例字段加 volatile，否则会读到「半构造对象」 ===");

        System.out.println("对象分配的三个步骤（编译器可重排 2 和 3）：");
        System.out.println("  1) 分配内存空间；2) 初始化对象（赋初值，把实例字段写进去）；3) 把引用指向这块内存。");
        System.out.println("  volatile 的作用：禁止步骤 3 被重排到步骤 2 之前。没有 volatile 时，"
                + "别的线程可能看到 instance != null 但实例字段仍是默认值——这就是「半构造对象」。");

        final int threads = 16;
        java.util.Set<Integer> safeIds = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        java.util.Set<Integer> unsafeIds = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads * 2);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    safeIds.add(System.identityHashCode(SafeDcl.getInstance()));
                    unsafeIds.add(System.identityHashCode(UnsafeDcl.getInstance()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "dcl-" + i).start();
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);

        System.out.println("volatile 版：16 个线程共拿到 " + safeIds.size() + " 个不同实例（期望 1）");
        System.out.println("非 volatile 版：16 个线程共拿到 " + unsafeIds.size() + " 个不同实例（期望 1，本次观测值仅供参考）");
        check("volatile 版 DCL 只创建一个实例", 1, safeIds.size());
        if (unsafeIds.size() > 1) {
            System.out.println("非 volatile 版本次复现了多实例（这是「允许发生」而不是「必然发生」）。");
        } else {
            System.out.println("非 volatile 版本次没有复现多实例——这不代表它是安全的："
                    + "JMM 不给它任何保证，是否复现取决于 CPU、内存布局、JIT 编译时机等偶然因素。");
        }

        System.out.println(">> 面试点: DCL 的两次判空分别防的是「重复创建」和「并发创建」；"
                + "加锁只解决了后者的可见性顺序问题，volatile 才是解决「重排导致半构造对象」的那一步。");
    }

    // ------------------------------------------------------------------
    // 实验 6：happens-before 的八条规则
    // ------------------------------------------------------------------

    private static void step6_happensBefore() throws InterruptedException {
        System.out.println("=== 实验6: happens-before 的八条规则 ===");

        System.out.println("1) 程序顺序规则：同一线程内，前面的操作 happens-before 后面的操作；");
        System.out.println("2) volatile 规则：对 volatile 字段的写 happens-before 后续对它的读；");
        System.out.println("3) 锁规则：解锁 happens-before 后续对同一把锁的加锁（监视器 lock/unlock）；");
        System.out.println("4) 线程启动规则：Thread.start() happens-before 该线程体的第一个操作；");
        System.out.println("5) 线程终止规则：线程中的所有操作 happens-before 另一个线程对该线程 join() 成功返回；");
        System.out.println("6) 线程中断规则：对线程 interrupt() happens-before 该线程检测到中断事件（Thread.interrupted() 返回 true）；");
        System.out.println("7) 终结器规则：对象构造器（及之前所有写入）的完成 happens-before 终结器线程的启动；");
        System.out.println("   （JDK 17 中 Object.finalize() 已被废弃，本实验只陈述规则，不实机演示。）");
        System.out.println("8) 传递性规则：若 A happens-before B，且 B happens-before C，则 A happens-before C；"
                + "happens-before 关系是可传递的。");

        // 规则 2：volatile 写 happens-before 后续 volatile 读。
        // 读线程必须真的做一次读到 true 的 volatile 读，才能与写线程的 volatile 写建立 synchronizes-with 关系。
        final int[] rule2 = new int[1];
        final VolatileSignal sig = new VolatileSignal();
        Thread volatileWriter = new Thread(() -> {
            sig.payload = 7;                 // 普通写
            sig.ready = true;                // volatile 写
        }, "volatile-writer");
        Thread volatileReader = new Thread(() -> {
            while (!sig.ready) {             // volatile 读，读到 true 即与上面的 volatile 写建立 happens-before
                Thread.onSpinWait();
            }
            rule2[0] = sig.payload;          // 因此这个普通字段的读必然看到 7
        }, "volatile-reader");
        volatileWriter.start();
        volatileReader.start();
        volatileWriter.join();
        volatileReader.join();
        check("规则2 volatile 写 happens-before 后续读（读到 payload=7）", 7, rule2[0]);

        // 规则 3：锁规则。数据的读写都在同一把监视器内完成，读写路径上没有任何 volatile 或 latch，排除其它规则的干扰
        final Object lock = new Object();
        final int[] lockData = new int[1];    // 受监视器保护的普通数据
        final int[] lockFlag = new int[1];    // 受监视器保护的就绪标志
        final int[] lockRead = new int[1];    // 读线程读到的值
        Thread writer = new Thread(() -> {
            synchronized (lock) {
                lockData[0] = 99;            // 在监视器内写普通字段
                lockFlag[0] = 1;              // 在监视器内写标志位
                lock.notifyAll();
            }
        }, "lock-writer");
        Thread reader = new Thread(() -> {
            synchronized (lock) {
                while (lockFlag[0] == 0) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                lockRead[0] = lockData[0];    // 读同一监视器内的普通字段
            }
        }, "lock-reader");
        reader.start();
        writer.start();
        reader.join();
        writer.join();
        check("规则3 解锁 happens-before 后续加锁（读到 99）", 99, lockRead[0]);

        // 规则 4：线程启动规则
        final int[] rule4 = new int[1];
        final int[] payload4 = new int[1];
        payload4[0] = 11;                    // start 之前写
        Thread t4 = new Thread(() -> { rule4[0] = payload4[0]; });
        t4.start();
        t4.join();
        check("规则4 start() happens-before 线程体首个操作（读到 11）", 11, rule4[0]);

        // 规则 5：线程终止规则
        final int[] rule5 = new int[1];
        Thread t5 = new Thread(() -> { rule5[0] = 33; });
        t5.start();
        t5.join();
        check("规则5 join() 返回 happens-before 主线程读（读到 33）", 33, rule5[0]);

        // 规则 6：线程中断规则
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread t6 = new Thread(() -> {
            while (!Thread.interrupted()) {
                Thread.onSpinWait();
            }
            interrupted.set(true);
        }, "interrupt-waiter");
        t6.start();
        Thread.sleep(50);
        t6.interrupt();
        t6.join(5000);
        check("规则6 interrupt() happens-before 中断检测（检测到中断）", Boolean.TRUE, interrupted.get());

        // 规则 8：传递性。三个线程串成一条链：tA --v1--> tB --v2--> tC
        // tA 写普通字段 payload 并 volatile 写 v1；tB volatile 读 v1 后 volatile 写 v2；
        // tC volatile 读 v2 后读普通字段 payload —— 由传递性，tC 必须看到 payload。
        final HbChain chain = new HbChain();
        final int[] rule8 = new int[1];
        Thread t8a = new Thread(() -> {
            chain.payload = 7;             // 普通写
            chain.v1 = true;               // volatile 写
        }, "hb-chain-a");
        Thread t8b = new Thread(() -> {
            while (!chain.v1) {            // volatile 读
                Thread.onSpinWait();
            }
            chain.v2 = true;               // volatile 写
        }, "hb-chain-b");
        Thread t8c = new Thread(() -> {
            while (!chain.v2) {            // volatile 读
                Thread.onSpinWait();
            }
            rule8[0] = chain.payload;      // 普通读，靠传递性保证可见
        }, "hb-chain-c");
        t8a.start();
        t8b.start();
        t8c.start();
        t8a.join();
        t8b.join();
        t8c.join();
        check("规则8 传递性（tA 的普通写经 tB 中转后对 tC 可见）", 7, rule8[0]);

        System.out.println(">> 面试点: happens-before 是 JMM 的核心概念——「可见性」只是它的推论之一，"
                + "「有序性」是另一个推论；八条规则里传递性最容易被漏答，"
                + "它决定了「A 写、B 中转、C 读」这种链条上 C 能不能看到 A 的写入；"
                + "回答 volatile/锁/单例/线程池问题时，落到 happens-before 上才算到底。");
    }

    // ------------------------------------------------------------------
    // 自检工具
    // ------------------------------------------------------------------

    /** 打印「期望 vs 实际」并给出 PASS/FAIL，不抛异常，避免偶发差异导致实验中断。 */
    private static void check(String label, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + label
                + " | 期望 = " + expected + " | 实际 = " + actual);
    }
}
