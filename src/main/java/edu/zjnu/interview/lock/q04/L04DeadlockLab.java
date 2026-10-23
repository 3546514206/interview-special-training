package edu.zjnu.interview.lock.q04;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Q4 · 死锁实验 (复现 → 程序内定位 → jcmd 现场抓栈 → 三种预防手段)
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q04.L04DeadlockLab
 *
 * 验证面试点:
 * 1. 死锁四条件: 互斥、持有并等待、不可剥夺、循环等待; 打破任意一条即可预防
 * 2. jstack / jcmd Thread.print 现场定位死锁, 关键标记 "Found one Java-level deadlock"
 * 3. ThreadMXBean.findDeadlockedThreads() 在程序内自检死锁, getThreadInfo 拿到"等待谁/持有谁"
 * 4. 预防手段: 固定加锁顺序、tryLock 带超时降级、消除嵌套锁
 */
public class L04DeadlockLab {

    /** 表对象: 演示"两张表交叉访问"的经典死锁场景 */
    static class Table {
        final String name;
        int rows;

        Table(String name) {
            this.name = name;
        }
    }

    /** 锁对象 -> 名字, 便于把 identityHashCode 还原成"表A/表B" */
    private static final Map<Integer, String> LOCK_NAMES = new HashMap<>();

    private static Table tableA;
    private static Table tableB;
    private static Thread watchdog;

    public static void main(String[] args) throws Exception {
        step1_reproduceDeadlock();
        step2_detectByThreadMXBean();
        step3_dumpWithJcmd();
        step4_fixedLockOrder();
        step5_tryLockTimeout();
        step6_avoidNestedLock();
    }

