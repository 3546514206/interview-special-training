package edu.zjnu.interview.lock.q06;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;

/**
 * Q6 · StampedLock 实验 (乐观读 / 校验重试 / 不可重入 / 锁转换 / 吞吐对比)
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q06.L06StampedLockLab
 *
 * 验证面试点:
 * 1. 乐观读: tryOptimisticRead 不加锁, validate 校验期间是否有写发生
 * 2. 校验失败必须重试: 读到旧值但 validate=false 时要重来一遍
 * 3. StampedLock 不可重入也不支持 Condition: 同线程重复获取写锁会自锁
 * 4. 锁转换: tryConvertToReadLock(降级) 与 tryConvertToWriteLock(乐观读升级为写锁)
 * 5. 与 ReentrantReadWriteLock 的吞吐对比 (读多写少)
 */
public class L06StampedLockLab {

    /** 被保护的普通字段: 乐观读就是不加锁直接读它, 因此可能读到旧值 */
    private static long value;

    /** 汇总变量: 防止 JIT 把读操作优化掉 */
    private static volatile long sink;

    public static void main(String[] args) throws Exception {
        step1_optimisticRead();
        step2_optimisticReadInvalidated();
        step3_notReentrant();
        step4_lockConversion();
        step5_throughputCompare();
    }

    // ========== 实验1: 乐观读 ==========

    static void step1_optimisticRead() {
        System.out.println("=== 实验1: 乐观读 —— 不加锁直接读, 靠 validate 校验 ===");
        StampedLock sl = new StampedLock();
        value = 100;

        long stamp = sl.tryOptimisticRead();
        long v = value;
        boolean ok = sl.validate(stamp);
        System.out.println("  期望: stamp 非 0 且 validate=true -> 实际: stamp=" + stamp
                + ", 读到值=" + v + ", validate=" + ok);
        System.out.println("  期望: 乐观读期间不持有任何锁 -> 实际: isReadLocked=" + sl.isReadLocked()
                + ", isWriteLocked=" + sl.isWriteLocked());

        long stamp2 = sl.tryOptimisticRead();
        System.out.println("  期望: 同一时刻两次乐观读拿到相同 stamp(它不是锁) -> 实际: "
                + stamp + " vs " + stamp2 + ", 相同=" + (stamp == stamp2));
        System.out.println(">> 面试点: 乐观读不进入临界区, 只拿一个版本号 stamp;");
        System.out.println("   validate 校验的是\"读数据期间有没有发生过写\", 失败就必须重试 —— 少写多读时最划算\n");
    }

    // ========== 实验2: 写锁期间乐观读 -> 校验失败 -> 重试 ==========

