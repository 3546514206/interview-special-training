package edu.zjnu.interview.lock.q07;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;

/**
 * Q7 · 锁选型基准 (同一临界区, 4 种锁, 2 种负载)
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q07.L07LockChoiceBench
 *
 * 验证面试点:
 * 1. 同一临界区(读计数 + 累加)在 synchronized / ReentrantLock / ReentrantReadWriteLock / StampedLock 下的吞吐差异
 * 2. 读多写少: 读写锁与 StampedLock 的读路径优势; 读写均衡: 写锁成为瓶颈, 优势缩小
 * 3. synchronized 的优势: JVM 内建优化、语法自动释放、jstack 可读性
 * 4. 选型对照表: 什么时候用哪种锁
 */
public class L07LockChoiceBench {

    /** 统一的临界区接口: 读一个值 / 加一 */
    interface Counter {
        long read();

        void increment();

        void reset();
    }

    /** 汇总变量: 防止 JIT 把读操作优化掉 */
    private static volatile long sink;

    public static void main(String[] args) throws Exception {
        step1_readHeavy();
        step2_balanced();
        step3_selectionGuide();
    }

    // ========== 实验1: 读多写少 (90% 读 / 10% 写) ==========

    static void step1_readHeavy() throws Exception {
        System.out.println("=== 实验1: 读多写少 (90% 读 / 10% 写), 4 线程 x 100000 次 ===");
        runScenario(100_000, 10);
        System.out.println(">> 面试点: 读路径的【进入成本】决定胜负 —— StampedLock 乐观读不进临界区, 成本最低;");
        System.out.println("   注意本实验临界区极短(纳秒级), 读锁的 AQS 开销反而盖过了读并发的好处, 读写锁可能慢于 synchronized;");
        System.out.println("   只有当读逻辑本身较重、或线程数远大于核数时, 读并发才会转化成吞吐优势\n");
    }

    // ========== 实验2: 读写均衡 (50% 读 / 50% 写) ==========

    static void step2_balanced() throws Exception {
        System.out.println("=== 实验2: 读写均衡 (50% 读 / 50% 写), 4 线程 x 100000 次 ===");
        runScenario(100_000, 2);
        System.out.println(">> 面试点: 写占比升高后写锁成为瓶颈, 四者的差距显著收窄;");
        System.out.println("   选型不能只看读多写少, 要结合实际写比例; 数值受核数与 JIT 影响, 只看相对趋势\n");
    }

    /** 跑一轮场景: 四种锁跑同一个临界区, 输出耗时/吞吐/计数校验 */
    static void runScenario(int iterations, int writeEvery) throws Exception {
        int threads = 4;
        System.out.printf("  %-24s %10s %14s %12s%n", "锁实现", "耗时(ms)", "吞吐(ops/s)", "计数校验");
        bench("synchronized", new SyncCounter(), threads, iterations, writeEvery);
        bench("ReentrantLock", new ReentrantLockCounter(), threads, iterations, writeEvery);
        bench("ReentrantReadWriteLock", new RwLockCounter(), threads, iterations, writeEvery);
        bench("StampedLock", new StampedLockCounter(), threads, iterations, writeEvery);
    }

    static void bench(String name, Counter counter, int threads, int iterations, int writeEvery)
            throws InterruptedException {
        // 预热: 让 JIT 先把热点代码编译掉, 正式计时只看稳定后的数据
        runOnce(counter, threads, Math.max(1, iterations / 10), writeEvery);
        counter.reset();
        long expected = (long) threads * (iterations / writeEvery);
        long t0 = System.nanoTime();
        runOnce(counter, threads, iterations, writeEvery);
        double ms = (System.nanoTime() - t0) / 1_000_000.0;
        long ops = (long) (threads * iterations / (ms / 1000.0));
        long actual = counter.read();
        System.out.printf("  %-24s %10.1f %14d %12s%n", name, ms, ops,
                (actual == expected ? "PASS(" + actual + ")" : "FAIL(" + actual + "!=" + expected + ")"));
    }

    static void runOnce(Counter counter, int threads, int iterations, int writeEvery) throws InterruptedException {
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                for (int j = 0; j < iterations; j++) {
                    if (j % writeEvery == 0) {
                        counter.increment();
                    } else {
                        sink += counter.read();
                    }
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join();
        }
    }

    // ========== 实验3: 选型对照表 + synchronized 的优势 ==========

    static void step3_selectionGuide() throws Exception {
        System.out.println("=== 实验3: 选型对照表 ===");
        System.out.println("  " + pad("维度", 16) + pad("synchronized", 22) + pad("ReentrantLock", 22)
                + pad("ReentrantReadWriteLock", 28) + pad("StampedLock", 22));
        String[][] rows = {
                {"可重入", "是", "是", "是", "否"},
                {"公平策略", "无(偏向/轻量)", "可选公平", "可选公平", "无"},
                {"Condition", "wait/notify", "支持", "写锁支持", "不支持"},
                {"超时获取", "不支持", "tryLock", "tryLock", "tryLock"},
                {"读并发", "无", "无", "有", "有(乐观读)"},
                {"自动释放", "语法块自动", "必须 finally", "必须 finally", "必须 finally"},
                {"排查友好度", "高", "中", "中", "低"},
        };
        for (String[] r : rows) {
            System.out.println("  " + pad(r[0], 16) + pad(r[1], 22) + pad(r[2], 22) + pad(r[3], 28) + pad(r[4], 22));
        }

        System.out.println();
        System.out.println("=== 实验3b: synchronized 的三个优势 ===");
        System.out.println("  1) JVM 内建优化: 偏向锁(JDK 8 默认开启, 启动后约 4s 生效) -> 轻量级锁(CAS 自旋) -> 重量级锁; 后续 JDK 15+ 才废弃并移除偏向锁");
        System.out.println("  2) 语法自动释放: 退出 synchronized 代码块(含抛异常)自动释放, 不会像 Lock 那样忘记 finally");
        System.out.println("  3) jstack 可读性: 下面用 jcmd Thread.print 对比两种锁的等待线程描述");
        printStackDifference();
        System.out.println(">> 面试点: 选型顺序 —— 默认 synchronized(够用且省心); 需要超时/公平/Condition 用 ReentrantLock;");
        System.out.println("   读多写少且能接受乐观读重试用 StampedLock; 一般业务读写分离用 ReentrantReadWriteLock\n");
    }

