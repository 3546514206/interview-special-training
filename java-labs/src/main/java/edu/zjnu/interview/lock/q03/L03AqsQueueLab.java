package edu.zjnu.interview.lock.q03;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 锁专题 Lab 3：AQS 同步器框架、可重入锁、公平与非公平、Condition 条件队列。
 *
 * <p>运行命令（JDK 8）：
 * <pre>
 * mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q03.L03AqsQueueLab
 * </pre>
 *
 * <p>验证面试点：
 * <ol>
 *   <li>继承 AQS 手写一个可重入互斥锁（tryAcquire / tryRelease 完整实现）；</li>
 *   <li>反射读出 ReentrantLock / Semaphore / CountDownLatch 内部 AQS 的 state，并说明各自语义；</li>
 *   <li>JDK 8 源码中 acquire / release / unparkSuccessor 的关键路径（原文引用）；</li>
 *   <li>公平锁与非公平锁：hasQueuedPredecessors 的作用与获取顺序；</li>
 *   <li>Condition 条件队列：await / signal / signalAll，等待期间被中断的响应；</li>
 *   <li>AQS 队列节点的 waitStatus 四值流转与实机观察；</li>
 *   <li>JDK 8 的锁升级路径：无锁 → 偏向 → 轻量级 → 重量级。</li>
 * </ol>
 */
public class L03AqsQueueLab {

    public static void main(String[] args) throws Exception {
        step1_customAqsLock();
        step2_reflectAqsState();
        step3_jdk8SourceTrace();
        step4_fairVsUnfair();
        step5_condition();
        step6_nodeStatus();
    }

    // ------------------------------------------------------------------
    // 实验 1：继承 AQS 手写可重入锁
    // ------------------------------------------------------------------

    /**
     * 基于 AQS 手写的可重入互斥锁。
     * <p>state 的语义：对齐 JDK 的 ReentrantLock —— state == 0 表示未加锁，state == n 表示被持有 n 次。
     * <p>持有者线程记录在父类 AbstractOwnableSynchronizer 的 exclusiveOwnerThread 字段里。
     */
    static final class MyReentrantLock extends AbstractQueuedSynchronizer implements Lock {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean tryAcquire(int acquires) {
            Thread current = Thread.currentThread();
            if (getState() == 0) {
                // 无人持有：CAS 把 state 从 0 改成 1，成功即获得锁
                if (compareAndSetState(0, acquires)) {
                    setExclusiveOwnerThread(current);
                    return true;
                }
            } else if (getExclusiveOwnerThread() == current) {
                // 可重入：持有者自己再加一次。持有者是唯一能修改 state 的线程，这里不需要 CAS
                int c = getState();
                if (c + acquires > Integer.MAX_VALUE) {
                    throw new Error("Maximum lock count exceeded");
                }
                setState(c + acquires);
                return true;
            }
            return false;
        }

        @Override
        protected boolean tryRelease(int releases) {
            if (getExclusiveOwnerThread() != Thread.currentThread()) {
                throw new IllegalMonitorStateException();
            }
            int c = getState() - releases;
            boolean free = (c == 0);
            if (free) {
                setExclusiveOwnerThread(null);
            }
            setState(c);
            // 返回 true 表示「完全释放」，此时由 AQS.release() 调用 unparkSuccessor(head) 唤醒后继线程。
            // 注意：唤醒后继不是 tryRelease 的职责——JDK 8 里唤醒由 AQS 的 release() 统一完成。
            return free;
        }

        @Override
        protected boolean isHeldExclusively() {
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        @Override
        public void lock() {
            acquire(1);
        }

        @Override
        public void unlock() {
            release(1);
        }

        @Override
        public boolean tryLock() {
            return tryAcquire(1);
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            acquireInterruptibly(1);
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            return tryAcquireNanos(1, unit.toNanos(time));
        }

        @Override
        public Condition newCondition() {
            return new ConditionObject();
        }

        /** 观测用：当前 state。 */
        int holdCount() {
            return getState();
        }
    }