    /**
     * 兜底守护线程: 死锁线程永不结束, main 绝不能 join 它们。
     * 该线程到点后打印一条兜底信息, 证明"程序不会因为死锁卡住"。
     */
    static void startWatchdog(long timeoutMs) {
        watchdog = new Thread(() -> {
            try {
                Thread.sleep(timeoutMs);
            } catch (InterruptedException e) {
                return;
            }
            ThreadMXBean tb = ManagementFactory.getThreadMXBean();
            long[] ids = tb.findDeadlockedThreads();
            System.out.println("[兜底] " + timeoutMs + "ms 后死锁仍未解除, 死锁线程数="
                    + (ids == null ? 0 : ids.length)
                    + "; main 不 join 死锁线程, 实验可继续, 守护线程保证 JVM 能退出");
        }, "deadlock-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    // ========== 实验1: 复现两锁交叉死锁 ==========

    static void step1_reproduceDeadlock() throws Exception {
        System.out.println("=== 实验1: 两张表交叉加锁 -> 经典死锁 ===");
        tableA = new Table("表A");
        tableB = new Table("表B");
        LOCK_NAMES.put(System.identityHashCode(tableA), tableA.name);
        LOCK_NAMES.put(System.identityHashCode(tableB), tableB.name);

        CyclicBarrier barrier = new CyclicBarrier(2);
        Thread t1 = new Thread(() -> transfer(tableA, tableB, barrier), "T1-先持表A");
        Thread t2 = new Thread(() -> transfer(tableB, tableA, barrier), "T2-先持表B");
        // 守护线程: 死锁线程永远不结束, 必须设为守护线程, 否则 JVM 退出不了
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        startWatchdog(2000);

        Thread.sleep(300); // 留出时间让双方都拿到第一把锁并请求第二把
        printThreadSnapshot("T1-先持表A", t1);
        printThreadSnapshot("T2-先持表B", t2);
        System.out.println("死锁线程状态: " + t1.getState() + " / " + t2.getState() + " (BLOCKED = 等待 monitor)");
        System.out.println(">> 面试点: 死锁四条件 —— 互斥、持有并等待、不可剥夺、循环等待;");
        System.out.println("   本例 T1 持 表A 等 表B, T2 持 表B 等 表A, 满足循环等待\n");
    }

    /** 交叉加锁: 先锁 from, 再锁 to */
    static void transfer(Table from, Table to, CyclicBarrier barrier) {
        try {
            barrier.await();          // 两线程同时拿到各自的第一把锁
            synchronized (from) {
                Thread.sleep(100);    // 保证对方也进入临界区
                synchronized (to) {   // 互相等待 -> 死锁
                    from.rows++;
                }
            }
        } catch (InterruptedException | BrokenBarrierException ignored) {
            // 演示代码: 中断时直接退出
        }
    }

    /** 打印单个线程的栈快照(等待的 monitor + 已持有的 monitor) */
    static void printThreadSnapshot(String label, Thread t) {
        ThreadMXBean tb = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = tb.getThreadInfo(new long[]{t.getId()}, true, true);
        ThreadInfo info = infos.length > 0 ? infos[0] : null;
        if (info == null) {
            System.out.println("  " + label + ": 线程已结束, 取不到快照");
            return;
        }
        System.out.println("  [" + label + "] state=" + info.getThreadState()
                + " 等待的锁=" + describe(info.getLockInfo())
                + " 持有=" + describeMonitors(info.getLockedMonitors()));
        StackTraceElement[] stack = info.getStackTrace();
        for (int i = 0; i < Math.min(3, stack.length); i++) {
            System.out.println("      at " + stack[i]);
        }
    }

    // ========== 实验2: 程序内自检死锁 (ThreadMXBean) ==========

    static void step2_detectByThreadMXBean() throws Exception {
        System.out.println("=== 实验2: ThreadMXBean.findDeadlockedThreads() 程序内定位死锁 ===");
        if (watchdog != null) {
            watchdog.join(3000); // 等兜底线程把超时信息打出来, 保证输出顺序稳定
        }
        ThreadMXBean tb = ManagementFactory.getThreadMXBean();
        long[] ids = tb.findDeadlockedThreads();
        System.out.println("  期望: 检出 2 个死锁线程 -> 实际: " + (ids == null ? "0 (未检出)" : ids.length));
        if (ids == null || ids.length == 0) {
            System.out.println(">> 面试点: 未检出说明当前没有死锁 (或 JVM 不支持该检测), 死锁实验本身失败\n");
            return;
        }
        ThreadInfo[] infos = tb.getThreadInfo(ids, true, true);
        for (ThreadInfo info : infos) {
            System.out.println("  死锁线程 " + info.getThreadName()
                    + " state=" + info.getThreadState()
                    + " 等待的锁=" + describe(info.getLockInfo()));
            System.out.println("      持有关系: " + describeMonitors(info.getLockedMonitors()));
            StackTraceElement[] stack = info.getStackTrace();
            if (stack.length > 0) {
                System.out.println("      阻塞点: " + stack[0]);
            }
        }
        System.out.println(">> 面试点: findDeadlockedThreads() 返回死锁线程 id, getThreadInfo(id, true, true)");
        System.out.println("   才能同时拿到 lockedMonitors(持有哪些) 和 lockInfo(在等哪把) —— 生产可用, 不用 jstack\n");
    }

    // ========== 实验3: jcmd / jstack 现场抓栈 ==========

    static void step3_dumpWithJcmd() throws Exception {
        System.out.println("=== 实验3: jcmd <pid> Thread.print 现场抓死锁现场 ===");
        long pid = ProcessHandle.current().pid();
        System.out.println("  当前进程 pid = " + pid);
        String dump = runDumpTool(pid);
        if (dump == null) {
            System.out.println("  jcmd / jstack 均不可用 (JDK bin 缺失或 attach 被拒绝), 本步跳过");
            System.out.println(">> 面试点: 生产环境定位死锁的标准动作仍是 jstack <pid> | grep -A 20 'Found one Java-level deadlock'\n");
            return;
        }
        boolean found = dump.contains("Found one Java-level deadlock");
        System.out.println("  期望: 输出包含 \"Found one Java-level deadlock\" -> 实际: " + found);
        int idx = dump.indexOf("Found one Java-level deadlock");
        if (idx < 0) {
            idx = 0;
        }
        String[] lines = dump.substring(idx).split("\\R");
        System.out.println("  --- 关键片段 ---");
        int printed = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            boolean keep = trimmed.startsWith("Found one Java-level deadlock")
                    || trimmed.startsWith("waiting to lock monitor")
                    || trimmed.startsWith("waiting on")
                    || trimmed.startsWith("- waiting to lock")
                    || trimmed.startsWith("- locked")
                    || trimmed.startsWith("- parking to wait for")
                    || trimmed.startsWith("Java stack information");
            if (keep) {
                System.out.println("  | " + trimmed);
                if (++printed >= 24) {
                    break;
                }
            }
        }
        System.out.println(">> 面试点: jstack/jcmd 打印的是【线程持有哪些锁、等待哪把锁】, \"waiting to lock\" 即等待入口,");
        System.out.println("   \"locked\" 即已持有; 两组一对照就能还原 A 等 B、B 等 A 的环路\n");
    }

    /** 优先 jcmd, 失败回退 jstack; 都不可用返回 null */
    static String runDumpTool(long pid) throws Exception {
        String javaHome = System.getProperty("java.home");
        String[] candidates = {javaHome + "/bin/jcmd", javaHome + "/bin/jstack"};
        for (String tool : candidates) {
            java.io.File f = new java.io.File(tool);
            if (!f.canExecute()) {
                continue;
            }
            List<String> cmd = tool.endsWith("jcmd")
                    ? java.util.Arrays.asList(tool, String.valueOf(pid), "Thread.print")
                    : java.util.Arrays.asList(tool, String.valueOf(pid));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                continue;
            }
            if (p.exitValue() == 0 && sb.length() > 0) {
                System.out.println("  使用工具: " + tool);
                return sb.toString();
            }
        }
        return null;
    }