    static void step2_optimisticReadInvalidated() throws Exception {
        System.out.println("=== 实验2: 读到的值是旧的, validate 失败 -> 必须重试 ===");
        StampedLock sl = new StampedLock();
        value = 0;
        CountDownLatch readerRead = new CountDownLatch(1);   // 读线程已取到 stamp 并读到旧值
        CountDownLatch writerDone = new CountDownLatch(1);    // 写线程已改完值
        AtomicInteger stampWhileWriteLocked = new AtomicInteger(-1);

        Thread writer = new Thread(() -> {
            try {
                readerRead.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long ws = sl.writeLock();
            try {
                // 写锁持有期间再取乐观读 stamp: 实测直接失败, 返回 0
                stampWhileWriteLocked.set((int) sl.tryOptimisticRead());
                value = 42;
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                sl.unlockWrite(ws);
            }
            writerDone.countDown();
        }, "写线程");
        writer.setDaemon(true);
        writer.start();

        // 读线程: 先拿 stamp, 再读值(此时写线程还没改, 读到旧值 0)
        long stamp = sl.tryOptimisticRead();
        long seen = value;
        readerRead.countDown();
        writerDone.await();
        boolean valid = sl.validate(stamp);
        System.out.println("  第 1 次: 读到的值=" + seen + ", validate=" + valid + ", 重试次数=0");
        System.out.println("  写锁持有期间 tryOptimisticRead() 返回 " + stampWhileWriteLocked.get()
                + " (0 = 获取失败, 连乐观读都拿不到 stamp)");

        int retries = 0;
        long stamp2;
        long seen2;
        boolean valid2;
        do {
            stamp2 = sl.tryOptimisticRead();
            seen2 = value;
            retries++;
            valid2 = sl.validate(stamp2);
        } while (!valid2 && retries < 100);
        System.out.println("  第 2 次: 读到的值=" + seen2 + ", validate=" + valid2 + ", 重试次数=" + retries);
        System.out.println("  期望: 第 1 次校验失败, 重试后读到 42 且校验通过 -> 实际: "
                + (!valid && valid2 && seen2 == 42));
        System.out.println(">> 面试点: 乐观读读到旧值是正常现象, 关键在校验失败后重试;");
        System.out.println("   注意重试次数没有上限, 写压力大时可能一直重试, 所以乐观读只适合读远多于写的场景\n");
    }

    // ========== 实验3: StampedLock 不可重入 ==========

    static void step3_notReentrant() throws InterruptedException {
        System.out.println("=== 实验3: 不可重入 —— 同线程重复获取写锁会自锁 ===");
        StampedLock sl = new StampedLock();
        long s1 = sl.writeLock();
        System.out.println("  期望: 同一线程第二次 tryWriteLock() 失败 -> 实际: 返回 " + sl.tryWriteLock()
                + " (0 = 失败, 因为写锁不可重入)");
        System.out.println("  期望: 持有写锁时 tryOptimisticRead 失败 -> 实际: 返回 " + sl.tryOptimisticRead()
                + " (0 = 失败)");
        sl.unlockWrite(s1);

        // 同线程重复 writeLock(): 用守护线程验证它会永久阻塞
        StampedLock sl2 = new StampedLock();
        Thread self = new Thread(() -> {
            long a = sl2.writeLock();
            System.out.println("  [自锁线程] 已持有写锁, 再次调用 writeLock() ...");
            long b = sl2.writeLock();   // 同线程重入 -> 永久阻塞
            sl2.unlockWrite(b);
            sl2.unlockWrite(a);
        }, "自锁线程");
        self.setDaemon(true);
        self.start();
        self.join(800);
        System.out.println("  期望: 同线程重复 writeLock() 永久阻塞 -> 实际: 线程仍存活 = " + self.isAlive()
                + " (true = 卡在自锁, 这就是\"不可重入\"的代价)");
        System.out.println(">> 面试点: StampedLock 基于 AQS 的 state + 版本号实现, 不维护重入计数, 因此不可重入;");
        System.out.println("   也不能 newCondition; 用错会直接自锁, 排查成本高 —— 常规业务优先 ReentrantReadWriteLock\n");
    }

    // ========== 实验4: 锁转换 ==========

    static void step4_lockConversion() {
        System.out.println("=== 实验4: 锁转换 —— 写锁降级为读锁 / 乐观读升级为写锁 ===");
        StampedLock sl = new StampedLock();
        value = 0;

        // 4.1 写锁 -> 读锁(降级): 与 ReentrantReadWriteLock 的锁降级等价
        long ws = sl.writeLock();
        value = 100;
        long rs = sl.tryConvertToReadLock(ws);
        long seen = value;
        System.out.println("  降级: tryConvertToReadLock 返回 " + rs
                + ", 降级后 isWriteLocked=" + sl.isWriteLocked() + ", isReadLocked=" + sl.isReadLocked());
        System.out.println("  期望: 降级后仍读到 100(自己写的值, 不丢写) -> 实际: " + seen);
        sl.unlockRead(rs);

        // 4.2 乐观读 -> 写锁(升级): 只要期间没有别的写者, 就能直接转成写锁
        long os = sl.tryOptimisticRead();
        long ws2 = sl.tryConvertToWriteLock(os);
        if (ws2 != 0) {
            value++;
            System.out.println("  升级: tryConvertToWriteLock 成功, 持写锁把值加到 " + value);
            sl.unlockWrite(ws2);
        } else {
            System.out.println("  升级: tryConvertToWriteLock 失败(期间有写者), 需要重试乐观读");
        }

        // 4.3 对照: ReentrantReadWriteLock 不支持读->写升级
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        rw.readLock().lock();
        boolean upgraded = false;
        try {
            upgraded = rw.writeLock().tryLock();
        } catch (IllegalMonitorStateException e) {
            System.out.println("  对照(读写锁): 升级抛异常 " + e.getClass().getSimpleName());
        } finally {
            rw.readLock().unlock();
        }
        System.out.println("  对照(读写锁): 读锁升级写锁 -> 实际: " + (upgraded ? "成功" : "失败(不支持)"));
        System.out.println(">> 面试点: StampedLock 支持两个方向的转换 —— tryConvertToReadLock 降级(不丢写),");
        System.out.println("   tryConvertToWriteLock 升级(乐观读直接转写锁, 但期间有写者就失败需重试)\n");
    }

    // ========== 实验5: 与 ReentrantReadWriteLock 的吞吐对比 ==========

    static void step5_throughputCompare() throws Exception {
        System.out.println("=== 实验5: 吞吐对比 —— 读多写少场景 ===");
        int threads = 4;
        int iterations = 200_000;
        int writeEvery = 100;   // 每 100 次操作写 1 次

        long stampedOps = benchStamped(threads, iterations, writeEvery);
        long rwOps = benchReadWrite(threads, iterations, writeEvery);
        long syncOps = benchSynchronized(threads, iterations, writeEvery);

        System.out.println("  " + pad("锁实现", 30) + pad("耗时(ms)", 12) + pad("吞吐(ops/s)", 16));
        System.out.println("  " + pad("synchronized", 30) + pad(fmt(syncMs), 12) + pad(fmt(syncOps), 16));
        System.out.println("  " + pad("ReentrantReadWriteLock", 30) + pad(fmt(rwMs), 12) + pad(fmt(rwOps), 16));
        System.out.println("  " + pad("StampedLock(乐观读)", 30) + pad(fmt(stampedMs), 12) + pad(fmt(stampedOps), 16));
        System.out.println("  期望: 读多写少时 StampedLock 吞吐最高 -> 实际: "
                + (stampedOps >= rwOps ? "StampedLock 不低于读写锁" : "本次运行 StampedLock 反而更低"));
        System.out.println(">> 面试点: StampedLock 乐观读不走 AQS 队列, 读路径几乎零竞争;");
        System.out.println("   数值受机器核数与 JIT 预热影响, 只看相对趋势; 写密集场景三者差距会显著缩小\n");
    }

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

    static String fmt(double d) {
        return String.format("%.1f", d);
    }

    static String fmt(long l) {
        return String.format("%.0f", (double) l);
    }

    private static double syncMs;
    private static double rwMs;
    private static double stampedMs;

    /** 起 threads 个线程跑同一段逻辑并等待结束 */
    static void runThreads(int threads, int iterations, Runnable body) throws InterruptedException {
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(body);
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join();
        }
    }

