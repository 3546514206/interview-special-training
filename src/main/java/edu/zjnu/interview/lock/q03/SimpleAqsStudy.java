package edu.zjnu.interview.lock.q03;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.LockSupport;

/**
 * 简化版 AQS + 简化版可重入锁（教学版，只讲核心原理）
 *
 * <p>运行：
 * <pre>
 * export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home
 * mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q03.SimpleAqsStudy
 * </pre>
 *
 * <p>JDK 的 AQS 有两千多行，混着位图状态、共享模式、取消、超时、条件队列，
 * 很容易只记住「AQS 是框架」却说不清它到底干了什么。这里只留骨架：
 * <b>一个状态量 + 一个等待队列 + acquire/release 两个模板方法 + park/unpark</b>，
 * 再在骨架上加两个字段做成可重入锁。
 *
 * <p>本文件不是生产实现，砍掉了共享模式、公平/非公平、取消节点、中断响应、超时获取。
 */
public class SimpleAqsStudy {

    public static void main(String[] args) throws Exception {
        step1_mutexAndReentrant();
        step2_blockAndWakeup();
    }

    /**
     * 简化版 AQS 骨架。
     *
     * <p>核心就两件事：
     * <ul>
     *   <li><b>抢</b>：{@code tryAcquire} 里做一次 CAS，成功即拿到锁；</li>
     *   <li><b>等</b>：CAS 失败就把自己挂进队列并 {@code park}，等别人 {@code unpark} 叫醒。</li>
     * </ul>
     * 排队、阻塞、唤醒是所有锁共用的机制（框架的事）；
     * 「能不能拿」「算不算完全释放」是锁自己的语义（子类的事）——这两者正交，就是 AQS 的设计要点。
     */
    abstract static class SimpleAqs {

        /** 状态量，语义由子类定义（这里装持有次数）。volatile 只保证可见，不保证原子，所以改它必须走 CAS。 */
        private volatile int state;

        /** 用 JDK 公开的 AtomicIntegerFieldUpdater 做 CAS，作用在实例上，多个锁互不干扰。 */
        private static final AtomicIntegerFieldUpdater<SimpleAqs> STATE =
                AtomicIntegerFieldUpdater.newUpdater(SimpleAqs.class, "state");

        /** 保护队列指针的修改。 */
        private final Object queueLock = new Object();

        /** 哨兵头节点，next 指向第一个等待者。volatile 必需：等待线程靠它判断「是否轮到自己」，读到陈旧值会挂死。 */
        private volatile Node head = new Node(null);

        /** 队尾节点。 */
        private Node tail = head;

        /** 等待队列节点：谁在等、前驱、后继。 */
        static final class Node {
            Thread thread;
            Node prev;
            Node next;

            Node(Thread thread) {
                this.thread = thread;
            }
        }

        // ---------- 子类填这两个空 ----------

        /** 当前线程能不能拿到锁？靠 CAS 保证原子性——这是互斥的唯一来源。 */
        protected abstract boolean tryAcquire(int arg);

        /** 锁是不是<b>完全</b>释放了？是则框架去唤醒后继。 */
        protected abstract boolean tryRelease(int arg);

        // ---------- 框架提供的机制 ----------

        protected int getState() {
            return state;
        }

        protected void setState(int s) {
            state = s;
        }

        protected boolean compareAndSetState(int expect, int update) {
            return STATE.compareAndSet(this, expect, update);
        }

        /**
         * 加锁：先抢，抢不到才排队。
         * <p>绝大多数情况没有竞争，一次 CAS 就走完了；排队只是兜底路径。
         */
        public final void acquire(int arg) {
            if (!tryAcquire(arg)) {
                acquireQueued(arg);
            }
        }