    private static void step1_customAqsLock() throws InterruptedException {
        System.out.println("=== 实验1: 继承 AQS 手写可重入锁——互斥、可重入、非持有者解锁报错 ===");

        final MyReentrantLock lock = new MyReentrantLock();

        // 1.1 互斥性：8 个线程各累加 10000 次，最终结果必须等于 80000
        final int threads = 8;
        final int loops = 10_000;
        final int[] shared = new int[1];
        final AtomicInteger inside = new AtomicInteger(0);
        final AtomicInteger maxInside = new AtomicInteger(0);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < loops; j++) {
                        lock.lock();
                        try {
                            int cur = inside.incrementAndGet();
                            maxInside.accumulateAndGet(cur, Math::max);
                            shared[0]++;
                            inside.decrementAndGet();
                        } finally {
                            lock.unlock();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "my-lock-" + i).start();
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);

        System.out.println("8 线程各加 " + loops + " 次，最终值 = " + shared[0] + "（期望 " + (threads * loops) + "）");
        System.out.println("临界区同时进入的最大线程数 = " + maxInside.get() + "（期望 1）");
        check("自定义锁保证互斥", 1, maxInside.get());
        check("自定义锁保证计数不丢失", threads * loops, shared[0]);

        // 1.2 可重入：同一线程连续加锁两次，state 应为 2
        lock.lock();
        lock.lock();
        System.out.println("同一线程加锁两次后 state = " + lock.holdCount() + "（期望 2）");
        check("自定义锁可重入", 2, lock.holdCount());
        lock.unlock();
        System.out.println("解锁一次后 state = " + lock.holdCount() + "（期望 1）");
        lock.unlock();
        System.out.println("解锁两次后 state = " + lock.holdCount() + "（期望 0）");
        check("重入计数正确递减", 0, lock.holdCount());

        // 1.3 非持有者解锁必须抛 IllegalMonitorStateException
        final String[] errorType = new String[1];
        Thread intruder = new Thread(() -> {
            try {
                lock.unlock();
                errorType[0] = "没有抛异常";
            } catch (IllegalMonitorStateException e) {
                errorType[0] = "IllegalMonitorStateException";
            }
        }, "intruder");
        intruder.start();
        intruder.join();
        System.out.println("非持有者线程调用 unlock() 的结果 = " + errorType[0] + "（期望 IllegalMonitorStateException）");
        check("非持有者解锁抛 IllegalMonitorStateException", "IllegalMonitorStateException", errorType[0]);

        System.out.println(">> 面试点: AQS 把「排队、阻塞、唤醒」做完了，子类只需要回答两个问题——"
                + "「能不能拿到锁」(tryAcquire) 和「锁是否完全释放」(tryRelease)；"
                + "state 是唯一被 AQS 原子维护的状态量，其它语义都编码在这个 int 里。");
    }

    // ------------------------------------------------------------------
    // 实验 2：反射读出 JDK 各同步器的 AQS state
    // ------------------------------------------------------------------