    /** 对比: 线程卡在 synchronized 上 vs 卡在 ReentrantLock 上, jstack 输出长什么样 */
    static void printStackDifference() throws Exception {
        Object monitor = new Object();
        ReentrantLock rl = new ReentrantLock();
        CountDownLatch waiting = new CountDownLatch(2);

        // 主线程同时持有两把锁, 两个子线程分别去抢
        synchronized (monitor) {
            rl.lock();
            try {
                Thread t1 = new Thread(() -> {
                    synchronized (monitor) {   // 阻塞在 monitor 上
                        waiting.countDown();
                    }
                }, "wait-on-synchronized");
                Thread t2 = new Thread(() -> {
                    rl.lock();                // 阻塞在 AQS 队列上
                    try {
                        waiting.countDown();
                    } finally {
                        rl.unlock();
                    }
                }, "wait-on-reentrantlock");
                t1.setDaemon(true);
                t2.setDaemon(true);
                t1.start();
                t2.start();
                waiting.await(5, TimeUnit.SECONDS);
                Thread.sleep(200);   // 等两个线程都真正进入等待状态

                String dump = runDumpTool();
                if (dump == null) {
                    System.out.println("     jcmd/jstack 不可用, 本小节跳过");
                    return;
                }
                System.out.println("     [线程 A] 等 synchronized:");
                printThreadBlock(dump, "wait-on-synchronized");
                System.out.println("     [线程 B] 等 ReentrantLock:");
                printThreadBlock(dump, "wait-on-reentrantlock");
            } finally {
                rl.unlock();
            }
        }
    }

    /** 打印指定线程在 jstack 输出里的等待描述 */
    static void printThreadBlock(String dump, String threadName) {
        String[] lines = dump.split("\\R");
        boolean inBlock = false;
        int printed = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("\"" + threadName + "\"")) {
                inBlock = true;
                System.out.println("       " + trimmed.substring(0, Math.min(trimmed.length(), 110)));
                continue;
            }
            if (inBlock) {
                if (trimmed.startsWith("- ")) {
                    System.out.println("       " + trimmed);
                    if (++printed >= 2) {
                        break;
                    }
                } else if (trimmed.isEmpty()) {
                    break;
                }
            }
        }
        if (printed == 0) {
            System.out.println("       (未在 dump 中找到该线程的等待描述)");
        }
    }

    /** Java 8 没有 ProcessHandle, 从 RuntimeMXBean 的 "pid@host" 里取 pid */
    static long currentPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return Long.parseLong(at > 0 ? name.substring(0, at) : name);
    }

    /** 调用 jcmd <pid> Thread.print, 失败回退 jstack, 都不可用返回 null */
    static String runDumpTool() {
        long pid = currentPid();
        String javaHome = System.getProperty("java.home");
        String[] candidates = {javaHome + "/bin/jcmd", javaHome + "/bin/jstack"};
        for (String tool : candidates) {
            if (!new java.io.File(tool).canExecute()) {
                continue;
            }
            List<String> cmd = tool.endsWith("jcmd")
                    ? java.util.Arrays.asList(tool, String.valueOf(pid), "Thread.print")
                    : java.util.Arrays.asList(tool, String.valueOf(pid));
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                }
                if (!p.waitFor(20, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    continue;
                }
                if (p.exitValue() == 0 && sb.length() > 0) {
                    return sb.toString();
                }
            } catch (Exception e) {
                // 演示代码: 工具不可用时继续尝试下一个
            }
        }
        return null;
    }

    // ========== 四种锁的同一临界区实现 ==========

    static class SyncCounter implements Counter {
        private long value;

        @Override
        public synchronized long read() {
            return value;
        }

        @Override
        public synchronized void increment() {
            value++;
        }

        @Override
        public synchronized void reset() {
            value = 0;
        }
    }

    static class ReentrantLockCounter implements Counter {
        private final ReentrantLock lock = new ReentrantLock();
        private long value;

        @Override
        public long read() {
            lock.lock();
            try {
                return value;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void increment() {
            lock.lock();
            try {
                value++;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void reset() {
            lock.lock();
            try {
                value = 0;
            } finally {
                lock.unlock();
            }
        }
    }

    static class RwLockCounter implements Counter {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private long value;

        @Override
        public long read() {
            lock.readLock().lock();
            try {
                return value;
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public void increment() {
            lock.writeLock().lock();
            try {
                value++;
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public void reset() {
            lock.writeLock().lock();
            try {
                value = 0;
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    static class StampedLockCounter implements Counter {
        private final StampedLock lock = new StampedLock();
        private long value;

        @Override
        public long read() {
            long stamp;
            long v;
            do {
                stamp = lock.tryOptimisticRead();
                v = value;
            } while (!lock.validate(stamp));
            return v;
        }

        @Override
        public void increment() {
            long stamp = lock.writeLock();
            try {
                value++;
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        @Override
        public void reset() {
            long stamp = lock.writeLock();
            try {
                value = 0;
            } finally {
                lock.unlockWrite(stamp);
            }
        }
    }

    // ========== 工具方法: 按显示宽度对齐(中文算 2 列) ==========

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
}