    // ========== 实验4: 预防手段一 —— 固定加锁顺序 ==========

    static void step4_fixedLockOrder() throws Exception {
        System.out.println("=== 实验4: 预防手段一 —— 全局固定加锁顺序 ===");
        Table a = new Table("表A");
        Table b = new Table("表B");
        // 全局顺序: 与线程无关, 所有线程都先锁 first 再锁 second
        Table first = (System.identityHashCode(a) <= System.identityHashCode(b)) ? a : b;
        Table second = (first == a) ? b : a;
        System.out.println("  全局顺序: 先 " + first.name + " 后 " + second.name
                + " (由 identityHashCode 决定, 与线程无关)");

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger done = new AtomicInteger();
        Runnable task = () -> {
            try {
                barrier.await();
                synchronized (first) {
                    Thread.sleep(80);
                    synchronized (second) {
                        first.rows++;
                    }
                }
                done.incrementAndGet();
            } catch (Exception ignored) {
                // 演示代码: 忽略中断
            }
        };
        Thread t1 = new Thread(task, "T1");
        Thread t2 = new Thread(task, "T2");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        t1.join(3000);
        t2.join(3000);
        System.out.println("  线程 id: T1=" + t1.getId() + ", T2=" + t2.getId()
                + " (id 只用于观察先后, 不参与加锁顺序决策)");
        System.out.println("  反例: 若拿 Thread.getId() 当排序依据(T1.id < T2.id -> T1 先锁 表A, T2 先锁 表B),"
                + " 就退回了实验1 的交叉加锁, 必然死锁;");
        System.out.println("  期望: 2 个线程全部完成, 无死锁 -> 实际: 完成 " + done.get() + " 个");
        System.out.println(">> 面试点: 固定加锁顺序消除【循环等待】; 注意顺序必须是全局一致的,");
        System.out.println("   绝不能按 Thread.getId() 这类线程私有信息决定顺序, 否则换个线程顺序就变了\n");
    }

    // ========== 实验5: 预防手段二 —— tryLock 带超时 + 降级 ==========

