package edu.zjnu.interview.lock.q02;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * 锁专题 Lab 2：CAS、原子类与 ABA 问题。
 *
 * <p>运行命令（默认 java 是 JDK 8，必须显式指定 JDK 17）：
 * <pre>
 * export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home
 * mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q02.L02CasAtomicLab
 * </pre>
 *
 * <p>验证面试点：
 * <ol>
 *   <li>手写 CAS 自旋自增器与 AtomicInteger 的等价性，以及性能差异（ns/op）；</li>
 *   <li>ABA 问题：值 A→B→A 之后 CAS 仍然成功造成的误判，以及 AtomicStampedReference 的修复；</li>
 *   <li>LongAdder 与 AtomicLong 在单线程 / 多线程下的性能差异；</li>
 *   <li>AtomicIntegerFieldUpdater 与 LongAdder 的 Cell 槽位机制（反射观察 Striped64.base 与 cells）。</li>
 * </ol>
 */
public class L02CasAtomicLab {

    public static void main(String[] args) throws Exception {
        step1_casSpinVsAtomic();
        step2_abaProblem();
        step3_longAdderVsAtomicLong();
        step4_fieldUpdaterAndCells();
    }

    // ------------------------------------------------------------------
    // 实验 1：手写 CAS 自旋 vs AtomicInteger
    // ------------------------------------------------------------------

    /**
     * 手写 CAS 自增器：底层用 VarHandle 做 compareAndSet（JDK 9+ 提供的标准 CAS 原语）。
     * 语义与 AtomicInteger.incrementAndGet() 等价：读当前值 → 计算新值 → CAS 写回，失败则重试。
     */
    static final class CasCounter {
        private volatile int value;                 // 普通 volatile 字段即可，CAS 负责原子性
        private static final VarHandle VALUE;