    private static void step2_reflectAqsState() throws Exception {
        System.out.println("=== 实验2: ReentrantLock / Semaphore / CountDownLatch 的 AQS state 各有不同语义 ===");

        // 2.1 ReentrantLock：state = 持有次数
        ReentrantLock reentrantLock = new ReentrantLock();
        Object rlSync = InternalField.readObject(reentrantLock, ReentrantLock.class, "sync");
        System.out.println("ReentrantLock 的 sync 类型 = " + rlSync.getClass().getName());
        System.out.println("  未加锁时 state = " + InternalField.readInt(rlSync, AbstractQueuedSynchronizer.class, "state")
                + "（语义：0 = 未加锁）");
        reentrantLock.lock();
        System.out.println("  加锁一次后 state = " + InternalField.readInt(rlSync, AbstractQueuedSynchronizer.class, "state")
                + "（语义：n = 被持有 n 次）");
        reentrantLock.lock();
        System.out.println("  重入一次后 state = " + InternalField.readInt(rlSync, AbstractQueuedSynchronizer.class, "state"));
        reentrantLock.unlock();
        reentrantLock.unlock();
        System.out.println("  解锁两次后 state = " + InternalField.readInt(rlSync, AbstractQueuedSynchronizer.class, "state")
                + "（回到 0 = 未加锁）");
        check("ReentrantLock 的 state 表达持有次数", 0,
                InternalField.readInt(rlSync, AbstractQueuedSynchronizer.class, "state"));

        // 2.2 Semaphore：state = 可用许可数
        Semaphore semaphore = new Semaphore(3);
        Object semSync = InternalField.readObject(semaphore, Semaphore.class, "sync");
        System.out.println("Semaphore(3) 的 state = " + InternalField.readInt(semSync, AbstractQueuedSynchronizer.class, "state")
                + "（语义：可用许可数）");
        semaphore.acquire();
        System.out.println("  acquire() 一次后 state = " + InternalField.readInt(semSync, AbstractQueuedSynchronizer.class, "state"));
        semaphore.release();
        System.out.println("  release() 一次后 state = " + InternalField.readInt(semSync, AbstractQueuedSynchronizer.class, "state"));
        check("Semaphore 的 state 表达可用许可数", 3,
                InternalField.readInt(semSync, AbstractQueuedSynchronizer.class, "state"));

        // 2.3 CountDownLatch：state = 剩余计数
        CountDownLatch latch = new CountDownLatch(5);
        Object latchSync = InternalField.readObject(latch, CountDownLatch.class, "sync");
        System.out.println("CountDownLatch(5) 的 state = " + InternalField.readInt(latchSync, AbstractQueuedSynchronizer.class, "state")
                + "（语义：剩余计数）");
        latch.countDown();
        latch.countDown();
        System.out.println("  countDown() 两次后 state = " + InternalField.readInt(latchSync, AbstractQueuedSynchronizer.class, "state"));
        check("CountDownLatch 的 state 表达剩余计数", 3,
                InternalField.readInt(latchSync, AbstractQueuedSynchronizer.class, "state"));

        System.out.println(">> 面试点: AQS 的 state 只是一个 int，语义由子类自己定义——"
                + "ReentrantLock 定义成「持有次数」，Semaphore 定义成「可用许可数」，CountDownLatch 定义成「剩余计数」；"
                + "同一个 AQS 框架因此能支撑互斥锁、共享锁、信号量、闭锁。");
    }

    // ------------------------------------------------------------------
    // 实验 3：JDK 8 源码关键路径追踪
    // ------------------------------------------------------------------