    static long benchStamped(int threads, int iterations, int writeEvery) throws InterruptedException {
        StampedLock sl = new StampedLock();
        Runnable body = () -> {
            for (int j = 0; j < iterations; j++) {
                if (j % writeEvery == 0) {
                    long w = sl.writeLock();
                    try {
                        value++;
                    } finally {
                        sl.unlockWrite(w);
                    }
                } else {
                    long stamp;
                    long v;
                    do {
                        stamp = sl.tryOptimisticRead();
                        v = value;
                    } while (!sl.validate(stamp));
                    sink += v;
                }
            }
        };
        value = 0;
        runThreads(threads, iterations / 10, body);   // 预热, 让 JIT 先编译热点代码
        value = 0;
        long t0 = System.nanoTime();
        runThreads(threads, iterations, body);
        stampedMs = (System.nanoTime() - t0) / 1_000_000.0;
        return (long) (threads * iterations / (stampedMs / 1000.0));
    }

    static long benchReadWrite(int threads, int iterations, int writeEvery) throws InterruptedException {
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        Runnable body = () -> {
            for (int j = 0; j < iterations; j++) {
                if (j % writeEvery == 0) {
                    rw.writeLock().lock();
                    try {
                        value++;
                    } finally {
                        rw.writeLock().unlock();
                    }
                } else {
                    rw.readLock().lock();
                    try {
                        sink += value;
                    } finally {
                        rw.readLock().unlock();
                    }
                }
            }
        };
        value = 0;
        runThreads(threads, iterations / 10, body);   // 预热
        value = 0;
        long t0 = System.nanoTime();
        runThreads(threads, iterations, body);
        rwMs = (System.nanoTime() - t0) / 1_000_000.0;
        return (long) (threads * iterations / (rwMs / 1000.0));
    }

    static long benchSynchronized(int threads, int iterations, int writeEvery) throws InterruptedException {
        Object lock = new Object();
        Runnable body = () -> {
            for (int j = 0; j < iterations; j++) {
                if (j % writeEvery == 0) {
                    synchronized (lock) {
                        value++;
                    }
                } else {
                    synchronized (lock) {
                        sink += value;
                    }
                }
            }
        };
        value = 0;
        runThreads(threads, iterations / 10, body);   // 预热
        value = 0;
        long t0 = System.nanoTime();
        runThreads(threads, iterations, body);
        syncMs = (System.nanoTime() - t0) / 1_000_000.0;
        return (long) (threads * iterations / (syncMs / 1000.0));
    }
}