        static {
            try {
                VALUE = MethodHandles.lookup().findVarHandle(CasCounter.class, "value", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        int get() {
            return (int) VALUE.getVolatile(this);
        }

        /** CAS 自旋：读 → 改 → CAS，失败则重试。 */
        void increment() {
            for (;;) {
                int current = (int) VALUE.getVolatile(this);
                if (VALUE.compareAndSet(this, current, current + 1)) {
                    return;
                }
                Thread.onSpinWait();
            }
        }
    }

    private static void step1_casSpinVsAtomic() throws InterruptedException {
        System.out.println("=== 实验1: 手写 CAS 自旋与 AtomicInteger 等价，但多线程下重试次数更多、性能更低 ===");

        final int threads = 8;
        final int loops = 200_000;
        final int expected = threads * loops;

        CasCounter cas = new CasCounter();
        AtomicInteger atomic = new AtomicInteger(0);

        // 两者分别单独压测，互不干扰，耗时才有可比性
        long casNs = timeOps(threads, expected, () -> cas.increment());
        long atomicNs = timeOps(threads, expected, () -> atomic.incrementAndGet());

        System.out.println("总操作数 = " + expected + "（8 线程 × " + loops + "）");
        System.out.println("手写 CAS 自增器 实际值 = " + cas.get() + "（期望 " + expected + "）");
        System.out.println("AtomicInteger    实际值 = " + atomic.get() + "（期望 " + expected + "）");
        System.out.printf("手写 CAS 自增器  总耗时 = %d ms, 平均 = %.1f ns/op%n",
                casNs / 1_000_000, (double) casNs / expected);
        System.out.printf("AtomicInteger    总耗时 = %d ms, 平均 = %.1f ns/op（CAS 由 JDK 内部 Unsafe 完成，失败重试路径更短）%n",
                atomicNs / 1_000_000, (double) atomicNs / expected);

        check("手写 CAS 自增器结果正确（无丢失更新）", expected, cas.get());
        check("AtomicInteger 结果正确（无丢失更新）", expected, atomic.get());

        System.out.println(">> 面试点: CAS 的三个步骤是「读 → 改 → CAS 写回」，CAS 成功即原子完成，失败则自旋重试；"
                + "CAS 保证了「比较」和「写入」的原子性，但不保证整个读-改-写序列只有一次执行。");
    }

    // ------------------------------------------------------------------
    // 实验 2：ABA 问题
    // ------------------------------------------------------------------

    private static void step2_abaProblem() throws InterruptedException {
        System.out.println("=== 实验2: ABA 问题——值从 A 变 B 再变回 A 后，CAS 会「误判」为未发生变化 ===");

        // 2.1 用 AtomicReference 演示 ABA 误判
        AtomicReference<String> ref = new AtomicReference<>("A");
        CountDownLatch readDone = new CountDownLatch(1);   // 读线程已读到期望值
        CountDownLatch mayCas = new CountDownLatch(1);     // 放行读线程执行 CAS
        boolean[] casResult = new boolean[1];
        String[] casExpected = new String[1];

        Thread casThread = new Thread(() -> {
            try {
                String expected = ref.get();                // 读到 "A"
                casExpected[0] = expected;
                readDone.countDown();
                mayCas.await();
                // 此刻真实值已经被别人改成 B 又改回 A，但这里只比较「值是否还等于 A」
                casResult[0] = ref.compareAndSet(expected, "C");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "cas-thread");

        casThread.start();
        readDone.await();
        ref.set("B");                                      // 别的线程把值改成 B
        ref.set("A");                                      // 又改回 A
        mayCas.countDown();
        casThread.join(5000);

        System.out.println("值经历 A → B → A 之后，读线程持有的期望值 = " + casExpected[0]
                + "，CAS(期望值 → \"C\") 结果 = " + casResult[0] + "（期望 false，实际发生了误判）");
        check("AtomicReference 出现 ABA 误判（CAS 意外成功）", Boolean.TRUE, casResult[0]);

        // 2.2 用 AtomicStampedReference 修复
        AtomicStampedReference<String> stamped = new AtomicStampedReference<>("A", 0);
        int stampBefore = stamped.getStamp();
        String refBefore = stamped.getReference();
        stamped.set("B", 1);                               // 值变了，版本号 +1
        stamped.set("A", 2);                               // 值变回来了，版本号继续 +1
        boolean stampedCas = stamped.compareAndSet(refBefore, "C", stampBefore, stampBefore + 1);

        System.out.println("AtomicStampedReference: CAS 前的版本号 = " + stampBefore
                + "，当前版本号 = " + stamped.getStamp() + "，当前值 = " + stamped.getReference());
        System.out.println("AtomicStampedReference.compareAndSet 结果 = " + stampedCas + "（期望 false，版本号不同 → 拒绝修改）");
        check("AtomicStampedReference 通过版本号拒绝了 ABA 误判", Boolean.FALSE, stampedCas);

        System.out.println(">> 面试点: ABA 的根因是「CAS 只比较值，不比较版本」；"
                + "解法是给值附加一个单调递增的版本号（AtomicStampedReference），"
                + "或者改用不可变对象引用、或者用锁来消除这个窗口。");
    }

    // ------------------------------------------------------------------
    // 实验 3：LongAdder vs AtomicLong
    // ------------------------------------------------------------------

    private static void step3_longAdderVsAtomicLong() throws InterruptedException {
        System.out.println("=== 实验3: LongAdder 用 Cell 槽位分散热点，多线程下显著优于 AtomicLong 的单点 CAS ===");

        runAdderCompare(1, 2_000_000);
        runAdderCompare(8, 4_000_000);

        System.out.println(">> 面试点: AtomicLong 是「单变量 + CAS 自旋」，线程越多争抢越激烈；"
                + "LongAdder 把累加分摊到 Cell 数组上，每个线程大概率落到不同 Cell，"
                + "只有扩容时才需要竞争 cellsBusy 锁，因此高并发写多、读少的场景要用 LongAdder。");
    }

    /** 同样的操作量，分别用 AtomicLong 和 LongAdder 跑一遍，比较耗时。 */
    private static void runAdderCompare(int threads, int totalOps) throws InterruptedException {
        System.out.println("-- 线程数 = " + threads + "，总操作数 = " + totalOps + " --");

        AtomicLong atomicLong = new AtomicLong(0);
        long atomicNs = timeOps(threads, totalOps, () -> atomicLong.incrementAndGet());

        LongAdder longAdder = new LongAdder();
        long adderNs = timeOps(threads, totalOps, () -> longAdder.add(1L));

        System.out.printf("  AtomicLong : %d ms, %.1f ns/op, 最终值 = %d%n",
                atomicNs / 1_000_000, (double) atomicNs / totalOps, atomicLong.get());
        System.out.printf("  LongAdder  : %d ms, %.1f ns/op, 最终值 = %d%n",
                adderNs / 1_000_000, (double) adderNs / totalOps, longAdder.sum());
        check("AtomicLong 累加结果正确", (long) totalOps, atomicLong.get());
        check("LongAdder 累加结果正确", (long) totalOps, longAdder.sum());
    }

    /** 起 threads 个线程并发执行 totalOps 次操作，返回总耗时（纳秒）。 */
    private static long timeOps(int threads, int totalOps, Runnable op) throws InterruptedException {
        int perThread = totalOps / threads;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        op.run();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        long t0 = System.nanoTime();
        start.countDown();
        done.await(120, TimeUnit.SECONDS);
        return System.nanoTime() - t0;
    }

    // ------------------------------------------------------------------
    // 实验 4：AtomicIntegerFieldUpdater 与 LongAdder 的 Cell 槽位
    // ------------------------------------------------------------------

    /** 供 AtomicIntegerFieldUpdater 使用的目标对象：字段必须是 volatile int，且调用方要有访问权限。 */
    static final class Holder {
        volatile int value;
    }

    private static void step4_fieldUpdaterAndCells() throws Exception {
        System.out.println("=== 实验4: AtomicIntegerFieldUpdater 直接在字段上做 CAS；LongAdder 的 base/cells 槽位分布 ===");

        // 4.1 AtomicIntegerFieldUpdater：对 Holder.value 这个字段做原子更新
        Holder holder = new Holder();
        AtomicIntegerFieldUpdater<Holder> updater = AtomicIntegerFieldUpdater.newUpdater(Holder.class, "value");
        int before = updater.getAndIncrement(holder);
        int after = updater.get(holder);
        System.out.println("AtomicIntegerFieldUpdater: getAndIncrement 前 = " + before + "，后 = " + after + "（期望 0 → 1）");
        check("AtomicIntegerFieldUpdater 原子自增", 1, after);

        // 4.2 观察 LongAdder 内部的 base 与 cells
        final int threads = 8;
        final int perThread = 200_000;
        LongAdder adder = new LongAdder();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        adder.add(1L);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await(60, TimeUnit.SECONDS);

        System.out.println("LongAdder 累加结果 = " + adder.sum() + "（期望 " + (threads * perThread) + "）");
        check("LongAdder 累加结果正确", (long) threads * perThread, adder.sum());

        if (!InternalField.available()) {
            System.out.println("反射观察 Striped64 内部结构不可用（Unsafe 不可访问），跳过本小节的槽位观察。");
        } else {
            Class<?> striped64 = Class.forName("java.util.concurrent.atomic.Striped64");
            Class<?> cellClass = Class.forName("java.util.concurrent.atomic.Striped64$Cell");
            long base = InternalField.readLong(adder, striped64, "base");
            Object cells = InternalField.readObject(adder, striped64, "cells");
            int cellCount = cells == null ? 0 : java.lang.reflect.Array.getLength(cells);
            System.out.println("Striped64.base = " + base + "（未被 Cell 分摊掉的部分）");
            System.out.println("Striped64.cells 数组长度 = " + cellCount + "（Cell 槽位数量，CPU 核数 = "
                    + Runtime.getRuntime().availableProcessors() + "）");
            if (cells != null) {
                StringBuilder sb = new StringBuilder("各 Cell 槽位的值 = [");
                for (int i = 0; i < cellCount; i++) {
                    Object cell = java.lang.reflect.Array.get(cells, i);
                    sb.append(i == 0 ? "" : ", ").append(InternalField.readLong(cell, cellClass, "value"));
                }
                sb.append("]");
                System.out.println(sb);
            }
            check("LongAdder 把累加分散到了多个 Cell 槽位", Boolean.TRUE, cellCount > 1);
            System.out.println("说明：base + 所有 Cell 的和 = " + base + " + 各槽位之和 = " + adder.sum()
                    + "；线程数少于槽位数时，槽位不一定被全部占满。");
        }

        System.out.println(">> 面试点: AtomicIntegerFieldUpdater 让「已有对象的一个 volatile int 字段」获得原子语义，"
                + "省掉 AtomicInteger 的额外对象；LongAdder 底层是 Striped64（base + Cell[]），"
                + "低竞争时走 base 的 CAS，高竞争时按线程探针散列到不同 Cell，扩容用 cellsBusy 自旋锁串行化。");
    }

    // ------------------------------------------------------------------
    // 反射工具
    // ------------------------------------------------------------------

    /**
     * 读取 JDK 内部私有字段的工具。
     * <p>JDK 9+ 的模块系统没有向未命名模块开放 java.base 的包，
     * {@code Field.setAccessible(true)} 会抛 InaccessibleObjectException；
     * 这里改用 jdk.unsupported 模块导出的 sun.misc.Unsafe 取字段偏移后直接读值，避开访问检查。
     */
    static final class InternalField {
        private static final sun.misc.Unsafe UNSAFE;

        static {
            sun.misc.Unsafe u = null;
            try {
                java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                u = (sun.misc.Unsafe) f.get(null);
            } catch (Throwable ignored) {
                // 取不到就保持为 null，下面的调用方会走「不可用」分支
            }
            UNSAFE = u;
        }

        private InternalField() {
        }

        static boolean available() {
            return UNSAFE != null;
        }

        @SuppressWarnings("deprecation")
        static int readInt(Object target, Class<?> owner, String field) throws Exception {
            return UNSAFE.getInt(target, UNSAFE.objectFieldOffset(owner.getDeclaredField(field)));
        }

        @SuppressWarnings("deprecation")
        static long readLong(Object target, Class<?> owner, String field) throws Exception {
            return UNSAFE.getLong(target, UNSAFE.objectFieldOffset(owner.getDeclaredField(field)));
        }

        @SuppressWarnings("deprecation")
        static Object readObject(Object target, Class<?> owner, String field) throws Exception {
            return UNSAFE.getObject(target, UNSAFE.objectFieldOffset(owner.getDeclaredField(field)));
        }
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
