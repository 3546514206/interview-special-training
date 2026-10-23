package edu.zjnu.interview.lock.q05;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Q5 · 读写锁实验 (ReentrantReadWriteLock)
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q05.L05ReadWriteLockLab
 *
 * 验证面试点:
 * 1. 读读共享: 多个读线程可同时进入临界区; 读写互斥: 写线程与读线程互斥
 * 2. 锁降级 (write -> read) 可行, 降级后仍能读到"自己写进去的值", 不丢写
 * 3. 锁升级 (read -> write) 不可行: JDK 17 实测 tryLock 返回 false, lock() 永久阻塞
 * 4. 公平锁 vs 非公平锁: 公平 = 严格 FIFO, 非公平 = 允许插队, 但吞吐更高
 * 5. 写线程饥饿: 非公平模式下读线程是否插队, 取决于队首是否为等待中的写者
 */
public class L05ReadWriteLockLab {

    /** 带下标的读操作(用于两个读线程复用同一段逻辑) */
    interface IndexedReader {
        void read(int idx);
    }

    /** 被保护的共享数据 */
    static class Data {
        int value;
    }

    private static final Data DATA = new Data();

    public static void main(String[] args) throws Exception {
        step1_readShared();
        step2_readWriteExclusive();
        step3_lockDowngrade();
        step4_upgradeUnsupported();
        step5_fairVsUnfair();
        step6_writerStarvation();
        step7_stampedLockPositioning();
    }

    // ========== 实验1: 读读共享 ==========

    static void step1_readShared() throws Exception {
        System.out.println("=== 实验1: 读读共享 —— 两个读线程同时进入临界区 ===");
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CyclicBarrier inside = new CyclicBarrier(2);
        long[] enter = new long[2];
        long[] leave = new long[2];

        IndexedReader reader = (int idx) -> {
            rw.readLock().lock();
            try {
                int cur = active.incrementAndGet();
                maxActive.updateAndGet(m -> Math.max(m, cur));
                enter[idx] = System.nanoTime();
                inside.await(2, TimeUnit.SECONDS);  // 保证两个读线程同时在临界区内
                Thread.sleep(150);
                leave[idx] = System.nanoTime();
            } catch (Exception ignored) {
                // 演示代码: 忽略中断与栅栏异常
            } finally {
                active.decrementAndGet();
                rw.readLock().unlock();
            }
        };
        Thread r1 = new Thread(() -> reader.read(0), "读线程1");
        Thread r2 = new Thread(() -> reader.read(1), "读线程2");
        r1.setDaemon(true);
        r2.setDaemon(true);
        long t0 = System.nanoTime();
        r1.start();
        r2.start();
        r1.join(3000);
        r2.join(3000);

        System.out.println("  期望: 同时在读临界区的线程数 = 2 -> 实际: " + maxActive.get());
        System.out.println("  读线程1 区间: [" + ms(enter[0] - t0) + "ms, " + ms(leave[0] - t0) + "ms]");
        System.out.println("  读线程2 区间: [" + ms(enter[1] - t0) + "ms, " + ms(leave[1] - t0) + "ms]");
        boolean overlap = enter[1] < leave[0] && enter[0] < leave[1];
        System.out.println("  期望: 两个区间重叠(证明并发进入) -> 实际: " + overlap);
        System.out.println(">> 面试点: 读锁共享同一份 state 的读计数, 多个读线程可同时持锁;");
        System.out.println("   这是读写锁的全部价值 —— 读多场景下把并发度从 1 提升到 N\n");
    }

    // ========== 实验2: 读写互斥 ==========

