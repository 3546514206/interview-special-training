package edu.zjnu.lab.q03;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Q3 · AQS 原理实验
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.lab.q03.Q03AqsLab
 *
 * 验证面试点:
 * 1. AQS 是框架不是锁: 只提供 state + CLH 队列, 锁语义由子类的 tryAcquire/tryRelease 定义
 * 2. 不同实现里 state 含义不同 (ReentrantLock=重入次数, Semaphore=许可证, Latch=倒数)
 * 3. 公平 vs 非公平: 公平锁先看队列, 非公平上来先 CAS 抢
 * 4. CountDownLatch 一次性 vs CyclicBarrier 可 reset 循环使用
 */
public class Q03AqsLab {

    public static void main(String[] args) throws Exception {
        step1_customLockByAqs();
        step2_stateMeanings();
        step3_fairVsUnfair();
        step4_latchVsBarrier();
    }

    // ========== 实验1: 手写一个基于 AQS 的互斥锁 (20 行看懂 AQS 用法) ==========

    /**
     * state: 0=没人拿, 1=被拿走. 这就是 ReentrantLock 非公平版的骨架.
     * AQS 负责: 失败的线程进 CLH 双向队列排队 + park 挂起 + 释放时 unpark 唤醒后继.
     */
    static class SimpleMutex extends AbstractQueuedSynchronizer {
        @Override
        protected boolean tryAcquire(int arg) {
            return compareAndSetState(0, 1);              // CAS 抢 state, 抢不到交给 AQS 排队
        }

        @Override
        protected boolean tryRelease(int arg) {
            setState(0);                                  // 只有持锁线程会走到这, 无需 CAS
            return true;
        }

        @Override
        protected boolean isHeldExclusively() {
            return getState() == 1;
        }
    }

    static void step1_customLockByAqs() throws Exception {
        System.out.println("=== 实验1: 继承 AQS 手写互斥锁, 10 线程各加 1 万 ===");
        SimpleMutex mutex = new SimpleMutex();
        int[] counter = {0}; // 故意用裸数组, 锁由我们的 SimpleMutex 提供
        Thread[] ts = new Thread[10];
        for (int t = 0; t < 10; t++) {
            ts[t] = new Thread(() -> {
                for (int i = 0; i < 10_000; i++) {
                    mutex.acquire(1);   // -> tryAcquire, 失败则 addWaiter + acquireQueued(排队/park)
                    try {
                        counter[0]++;
                    } finally {
                        mutex.release(1); // -> tryRelease + unparkSuccessor 唤醒后继
                    }
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        System.out.println("期望 100000, 实际 " + counter[0] + " => " + (counter[0] == 100_000 ? "AQS 队列机制工作正常" : "有 bug"));
        System.out.println(">> 面试点: AQS 提供 state/volatile + CLH 双向队列 + CAS; 子类只写 tryAcquire/tryRelease 定义语义\n");
    }

    // ========== 实验2: 同一个 state, 三种含义 ==========

    static void step2_stateMeanings() throws Exception {
        System.out.println("=== 实验2: 反射偷看不同锁的 AQS state ===");
        ReentrantLock rl = new ReentrantLock();
        rl.lock();
        rl.lock(); // 重入一次
        System.out.println("ReentrantLock lock 两次: state = " + aqsState(rl) + "  (含义: 重入次数, 2=持有+重入1次)");

        java.util.concurrent.Semaphore sem = new java.util.concurrent.Semaphore(5);
        sem.acquire();
        sem.acquire();
        System.out.println("Semaphore(5) acquire 两次: state = " + aqsState(sem) + "  (含义: 剩余许可证)");

        CountDownLatch latch = new CountDownLatch(3);
        latch.countDown();
        System.out.println("CountDownLatch(3) countDown 一次: state = " + aqsState(latch) + "  (含义: 还差几次 countDown)\n");
    }

    static int aqsState(Object syncOwner) throws Exception {
        // ReentrantLock/Semaphore/CountDownLatch 都持有一个继承 AQS 的 Sync 字段
        java.lang.reflect.Field f = syncOwner.getClass().getDeclaredField("sync");
        f.setAccessible(true);
        Object sync = f.get(syncOwner);
        java.lang.reflect.Field s = AbstractQueuedSynchronizer.class.getDeclaredField("state");
        s.setAccessible(true);
        return (int) s.get(sync);
    }

    // ========== 实验3: 公平 vs 非公平 ==========

    static void step3_fairVsUnfair() throws Exception {
        System.out.println("=== 实验3: 公平锁按排队顺序获取, 非公平锁允许「插队」 ===");
        System.out.println("-- 公平锁 --");
        runOrderTest(true);
        System.out.println("-- 非公平锁 (默认) --");
        runOrderTest(false);
        System.out.println(">> 公平锁: tryAcquire 前先 hasQueuedPredecessors(), 队列有人就老老实实排队");
        System.out.println(">> 非公平锁: 先 CAS 抢一把, 抢到就用 —— 吞吐高 (减少唤醒切换), 但可能饿死排队线程\n");
    }

    static void runOrderTest(boolean fair) throws Exception {
        ReentrantLock lock = new ReentrantLock(fair);
        lock.lock();                    // 主线程先占住锁, 逼后面 3 个线程排队
        StringBuilder order = new StringBuilder();
        Thread[] ts = new Thread[3];
        for (int i = 0; i < 3; i++) {
            final int id = i;
            ts[i] = new Thread(() -> {
                lock.lock();
                try {
                    order.append("T").append(id).append(" ");
                } finally {
                    lock.unlock();
                }
            });
            ts[i].start();
            Thread.sleep(50);           // 保证入队顺序 = 创建顺序: T0 -> T1 -> T2
        }
        Thread.sleep(200);              // 等三个线程全部在 CLH 队列里挂好
        lock.unlock();                  // 释放, 观察谁来接棒
        for (Thread t : ts) t.join();
        System.out.println("实际获取顺序: " + order + (fair ? "(FIFO)" : "(可能插队)"));
    }

    // ========== 实验4: CountDownLatch 一次性 vs CyclicBarrier 可循环 ==========

    static void step4_latchVsBarrier() throws Exception {
        System.out.println("=== 实验4: Latch 用完即废, Barrier 可 reset 复用 ===");
        CountDownLatch latch = new CountDownLatch(1);
        latch.countDown();
        latch.await(); // count=0, 直接通过
        System.out.println("Latch countDown 到 0 后就永久敞开, 无法重置 —— 一次性");

        CyclicBarrier barrier = new CyclicBarrier(2, () -> System.out.println("  [2 方到齐 -> 执行屏障动作 -> generation 换代, 自动可复用]"));
        Thread side = new Thread(() -> {
            try {
                barrier.await();
                barrier.await();
            } catch (Exception ignored) {
            }
        });
        side.start();
        for (int round = 1; round <= 2; round++) {
            barrier.await();
            System.out.println("第 " + round + " 轮: 双方通过同一个屏障 —— 可循环复用");
        }
        side.join();
        System.out.println(">> 面试点: Latch 的 state 不能回填; Barrier 靠 generation 换代实现可循环\n");
    }
}