    private static void step3_jdk8SourceTrace() {
        System.out.println("=== 实验3: JDK 8 中 AQS.acquire / release / unparkSuccessor 的源码关键路径（原文引用） ===");
        System.out.println("源码出处：JDK 8 的 src.zip，"
                + "java.util.concurrent.locks.AbstractQueuedSynchronizer.java");

        System.out.println("\n[1] 类声明：AQS 继承 AbstractOwnableSynchronizer，持有者记录在 exclusiveOwnerThread");
        System.out.println("public abstract class AbstractQueuedSynchronizer\n"
                + "    extends AbstractOwnableSynchronizer\n"
                + "    implements java.io.Serializable {");

        System.out.println("\n[2] acquire(int)：先试一次，失败才入队（acquireQueued / addWaiter）");
        System.out.println("public final void acquire(int arg) {\n"
                + "    if (!tryAcquire(arg) &&\n"
                + "        acquireQueued(addWaiter(Node.EXCLUSIVE), arg))\n"
                + "        selfInterrupt();\n"
                + "}");

        System.out.println("\n[3] release(int)：tryRelease 返回 true 后，由 AQS 负责唤醒后继");
        System.out.println("public final boolean release(int arg) {\n"
                + "    if (tryRelease(arg)) {\n"
                + "        Node h = head;\n"
                + "        if (h != null && h.waitStatus != 0)\n"
                + "            unparkSuccessor(h);\n"
                + "        return true;\n"
                + "    }\n"
                + "    return false;\n"
                + "}");

        System.out.println("\n[4] unparkSuccessor(Node)：JDK 8 的唤醒入口——清 SIGNAL 位后 unpark 后继");
        System.out.println("private void unparkSuccessor(Node node) {\n"
                + "    int ws = node.waitStatus;\n"
                + "    if (ws < 0)\n"
                + "        compareAndSetWaitStatus(node, ws, 0);\n"
                + "    Node s = node.next;\n"
                + "    if (s == null || s.waitStatus > 0) {\n"
                + "        s = null;\n"
                + "        for (Node t = tail; t != null && t != node; t = t.prev)\n"
                + "            if (t.waitStatus <= 0)\n"
                + "                s = t;\n"
                + "    }\n"
                + "    if (s != null)\n"
                + "        LockSupport.unpark(s.thread);\n"
                + "}");

        System.out.println("\n[5] 入队后自旋：前驱把 waitStatus 置为 SIGNAL，然后 park 自己");
        System.out.println("private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {\n"
                + "    int ws = pred.waitStatus;\n"
                + "    if (ws == Node.SIGNAL)\n"
                + "        return true;\n"
                + "    if (ws > 0) {\n"
                + "        do {\n"
                + "            node.prev = pred = pred.prev;\n"
                + "        } while (pred.waitStatus > 0);\n"
                + "        pred.next = node;\n"
                + "    } else {\n"
                + "        compareAndSetWaitStatus(pred, ws, Node.SIGNAL);\n"
                + "    }\n"
                + "    return false;\n"
                + "}");
        System.out.println("private final boolean parkAndCheckInterrupt() {\n"
                + "    LockSupport.park(this);\n"
                + "    return Thread.interrupted();\n"
                + "}");

        System.out.println("\n[6] 取消获取：节点自身置为 CANCELLED（节选）");
        System.out.println("private void cancelAcquire(Node node) {\n"
                + "    if (node == null)\n"
                + "        return;\n"
                + "    node.thread = null;\n"
                + "    ...\n"
                + "    node.waitStatus = Node.CANCELLED;\n"
                + "    ...\n"
                + "}");

        System.out.println("\n[7] Condition.await()：入条件队列 + fullyRelease 完全释放锁");
        System.out.println("public final void await() throws InterruptedException {\n"
                + "    if (Thread.interrupted())\n"
                + "        throw new InterruptedException();\n"
                + "    Node node = addConditionWaiter();\n"
                + "    int savedState = fullyRelease(node);\n"
                + "    int interruptMode = 0;\n"
                + "    while (!isOnSyncQueue(node)) {\n"
                + "        LockSupport.park(this);\n"
                + "        if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)\n"
                + "            break;\n"
                + "    }\n"
                + "    if (acquireQueued(node, savedState) && interruptMode != THROW_IE)\n"
                + "        interruptMode = REINTERRUPT;\n"
                + "    if (node.nextWaiter != null) // clean up if cancelled\n"
                + "        unlinkCancelledWaiters();\n"
                + "    if (interruptMode != 0)\n"
                + "        reportInterruptAfterWait(interruptMode);\n"
                + "}");

        System.out.println("\n[8] addConditionWaiter / fullyRelease（原文节选）：");
        System.out.println("private Node addConditionWaiter() {\n"
                + "    Node t = lastWaiter;\n"
                + "    if (t != null && t.waitStatus != Node.CONDITION) {\n"
                + "        unlinkCancelledWaiters();\n"
                + "        t = lastWaiter;\n"
                + "    }\n"
                + "    Node node = new Node(Thread.currentThread(), Node.CONDITION);\n"
                + "    if (t == null)\n"
                + "        firstWaiter = node;\n"
                + "    else\n"
                + "        t.nextWaiter = node;\n"
                + "    lastWaiter = node;\n"
                + "    return node;\n"
                + "}");
        System.out.println("private int fullyRelease(Node node) {\n"
                + "    boolean failed = true;\n"
                + "    try {\n"
                + "        int savedState = getState();\n"
                + "        if (release(savedState)) {\n"
                + "            failed = false;\n"
                + "            return savedState;\n"
                + "        } else {\n"
                + "            throw new IllegalMonitorStateException();\n"
                + "        }\n"
                + "    } finally {\n"
                + "        if (failed)\n"
                + "            node.waitStatus = Node.CANCELLED;\n"
                + "    }\n"
                + "}");
        System.out.println("读法：interruptMode = THROW_IE 表示「中断发生时节点还在条件队列、尚未被 signal」"
                + "→ 最终抛 InterruptedException；interruptMode = REINTERRUPT 表示「已经被 signal 之后才来的中断」"
                + "→ 不抛异常，改为重新设置中断标志。");

        System.out.println("\n[9] 唤醒入口：JDK 8 的 unparkSuccessor(Node) 是 AQS 的 private 方法，子类无法调用；"
                + "release() 在 tryRelease 成功后统一调用它。"
                + "网上流传的「在 tryRelease 里调用 unparkSuccessor」的手写模板编译不过。");

        System.out.println(">> 面试点: AQS 的骨架是固定的——tryAcquire 成功就拿锁，失败就入队；"
                + "release 时 tryRelease 改状态，返回 true 才唤醒后继；"
                + "面试时把 acquire / release / unparkSuccessor 这几段源码背下来，比背概念更能压住追问。");
    }