        /** 排队等待：入队 → 反复尝试抢 → 抢不到就 park 让出 CPU。 */
        private void acquireQueued(int arg) {
            Node node = enqueue();
            for (;;) {
                // 队列是 FIFO，只有轮到自己（前驱已被摘掉）才值得尝试。
                // 这只是优化，真正的守门神仍是 tryAcquire 里的 CAS。
                if (node.prev == head && tryAcquire(arg)) {
                    setHead(node);   // 抢到了：升为新哨兵，并叫醒下一位
                    return;
                }
                // 被唤醒后要<b>重新抢</b>：唤醒只代表「可能有机会了」，不保证锁还空着。
                LockSupport.park(this);
            }
        }

        /** 入队：挂到队尾。 */
        private Node enqueue() {
            Node node = new Node(Thread.currentThread());
            synchronized (queueLock) {
                node.prev = tail;
                tail.next = node;
                tail = node;
            }
            return node;
        }

        /** 抢到锁后升为新哨兵头，并唤醒下一位——临界区是互斥的，下一位不叫醒就永远拿不到。 */
        private void setHead(Node node) {
            synchronized (queueLock) {
                node.thread = null;
                node.prev = null;
                head = node;
            }
            unparkSuccessor();
        }

        /** 释放：先改状态再唤醒。顺序反了的话，被唤醒的线程会 CAS 失败又睡回去。 */
        public final void release(int arg) {
            if (tryRelease(arg)) {
                unparkSuccessor();
            }
        }

        /** 唤醒队首等待者。 */
        private void unparkSuccessor() {
            synchronized (queueLock) {
                Node s = head.next;
                if (s != null && s.thread != null) {
                    LockSupport.unpark(s.thread);
                }
            }
        }

        /** 仅用于观察：队列里等着的线程数。 */
        int queueSize() {
            synchronized (queueLock) {
                int n = 0;
                for (Node p = head.next; p != null; p = p.next) n++;
                return n;
            }
        }
    }

    /**
     * 简化版可重入锁。
     *
     * <p>比一把普通互斥锁只多了两处：
     * <ul>
     *   <li>{@code owner} 字段：回答「锁现在是不是我持有的」；</li>
     *   <li>{@code state} 从 0/1 变成计数：记录持有了几次。</li>
     * </ul>
     * 排队、阻塞、唤醒一行没改——那些是框架的事，与锁的语义无关。
     * 这就是 AQS 设计的价值：改锁的语义不用碰并发控制机制。
     */
    static final class SimpleReentrantLock extends SimpleAqs {

        /** 持有者线程。volatile 保证释放后置 null 能被别的线程看到。 */
        private volatile Thread owner;

        @Override
        protected boolean tryAcquire(int arg) {
            Thread current = Thread.currentThread();
            if (owner == current) {
                // 已是持有者 → 重入，计数 +1。持有者是唯一能改 state 的线程，这里不用 CAS。
                setState(getState() + arg);
                return true;
            }
            if (compareAndSetState(0, arg)) {
                owner = current;   // 无人持有 → CAS 抢锁成功
                return true;
            }
            return false;          // 别人持有 → 抢不到，交给框架排队
        }

        @Override
        protected boolean tryRelease(int arg) {
            if (owner != Thread.currentThread()) {
                throw new IllegalMonitorStateException("当前线程不是锁的持有者");
            }
            int c = getState() - arg;
            // 顺序很关键：先清持有者，再放状态。
            // 若反着来（先 setState(0) 再 owner=null），别的线程可能在这中间抢到锁并把 owner 写成自己，
            // 随后这句 owner=null 就把「新主人」的持有者记录抹掉了——它解锁时会误判自己不是持有者，
            // 状态再也归不了零，锁就此永久卡死。JDK 的 ReentrantLock 就是这个顺序。
            if (c == 0) {
                owner = null;
            }
            setState(c);
            // 只有减到 0 才算「完全释放」：否则框架会提前唤醒后继，互斥被破坏。
            return c == 0;
        }

        public void lock() {
            acquire(1);
        }

        public void unlock() {
            release(1);
        }
    }

