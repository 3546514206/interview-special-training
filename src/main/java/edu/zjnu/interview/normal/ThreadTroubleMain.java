package edu.zjnu.interview.normal;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多线程访问共享数据的经典问题：自增计数丢失更新。
 *
 * <p>运行命令：
 * <pre>
 * mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.normal.ThreadTroubleMain
 * </pre>
 *
 * <p>验证面试点：
 * <ol>
 *   <li>number++ 编译成「读-改-写」三条字节码，不是原子操作，多线程并发时后写的覆盖先写的，结果小于期望值；</li>
 *   <li>字段加 volatile 只解决可见性，复合操作仍然不原子，丢失更新照旧；</li>
 *   <li>synchronized 把整个读-改-写关进临界区，AtomicInteger 用 CAS 重试实现原子自增，两者结果都正确；</li>
 *   <li>可见性与 happens-before 专题见 edu.zjnu.interview.lock.q01.L01VisibilityLab，
 *       CAS 与 LongAdder 专题见 edu.zjnu.interview.lock.q02.L02CasAtomicLab。</li>
 * </ol>
 *
 * @Date 2026-10-25 20:23
 * @Author 杨海波
 **/
public class ThreadTroubleMain {

    /** 并发线程数 */
    private static final int THREADS = 10;

    /** 每个线程自增的次数 */
    private static final int LOOPS = 10000;

    /** 期望结果 = THREADS * LOOPS */
    private static final int EXPECTED = THREADS * LOOPS;

    public static void main(String[] args) throws InterruptedException {
        // step1_lostUpdate();
        // step2_volatileIsNotAtomic();
        // step3_synchronized();
        step4_atomic();
    }

    // ------------------------------------------------------------------
    // 实验 1：普通 int 字段，多线程自增 -> 丢失更新
    // ------------------------------------------------------------------
    private static void step1_lostUpdate() throws InterruptedException {
        System.out.println("=== 实验1: 普通 int 共享字段自增 ===");
        final NumberReporter reporter = new NumberReporter();

        runConcurrently(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < LOOPS; i++) {
                    reporter.genNumber();
                }
            }
        });

        int actual = reporter.getNumber();
        System.out.println("期望 = " + EXPECTED + ", 实际 = " + actual + ", 丢失 " + (EXPECTED - actual) + " 次更新");
        System.out.println("原因: number++ 是 读-改-写 三步, 线程 A 读出 5、线程 B 读出 5、A 写 6、B 也写 6, 一次更新丢了");
    }

    // ------------------------------------------------------------------
    // 实验 2：字段加 volatile -> 可见性有了, 原子性还是没有, 照样丢更新
    // ------------------------------------------------------------------
    private static void step2_volatileIsNotAtomic() throws InterruptedException {
        System.out.println();
        System.out.println("=== 实验2: volatile 字段自增 ===");
        final VolatileNumberReporter reporter = new VolatileNumberReporter();

        runConcurrently(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < LOOPS; i++) {
                    reporter.genNumber();
                }
            }
        });

        int actual = reporter.getNumber();
        System.out.println("期望 = " + EXPECTED + ", 实际 = " + actual + ", 丢失 " + (EXPECTED - actual) + " 次更新");
        System.out.println("结论: volatile 只保证读写可见性和禁止重排, 不保证 number++ 的原子性");
    }

    // ------------------------------------------------------------------
    // 实验 3：synchronized 把读-改-写整体关进临界区
    // ------------------------------------------------------------------
    private static void step3_synchronized() throws InterruptedException {
        System.out.println();
        System.out.println("=== 实验3: synchronized 自增 ===");
        final SyncNumberReporter reporter = new SyncNumberReporter();

        runConcurrently(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < LOOPS; i++) {
                    reporter.genNumber();
                }
            }
        });

        int actual = reporter.getNumber();
        System.out.println("期望 = " + EXPECTED + ", 实际 = " + actual + ", 丢失 " + (EXPECTED - actual) + " 次更新");
        System.out.println("结论: 同一把锁串行化了读-改-写, 结果一定正确, 代价是线程互相阻塞");
    }

    // ------------------------------------------------------------------
    // 实验 4：AtomicInteger 用 CAS 重试实现原子自增
    // ------------------------------------------------------------------
    private static void step4_atomic() throws InterruptedException {
        System.out.println();
        System.out.println("=== 实验4: AtomicInteger 自增 ===");
        final AtomicNumberReporter reporter = new AtomicNumberReporter();

        runConcurrently(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < LOOPS; i++) {
                    reporter.genNumber();
                }
            }
        });

        int actual = reporter.getNumber();
        System.out.println("期望 = " + EXPECTED + ", 实际 = " + actual + ", 丢失 " + (EXPECTED - actual) + " 次更新");
        System.out.println("结论: CAS 失败就重试, 无需加锁也能保证原子性");
    }

    /**
     * 启动 THREADS 个线程跑同一个任务, join 等它们全部结束后返回。
     * join() 建立了 happens-before 关系, 所以主线程读到的 counter 是各线程都写完之后的最终值。
     */
    private static void runConcurrently(Runnable task) throws InterruptedException {
        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            threads[i] = new Thread(task);
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
    }
}

/** 共享计数器: 普通 int 字段, 没有任何保护 */
class NumberReporter {
    private int number;

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
    }

    public int genNumber() {
        return number++;
    }
}

/** 共享计数器: volatile 字段, 只保证可见性 */
class VolatileNumberReporter {
    private volatile int number;

    public int getNumber() {
        return number;
    }

    public int genNumber() {
        return number++;
    }
}

/** 共享计数器: 自增方法加 synchronized */
class SyncNumberReporter {
    private int number;

    public int getNumber() {
        return number;
    }

    public synchronized int genNumber() {
        return number++;
    }
}

/** 共享计数器: 用 AtomicInteger 做原子自增 */
class AtomicNumberReporter {
    private final AtomicInteger number = new AtomicInteger();

    public int getNumber() {
        return number.get();
    }

    public int genNumber() {
        return number.getAndIncrement();
    }
}