    // ------------------------------------------------------------------
    // 实验 4：公平锁 vs 非公平锁
    // ------------------------------------------------------------------

    private static void step4_fairVsUnfair() throws InterruptedException {
        System.out.println("=== 实验4: 公平锁按 FIFO 排队，非公平锁允许新线程插队（barging） ===");

        System.out.println("JDK 8 ReentrantLock 的公平 / 非公平 tryAcquire（源码原文）：");
        System.out.println("Sync.nonfairTryAcquire —— 不查前驱，可以插队：");
        System.out.println("final boolean nonfairTryAcquire(int acquires) {\n"
                + "    final Thread current = Thread.currentThread();\n"
                + "    int c = getState();\n"
                + "    if (c == 0) {\n"
                + "        if (compareAndSetState(0, acquires)) {\n"
                + "            setExclusiveOwnerThread(current);\n"
                + "            return true;\n"
                + "        }\n"
                + "    }\n"
                + "    else if (current == getExclusiveOwnerThread()) {\n"
                + "        int nextc = c + acquires;\n"
                + "        if (nextc < 0) // overflow\n"
                + "            throw new Error(\"Maximum lock count exceeded\");\n"
                + "        setState(nextc);\n"
                + "        return true;\n"
                + "    }\n"
                + "    return false;\n"
                + "}");
        System.out.println("FairSync.tryAcquire —— 用 hasQueuedPredecessors() 挡住插队：");
        System.out.println("protected final boolean tryAcquire(int acquires) {\n"
                + "    final Thread current = Thread.currentThread();\n"
                + "    int c = getState();\n"
                + "    if (c == 0) {\n"
                + "        if (!hasQueuedPredecessors() &&\n"
                + "            compareAndSetState(0, acquires)) {\n"
                + "            setExclusiveOwnerThread(current);\n"
                + "            return true;\n"
                + "        }\n"
                + "    }\n"
                + "    else if (current == getExclusiveOwnerThread()) {\n"
                + "        int nextc = c + acquires;\n"
                + "        if (nextc < 0)\n"
                + "            throw new Error(\"Maximum lock count exceeded\");\n"
                + "        setState(nextc);\n"
                + "        return true;\n"
                + "    }\n"
                + "    return false;\n"
                + "}");
        System.out.println("hasQueuedPredecessors()（AQS.java）：");
        System.out.println("public final boolean hasQueuedPredecessors() {\n"
                + "    Node t = tail;\n"
                + "    Node h = head;\n"
                + "    Node s;\n"
                + "    return h != t &&\n"
                + "        ((s = h.next) == null || s.thread != Thread.currentThread());\n"
                + "}");
        System.out.println("含义：队列中排在我前面、并且自己又不是队首的线程存在吗？存在的话我必须排队，不能插队。");

        // 4.1 插队实验：主线程持锁，A 线程排队，主线程释放后立刻重抢
        final int trials = 20;
        int fairBarge = 0;
        int nonfairBarge = 0;
        for (int i = 0; i < trials; i++) {
            if (bargeOnce(true)) {
                fairBarge++;
            }
            if (bargeOnce(false)) {
                nonfairBarge++;
            }
        }
        System.out.println("插队实验（每种模式 " + trials + " 次）：");
        System.out.println("  公平锁：主线程插到已排队线程前面的次数 = " + fairBarge + "（期望 0）");
        System.out.println("  非公平锁：主线程插到已排队线程前面的次数 = " + nonfairBarge + "（期望 > 0）");
        check("公平锁不插队", Boolean.TRUE, fairBarge == 0);
        check("非公平锁允许插队", Boolean.TRUE, nonfairBarge > 0);

        // 4.2 10 线程同时起跑，观察首轮获取锁的顺序
        List<Integer> fairOrder = observeFirstAcquireOrder(true, 10);
        List<Integer> nonfairOrder = observeFirstAcquireOrder(false, 10);
        System.out.println("10 线程同时起跑的首轮获取顺序：");
        System.out.println("  公平锁   = " + fairOrder);
        System.out.println("  非公平锁 = " + nonfairOrder);
        System.out.println("公平锁首轮顺序是否严格按线程启动顺序递增 = " + isStrictlyIncreasing(fairOrder)
                + "；非公平锁 = " + isStrictlyIncreasing(nonfairOrder));
        System.out.println("注意：公平锁保证的是「按进入 AQS 同步队列的先后」排队，"
                + "而不是按线程启动顺序——线程从 barrier 出来、走到 lock() 的时刻本来就可能不同。"
                + "要观察公平性的稳定差异，请看上面的插队实验（公平锁 0 次、非公平锁几乎次次插队）。");

        System.out.println(">> 面试点: hasQueuedPredecessors() 就是公平与非公平的开关——"
                + "公平锁在 tryAcquire 里多问一句「我前面还有没有人」，非公平锁不问；"
                + "代价是公平锁吞吐量明显更低（每次释放都要唤醒队首，还要额外做一次队列检查）。");
    }