    static void step5_tryLockTimeout() throws Exception {
        System.out.println("=== 实验5: 预防手段二 —— tryLock 超时, 失败走降级 ===");
        ReentrantLock l1 = new ReentrantLock();
        ReentrantLock l2 = new ReentrantLock();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger degraded = new AtomicInteger();

        // 线程1: 长时间持有 l1, 制造竞争
        Thread holder = new Thread(() -> {
            l1.lock();
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
                // 演示代码: 忽略中断
            } finally {
                l1.unlock();
            }
        }, "H-持锁300ms");
        holder.setDaemon(true);
        holder.start();
        Thread.sleep(50);

        // 线程2: 带超时抢锁, 抢不到就降级
        Thread worker = new Thread(() -> {
            boolean gotL1 = false;
            try {
                gotL1 = l1.tryLock(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                // 演示代码: 忽略中断
            }
            if (!gotL1) {
                degraded.incrementAndGet();
                System.out.println("  [降级] tryLock(100ms) 返回 false, 放弃本次操作, 走补偿/重试路径");
                // 降级后重试: 等持锁线程让出锁再试一次
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    // 演示代码: 忽略中断
                }
                try {
                    gotL1 = l1.tryLock(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // 演示代码: 忽略中断
                }
                if (!gotL1) {
                    System.out.println("  [降级] 重试仍失败, 本次操作放弃");
                    return;
                }
                System.out.println("  [重试] 退避后重新 tryLock 成功");
            }
            try {
                boolean gotL2 = l2.tryLock(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (gotL2) {
                    success.incrementAndGet();
                    l2.unlock();
                } else {
                    degraded.incrementAndGet();
                }
            } catch (InterruptedException ignored) {
                // 演示代码: 忽略中断
            } finally {
                l1.unlock();
            }
        }, "W-超时降级");
        worker.setDaemon(true);
        worker.start();
        worker.join(3000);
        holder.join(3000);

        System.out.println("  期望: 1 次成功 + 1 次降级(超时) -> 实际: 成功 " + success.get()
                + " 次, 降级 " + degraded.get() + " 次");
        System.out.println(">> 面试点: tryLock(timeout) 把【无限等待的不可恢复死锁】变成【可捕获的超时失败],");
        System.out.println("   再由业务层重试或降级; 前提是业务允许失败 —— 资金扣减这类操作要配合幂等\n");
    }

    // ========== 实验6: 预防手段三 —— 消除嵌套锁 ==========

    static void step6_avoidNestedLock() throws Exception {
        System.out.println("=== 实验6: 预防手段三 —— 合并临界区, 消除嵌套锁 ===");
        Object globalLock = new Object();
        AtomicInteger done = new AtomicInteger();
        Runnable task = () -> {
            synchronized (globalLock) {   // 只有一把锁, 不存在加锁环路
                try {
                    Thread.sleep(80);
                } catch (InterruptedException ignored) {
                    // 演示代码: 忽略中断
                }
                done.incrementAndGet();
            }
        };
        Thread t1 = new Thread(task, "T1");
        Thread t2 = new Thread(task, "T2");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        t1.join(3000);
        t2.join(3000);
        System.out.println("  期望: 2 个线程全部完成 -> 实际: 完成 " + done.get() + " 个");
        System.out.println(">> 面试点: 消除【持有并等待】—— 把需要两把锁的操作合并到一个临界区;");
        System.out.println("   代价是并发度下降, 数据量不大时通常比加锁顺序更划算\n");
    }

    // ========== 工具方法 ==========

    static String describe(LockInfo lock) {
        if (lock == null) {
            return "无(未在等待锁)";
        }
        String name = LOCK_NAMES.get(lock.getIdentityHashCode());
        return lock.getClass().getSimpleName() + "@" + Integer.toHexString(lock.getIdentityHashCode())
                + (name == null ? "" : "(" + name + ")");
    }

    static String describeMonitors(LockInfo[] monitors) {
        if (monitors == null || monitors.length == 0) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (LockInfo m : monitors) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(describe(m));
        }
        return sb.toString();
    }
}