    static void step2_readWriteExclusive() throws Exception {
        System.out.println("=== 实验2: 读写互斥 —— 写线程必须等读线程释放读锁 ===");
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        long[] stamp = new long[2];   // [0] 读线程释放读锁的时刻, [1] 写线程拿到写锁的时刻
        long t0 = System.nanoTime();

        Thread reader = new Thread(() -> {
            rw.readLock().lock();
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                // 演示代码: 忽略中断
            } finally {
                stamp[0] = System.nanoTime();
                rw.readLock().unlock();
            }
        }, "持读锁线程");
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(50);     // 确保读线程已先拿到读锁
            } catch (InterruptedException ignored) {
                // 演示代码: 忽略中断
            }
            rw.writeLock().lock();
            try {
                stamp[1] = System.nanoTime();
            } finally {
                rw.writeLock().unlock();
            }
        }, "写线程");
        reader.setDaemon(true);
        writer.setDaemon(true);
        reader.start();
        writer.start();
        reader.join(3000);
        writer.join(3000);

        System.out.println("  读线程释放读锁: " + ms(stamp[0] - t0) + "ms, 写线程获得写锁: " + ms(stamp[1] - t0) + "ms");
        boolean exclusive = stamp[1] > stamp[0];
        System.out.println("  期望: 写锁获得时刻晚于读锁释放时刻 -> 实际: " + exclusive
                + " (间隔 " + ms(stamp[1] - stamp[0]) + "ms)");
        System.out.println(">> 面试点: 读写锁的互斥靠 state 里的写计数: 写计数非 0 时任何读/写都进不来;");
        System.out.println("   读计数非 0 时写线程排队 —— 所以写锁等待时间会包含读锁的持有时长\n");
    }

    // ========== 实验3: 锁降级 ==========

    static void step3_lockDowngrade() throws Exception {
        System.out.println("=== 实验3: 锁降级 (持写锁 -> 取读锁 -> 释放写锁) ===");
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        DATA.value = 0;

        // 正确降级: 先拿读锁再释放写锁
        rw.writeLock().lock();
        try {
            DATA.value = 42;
            rw.readLock().lock();       // 降级第一步: 在持有写锁时获取读锁
            rw.writeLock().unlock();    // 降级第二步: 释放写锁, 保留读锁
            int seen = DATA.value;      // 此刻仍持读锁, 读到的一定是自己写的值
            System.out.println("  期望: 读锁内读到 42(自己写的值, 不丢写) -> 实际: " + seen);
            System.out.println("  期望: 仍持有读锁 -> 实际: 读锁持有计数 = " + rw.getReadHoldCount()
                    + ", 仍持有写锁: " + rw.isWriteLockedByCurrentThread());
            rw.readLock().unlock();
        } finally {
            if (rw.writeLock().isHeldByCurrentThread()) {
                rw.writeLock().unlock();
            }
        }

        // 错误降级: 先释放写锁再取读锁, 中间存在窗口
        rw.writeLock().lock();
        DATA.value = 7;
        rw.writeLock().unlock();        // 写锁已释放
        Thread.sleep(100);               // 窗口期: 其他线程可以改值
        rw.readLock().lock();
        try {
            System.out.println("  对照(先释放写锁再取读锁): 期望读到 7 -> 实际: " + DATA.value
                    + "  <- 中间有窗口, 值可能已被其他线程改写, 这就是必须先取读锁的原因");
        } finally {
            rw.readLock().unlock();
        }
        System.out.println(">> 面试点: 锁降级 = 持写锁时先取读锁, 再释放写锁, 中间不丢写;");
        System.out.println("   常用于\"更新缓存后立刻读回来\"的场景; 升级(读->写)则完全不支持\n");
    }

    // ========== 实验4: 锁升级不支持 ==========

    static void step4_upgradeUnsupported() throws Exception {
        System.out.println("=== 实验4: 锁升级 (持读锁再取写锁) —— 不支持 ===");
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        rw.readLock().lock();
        try {
            // 4.1 tryLock: JDK 17 实测返回 false
            try {
                boolean got = rw.writeLock().tryLock();
                System.out.println("  tryLock() 期望: 无法升级 -> 实际: 返回 " + got);
            } catch (IllegalMonitorStateException e) {
                System.out.println("  tryLock() 抛异常: " + e.getClass().getName() + ": " + e.getMessage());
            }
            // 4.2 tryLock(超时): 同样拿不到
            try {
                boolean got2 = rw.writeLock().tryLock(100, TimeUnit.MILLISECONDS);
                System.out.println("  tryLock(100ms) 期望: 无法升级 -> 实际: 返回 " + got2);
            } catch (IllegalMonitorStateException e) {
                System.out.println("  tryLock(100ms) 抛异常: " + e.getClass().getName() + ": " + e.getMessage());
            }
            // 4.3 lock(): 永久阻塞, 用守护线程 + 超时验证
            Thread up = new Thread(() -> {
                try {
                    rw.writeLock().lock();
                } catch (IllegalMonitorStateException e) {
                    System.out.println("  lock() 抛异常: " + e.getClass().getName() + ": " + e.getMessage());
                }
            }, "尝试升级的线程");
            up.setDaemon(true);
            up.start();
            up.join(600);
            System.out.println("  lock() 期望: 永久阻塞(无法升级) -> 实际: 线程仍存活 = " + up.isAlive()
                    + " (true = 卡在等写锁, 即自我死锁)");
        } finally {
            rw.readLock().unlock();
        }
        System.out.println(">> 面试点: JDK 17 的 ReentrantReadWriteLock 不支持升级, 官方文档写明"
                + "\"If a reader tries to acquire the write lock it will never succeed\";");
        System.out.println("   实测 tryLock 返回 false, lock() 永久阻塞 —— 与\"抛 IllegalMonitorStateException\"的说法有出入, 以实际版本为准");
        System.out.println("   正确做法: 释放读锁再取写锁(中间有窗口), 或用 tryLock + 重试\n");
    }

    // ========== 实验5: 公平 vs 非公平 ==========

    static void step5_fairVsUnfair() throws Exception {
        System.out.println("=== 实验5: 公平锁 vs 非公平锁 ===");
        for (boolean fair : new boolean[]{false, true}) {
            int rounds = 200;
            int barges = 0;   // 插队次数: 后到的线程抢在先到的等待者之前拿到锁
            for (int i = 0; i < rounds; i++) {
                ReentrantReadWriteLock lock = new ReentrantReadWriteLock(fair);
                List<String> order = Collections.synchronizedList(new ArrayList<>());
                CountDownLatch bargerGo = new CountDownLatch(1);

                lock.writeLock().lock();                      // 主线程持写锁
                Thread waiter = new Thread(() -> {
                    lock.writeLock().lock();
                    try {
                        order.add("waiter");
                    } finally {
                        lock.writeLock().unlock();
                    }
                }, "先到的等待者");
                waiter.setDaemon(true);
                waiter.start();
                long deadline = System.currentTimeMillis() + 1000;
                while (!lock.hasQueuedThread(waiter) && System.currentTimeMillis() < deadline) {
                    Thread.sleep(1);
                }
                // 后到的线程: 与释放动作同时发起, 观察它能否抢在排队者前面拿到锁
                Thread barger = new Thread(() -> {
                    try {
                        bargerGo.await();
                    } catch (InterruptedException ignored) {
                        // 演示代码: 忽略中断
                    }
                    lock.writeLock().lock();
                    try {
                        order.add("barger");
                    } finally {
                        lock.writeLock().unlock();
                    }
                }, "后到的线程");
                barger.setDaemon(true);
                barger.start();
                bargerGo.countDown();
                lock.writeLock().unlock();
                waiter.join(2000);
                barger.join(2000);
                if (!order.isEmpty() && "barger".equals(order.get(0))) {
                    barges++;
                }
            }
            System.out.println("  " + (fair ? "公平锁  " : "非公平锁") + ": " + rounds
                    + " 轮竞争, 后到线程插队成功 " + barges + " 次");
        }
        System.out.println("  期望: 公平锁插队 0 次(严格 FIFO), 非公平锁允许插队 -> 实际见上");
        System.out.println(">> 面试点: 公平锁用 hasQueuedPredecessors() 严格 FIFO, 不插队但每次获取都要查队列, 吞吐低;");
        System.out.println("   非公平锁允许 barging, 吞吐更高; 插队是概率性的, 源码注释写明 \"only a probabilistic effect\"\n");
    }

    // ========== 实验6: 写线程饥饿 ==========

    static void step6_writerStarvation() throws Exception {
        System.out.println("=== 实验6: 写线程饥饿 —— 读线程会不会一直插队 ===");
        for (boolean fair : new boolean[]{false, true}) {
            ReentrantReadWriteLock rw = new ReentrantReadWriteLock(fair);
            AtomicInteger readsAfterWriterQueued = new AtomicInteger();
            AtomicInteger writerWaitMicros = new AtomicInteger();
            AtomicBoolean writerQueued = new AtomicBoolean();
            AtomicBoolean writerGot = new AtomicBoolean();
            AtomicBoolean stop = new AtomicBoolean();
            AtomicInteger readersInside = new AtomicInteger();
            CountDownLatch writerAboutToLock = new CountDownLatch(1);

            // 持续读线程: 不断拿读锁(不睡, 模拟读多场景下的持续读压力)
            Thread reader = new Thread(() -> {
                while (!stop.get()) {
                    rw.readLock().lock();
                    readersInside.incrementAndGet();
                    try {
                        if (writerQueued.get() && !writerGot.get()) {
                            readsAfterWriterQueued.incrementAndGet();
                        }
                        Thread.sleep(30);  // 每次读占用 30ms, 让写线程一定能排到队里去
                    } catch (InterruptedException ignored) {
                        // 演示代码: 忽略中断
                    } finally {
                        readersInside.decrementAndGet();
                        rw.readLock().unlock();
                    }
                }
            }, "持续读线程");
            reader.setDaemon(true);
            reader.start();
            Thread.sleep(100);

            // 写线程: 中途请求写锁
            Thread writer = new Thread(() -> {
                // 确保此刻确实有读线程在临界区内, 这样写线程一定会进入等待队列
                while (readersInside.get() == 0) {
                    Thread.onSpinWait();
                }
                writerAboutToLock.countDown();
                long t = System.nanoTime();
                rw.writeLock().lock();
                try {
                    writerWaitMicros.set((int) ((System.nanoTime() - t) / 1_000));
                    writerGot.set(true);
                } finally {
                    rw.writeLock().unlock();
                }
            }, "等待中的写线程");
            writer.setDaemon(true);
            writer.start();

            // 等写线程真正进入等待队列后再开始计数, 否则测到的是线程调度间隙
            writerAboutToLock.await();
            long deadline = System.currentTimeMillis() + 1000;
            while (!rw.hasQueuedThread(writer) && System.currentTimeMillis() < deadline) {
                Thread.sleep(1);
            }
            boolean queued = rw.hasQueuedThread(writer);
            writerQueued.set(queued);

            writer.join(3000);
            stop.set(true);
            reader.join(1000);
            System.out.println("  " + (fair ? "公平锁  " : "非公平锁")
                    + ": 写线程已入队 = " + queued
                    + ", 入队后仍完成的读操作次数 = " + readsAfterWriterQueued.get()
                    + ", 写线程等待 " + (writerWaitMicros.get() / 1000.0) + "ms");
        }
        System.out.println("  期望: 只要写线程已经排队, 新读线程就应被挡住(读操作次数接近 0) -> 实际见上");
        System.out.println(">> 面试点: 非公平模式的 readerShouldBlock() = apparentlyFirstQueuedIsExclusive(),");
        System.out.println("   只要队首是等待中的写者, 新读线程就排队 —— 实测写线程等待时间 ≈ 当前读线程的剩余持有时长,");
        System.out.println("   之后的读操作全部被挡住, 所以\"读线程无限插队饿死写线程\"不是常态;");
        System.out.println("   真正的插队窗口在写线程尚未成为队首时, 以及写线程之间(见实验5: 非公平锁插队成功, 公平锁为 0)");
        System.out.println("   公平模式用 hasQueuedPredecessors(), 写线程有明确等待上界, 代价是吞吐更低\n");
    }

    // ========== 实验7: 与 StampedLock 的定位差异 ==========

    static void step7_stampedLockPositioning() {
        System.out.println("=== 实验7: ReentrantReadWriteLock 与 StampedLock 的定位差异 ===");
        String[][] rows = {
                {"维度", "ReentrantReadWriteLock", "StampedLock"},
                {"可重入", "是(同一线程可重复获取)", "否(不可重入, 重复获取会自锁)"},
                {"Condition", "支持(写锁可 newCondition)", "不支持"},
                {"乐观读", "不支持", "支持(tryOptimisticRead + validate)"},
                {"写->读 降级", "支持", "支持(tryConvertToReadLock)"},
                {"读->写 升级", "不支持", "支持(tryConvertToWriteLock, 需校验)"},
                {"API 复杂度", "较低", "较高(stamp 用错不易排查)"},
        };
        for (String[] r : rows) {
            System.out.println("  " + pad(r[0], 16) + pad(r[1], 34) + pad(r[2], 38));
        }
        System.out.println(">> 面试点: 常规业务用 ReentrantReadWriteLock, 需要极致读性能且能处理校验重试时用 StampedLock;");
        System.out.println("   StampedLock 不可重入且不支持 Condition, 换来的收益见 L06 的吞吐对比\n");
    }

    // ========== 工具方法 ==========

    /** 按显示宽度对齐(中文算 2 列) */
    static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(s);
        for (int i = displayWidth(s); i < width; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            w += (c >= 0x1100 && (c <= 0x115F || c == 0x2329 || c == 0x232A
                    || (c >= 0x2E80 && c <= 0xA4CF && c != 0x303F)
                    || (c >= 0xAC00 && c <= 0xD7A3)
                    || (c >= 0xF900 && c <= 0xFAFF)
                    || (c >= 0xFE30 && c <= 0xFE6F)
                    || (c >= 0xFF00 && c <= 0xFF60)
                    || (c >= 0xFFE0 && c <= 0xFFE6))) ? 2 : 1;
        }
        return w;
    }

    static String ms(long nanos) {
        return String.format("%.1f", nanos / 1_000_000.0);
    }
}