    /**
     * 插队实验：主线程先持锁，线程 A 因拿不到锁而排队；主线程释放后立刻重新抢锁。
     *
     * @return true 表示主线程插到了 A 前面
     */
    private static boolean bargeOnce(boolean fair) throws InterruptedException {
        ReentrantLock lock = new ReentrantLock(fair);
        List<String> order = Collections.synchronizedList(new ArrayList<>());

        lock.lock();
        Thread a = new Thread(() -> {
            lock.lock();
            try {
                order.add("A");
            } finally {
                lock.unlock();
            }
        }, "barge-A");
        a.start();

        // 等 A 真正进入等待队列
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!lock.hasQueuedThreads() && System.nanoTime() < deadline) {
            Thread.yield();
        }

        lock.unlock();      // 释放锁，A 被唤醒
        lock.lock();        // 主线程立刻重抢
        try {
            order.add("main");
        } finally {
            lock.unlock();
        }
        a.join(5000);
        return "main".equals(order.isEmpty() ? "" : order.get(0));
    }

    /** 10 个线程同时起跑，记录每个线程第一次拿到锁的顺序。 */
    private static List<Integer> observeFirstAcquireOrder(boolean fair, int threads) throws InterruptedException {
        ReentrantLock lock = new ReentrantLock(fair);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int id = i;
            ts[i] = new Thread(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    return;
                }
                lock.lock();
                try {
                    order.add(id);
                } finally {
                    lock.unlock();
                }
            }, "order-" + i);
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        return new ArrayList<>(order);
    }

    private static boolean isStrictlyIncreasing(List<Integer> list) {
        for (int i = 1; i < list.size(); i++) {
            if (list.get(i) <= list.get(i - 1)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 实验 5：Condition 条件队列
    // ------------------------------------------------------------------

    private static void step5_condition() throws InterruptedException {
        System.out.println("=== 实验5: Condition 提供条件队列——await / signal / signalAll，等待期间可被中断 ===");

        // 5.1 await + signal：生产者-消费者
        ReentrantLock lock = new ReentrantLock();
        Condition notEmpty = lock.newCondition();
        Condition notFull = lock.newCondition();
        List<Integer> queue = Collections.synchronizedList(new ArrayList<>());
        List<Integer> consumed = Collections.synchronizedList(new ArrayList<>());
        final int total = 5;

        Thread producer = new Thread(() -> {
            for (int i = 0; i < total; i++) {
                lock.lock();
                try {
                    while (queue.size() == 1) {   // 必须用 while 而不是 if，防止虚假唤醒
                        notFull.await();
                    }
                    queue.add(i);
                    notEmpty.signal();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
            }
        }, "producer");

        Thread consumer = new Thread(() -> {
            for (int i = 0; i < total; i++) {
                lock.lock();
                try {
                    while (queue.isEmpty()) {
                        notEmpty.await();
                    }
                    consumed.add(queue.remove(0));
                    notFull.signal();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
            }
        }, "consumer");

        producer.start();
        consumer.start();
        producer.join(10_000);
        consumer.join(10_000);
        System.out.println("生产 " + total + " 个、消费 " + consumed.size() + " 个，消费序列 = " + consumed);
        check("await/signal 完成生产者-消费者且顺序正确", "[0, 1, 2, 3, 4]", consumed.toString());

        // 5.2 signalAll：唤醒同一个条件上的全部等待者
        final int waiters = 3;
        CountDownLatch allWoken = new CountDownLatch(waiters);
        CountDownLatch readyToWait = new CountDownLatch(waiters);
        ReentrantLock lock2 = new ReentrantLock();
        Condition cond = lock2.newCondition();
        for (int i = 0; i < waiters; i++) {
            new Thread(() -> {
                lock2.lock();
                try {
                    readyToWait.countDown();
                    cond.await();          // 三个线程都挂在这个条件上
                    allWoken.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock2.unlock();
                }
            }, "waiter-" + i).start();
        }
        readyToWait.await(5, TimeUnit.SECONDS);
        Thread.sleep(100);                 // 确保三个线程都已进入条件队列
        lock2.lock();
        try {
            cond.signalAll();             // 一次唤醒全部
        } finally {
            lock2.unlock();
        }
        boolean allAwakened = allWoken.await(5, TimeUnit.SECONDS);
        System.out.println("signalAll 后被唤醒的等待者数量 = " + (waiters - allWoken.getCount())
                + " / " + waiters + "（期望 " + waiters + "）");
        check("signalAll 唤醒全部等待者", Boolean.TRUE, allAwakened);

        // 5.3 等待期间被中断：await 抛 InterruptedException
        ReentrantLock lock3 = new ReentrantLock();
        Condition cond3 = lock3.newCondition();
        String[] caught = new String[1];
        CountDownLatch awaiting = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            lock3.lock();
            try {
                awaiting.countDown();
                cond3.await();
                caught[0] = "await 正常返回（没有抛异常）";
            } catch (InterruptedException e) {
                caught[0] = "InterruptedException（getMessage() = " + e.getMessage() + "）";
            } finally {
                lock3.unlock();
            }
        }, "interrupted-waiter");
        waiter.start();
        awaiting.await(5, TimeUnit.SECONDS);
        Thread.sleep(100);
        waiter.interrupt();
        waiter.join(5000);
        System.out.println("等待期间被中断的结果 = " + caught[0]);
        check("await 期间被中断抛 InterruptedException", Boolean.TRUE,
                caught[0] != null && caught[0].startsWith("InterruptedException"));

        System.out.println(">> 面试点: Condition 的 await 会「原子地」释放锁并进入条件队列，"
                + "signal 把一个等待者搬回同步队列；等待条件必须用 while 循环判断（防止虚假唤醒）；"
                + "await 期间被中断会抛 InterruptedException；"
                + "若中断发生在该线程已被 signal 之后，JDK 8 走的是 REINTERRUPT 分支——"
                + "重新设置中断标志而不是抛异常（见上文 [7] await() 的中断处理）。");
    }

    // ------------------------------------------------------------------
    // 实验 6：AQS 节点的状态流转
    // ------------------------------------------------------------------

    private static void step6_nodeStatus() throws Exception {
        System.out.println("=== 实验6: AQS 队列节点的状态流转——JDK 8 用 Node.waitStatus 四值枚举 ===");

        System.out.println("【JDK 8】Node 的状态常量（AQS.java，原文）：");
        System.out.println("static final int CANCELLED =  1;\n"
                + "static final int SIGNAL    = -1;\n"
                + "static final int CONDITION = -2;\n"
                + "static final int PROPAGATE = -3;");
        System.out.println("Node 用一个 volatile int waitStatus 字段记录状态：");
        System.out.println("  CANCELLED = 1   → 节点已取消（超时 / 中断后放弃获取）；");
        System.out.println("  SIGNAL    = -1  → 前驱承诺：后继 park 后需要被 unpark；");
        System.out.println("  CONDITION = -2  → 节点在条件队列上等待（await）；");
        System.out.println("  PROPAGATE = -3  → 共享模式下释放需要向后传播。");
        System.out.println("  流转：入队时 waitStatus = 0；后继要 park 前，前驱把自己置为 SIGNAL；"
                + "释放锁时 unparkSuccessor 把队首从负值 CAS 回 0 并 unpark 后继；"
                + "取消时节点自身置 CANCELLED；条件队列上的节点为 CONDITION，signal 后转回同步队列并 CAS 回 0。");
        System.out.println("注意：unparkSuccessor(Node) 是 AQS 的 private 方法，子类无法调用，"
                + "唤醒统一由 AQS.release() 完成。");

        // 实机观察：让一个线程在同步队列里 park，读出 head / tail 节点的 waitStatus
        if (!InternalField.available()) {
            System.out.println("\n反射观察队列节点不可用，跳过实机观察。");
        } else {
            final ReentrantLock lock = new ReentrantLock();
            Object sync = InternalField.readObject(lock, ReentrantLock.class, "sync");
            Class<?> nodeClass = Class.forName("java.util.concurrent.locks.AbstractQueuedSynchronizer$Node");

            lock.lock();      // 主线程持锁
            CountDownLatch queued = new CountDownLatch(1);
            Thread waiter = new Thread(() -> {
                queued.countDown();
                lock.lock();
                lock.unlock();
            }, "node-waiter");
            waiter.start();
            queued.await(5, TimeUnit.SECONDS);

            int headStatus = 0;
            int tailStatus = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            do {
                Object head = InternalField.readObject(sync, AbstractQueuedSynchronizer.class, "head");
                Object tail = InternalField.readObject(sync, AbstractQueuedSynchronizer.class, "tail");
                if (head != null) {
                    headStatus = InternalField.readInt(head, nodeClass, "waitStatus");
                }
                if (tail != null) {
                    tailStatus = InternalField.readInt(tail, nodeClass, "waitStatus");
                }
                if (headStatus == -1) {
                    break;
                }
                Thread.yield();
            } while (System.nanoTime() < deadline);

            System.out.println("\n实机观察：等待线程已进入同步队列");
            System.out.println("  head.waitStatus = " + headStatus + "（SIGNAL = -1：队首承诺唤醒后继）");
            System.out.println("  tail.waitStatus = " + tailStatus + "（0：正常等待；1 表示已取消）");
            check("队首已置 SIGNAL", -1, headStatus);
            check("排队节点未被取消", Boolean.TRUE, tailStatus <= 0);

            lock.unlock();
            waiter.join(5000);
        }

        System.out.println(">> 面试点: JDK 8 的 AQS 用 waitStatus 四值枚举表达节点状态，"
                + "唤醒入口是 release() → unparkSuccessor(Node)；"
                + "面试时被问到 SIGNAL / PROPAGATE 的流转，要能说清「前驱置 SIGNAL，承诺唤醒后继」这条主线。");

        System.out.println("\n【补充：JDK 8 的锁升级】");
        System.out.println("偏向锁在 JDK 8 默认开启（启动后约 4s 生效，可用 -XX:-UseBiasedLocking 关闭）；");
        System.out.println("锁升级路径是「无锁 → 偏向 → 轻量级 → 重量级」，验证见 Q04LockUpgradeLab。");
    }

    // ------------------------------------------------------------------
    // 反射工具
    // ------------------------------------------------------------------

    /**
     * 读取 JDK 内部私有字段的工具。
     * <p>JDK 8 没有模块系统，{@code Field.setAccessible(true)} 后可以直接读取私有字段。
     */
    static final class InternalField {

        private InternalField() {
        }

        static boolean available() {
            return true;
        }

        static int readInt(Object target, Class<?> owner, String fieldName) throws Exception {
            Field f = owner.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getInt(target);
        }

        static Object readObject(Object target, Class<?> owner, String fieldName) throws Exception {
            Field f = owner.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(target);
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