    /** 演示一：互斥 + 可重入 + 非持有者解锁报错。 */
    static void step1_mutexAndReentrant() throws Exception {
        System.out.println("=== 演示一: 互斥与可重入 ===");

        SimpleReentrantLock lock = new SimpleReentrantLock();

        // 可重入：同线程连加三次，计数 3 → 2 → 1 → 0
        lock.lock();
        lock.lock();
        lock.lock();
        System.out.println("同线程连续加锁 3 次后持有计数 = " + lock.getState() + "（期望 3）"
                + " => " + (lock.getState() == 3 ? "[PASS] 可重入" : "[FAIL]"));
        lock.unlock();
        lock.unlock();
        lock.unlock();
        System.out.println("全部解锁后持有计数 = " + lock.getState() + "（期望 0）"
                + " => " + (lock.getState() == 0 ? "[PASS] 计数正确" : "[FAIL]"));

        // 非持有者解锁必须报错
        try {
            lock.unlock();
            System.out.println("非持有者解锁 => [FAIL] 本应抛 IllegalMonitorStateException");
        } catch (IllegalMonitorStateException e) {
            System.out.println("非持有者解锁 => [PASS] 抛 IllegalMonitorStateException");
        }

        // 8 线程争抢：互斥成立，计数不丢
        AtomicInteger counter = new AtomicInteger();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        Thread[] ts = new Thread[8];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                for (int k = 0; k < 10_000; k++) {
                    lock.lock();
                    try {
                        int now = inside.incrementAndGet();
                        maxInside.accumulateAndGet(now, Math::max);
                        counter.incrementAndGet();
                        inside.decrementAndGet();
                    } finally {
                        lock.unlock();
                    }
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        System.out.println("8 线程各加锁 1 万次，期望计数 = 80000，实际 = " + counter.get()
                + " => " + (counter.get() == 80_000 ? "[PASS] 无丢失更新" : "[FAIL] 有丢失更新"));
        System.out.println("临界区同时进入的最大线程数 = " + maxInside.get() + "（期望 1）"
                + " => " + (maxInside.get() == 1 ? "[PASS] 互斥成立" : "[FAIL] 互斥被破坏"));

        System.out.println(">> 要点: 互斥的来源只有一处——tryAcquire 里那次 CAS；");
        System.out.println("   可重入 = owner 字段 + 持有计数，框架部分一行没改。\n");
    }

    /** 演示二：抢不到锁的线程被挂起，持有者释放后被叫醒。 */
    static void step2_blockAndWakeup() throws Exception {
        System.out.println("=== 演示二: 阻塞与唤醒 ===");

        SimpleReentrantLock lock = new SimpleReentrantLock();
        AtomicInteger order = new AtomicInteger();

        Thread a = new Thread(() -> {
            lock.lock();
            try {
                order.incrementAndGet();
                Thread.sleep(300);   // 持锁不放，让 B 只能排队
            } catch (InterruptedException ignored) {
            } finally {
                lock.unlock();       // 完全释放 → 框架唤醒队首 B
            }
        }, "A-持锁方");
        Thread b = new Thread(() -> {
            lock.lock();             // 锁被 A 持有 → 入队并 park
            try {
                order.incrementAndGet();
            } finally {
                lock.unlock();
            }
        }, "B-等待方");

        a.start();
        Thread.sleep(100);            // 确保 A 先拿到锁
        b.start();
        Thread.sleep(100);
        System.out.println("A 持锁期间 B 的状态 = " + b.getState() + "（期望 WAITING，即被 park 挂起）"
                + " => " + (b.getState() == Thread.State.WAITING ? "[PASS] 阻塞挂起" : "[FAIL]"));
        System.out.println("此刻队列中等待的线程数 = " + lock.queueSize() + "（期望 1）");

        a.join();
        b.join();
        System.out.println("A 释放后 B 拿到锁，进入顺序计数 = " + order.get() + "（期望 2）"
                + " => " + (order.get() == 2 ? "[PASS] 释放唤醒后继" : "[FAIL]"));
        System.out.println(">> 要点: 被唤醒的线程必须重新 CAS 抢锁——");
        System.out.println("   唤醒只代表「可能有机会了」，不保证锁还空着。");
    }
}
