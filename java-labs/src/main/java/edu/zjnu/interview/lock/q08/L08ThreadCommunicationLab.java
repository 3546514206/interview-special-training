package edu.zjnu.interview.lock.q08;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.locks.LockSupport;

/**
 * 线程通信方法实验: wait/notify、join、sleep vs wait、interrupt、park/unpark
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q08.L08ThreadCommunicationLab
 *
 * 方法分三组:
 * 1. Object 监视器方法: wait / notify / notifyAll
 *    - 依赖对象的 monitor, 必须在 synchronized 内调用
 *    - 操作的是该对象的 wait set
 * 2. Thread 方法: start / join / sleep / interrupt / isInterrupted / Thread.interrupted()
 *    - join 内部也是 wait/notify; sleep 不释放锁; interrupt 只置标志或唤醒阻塞
 * 3. LockSupport: park / unpark
 *    - 不依赖 monitor, 靠许可(permit)工作, 可以精确唤醒指定线程
 *
 * 每个线程都在构造时指定了名字, 输出统一用 Thread.currentThread().getName() 打印,
 * 这样并发输出能直接看出是哪个线程在说话, jstack 里也能对上号.
 *
 * 验证点:
 * 1. wait 必须在 synchronized 内调用, 会释放锁; notifyAll 唤醒后还要重新抢锁
 * 2. join 的本质是等待目标线程终止 (内部就是 wait/notify)
 * 3. sleep 不释放锁, wait 释放锁
 * 4. interrupt 唤醒阻塞线程, 抛 InterruptedException 后中断标志被清除
 * 5. park/unpark 是许可制, 许可可以先于 park 发放
 */
public class L08ThreadCommunicationLab {

    // LOCK 是普通的 Object, 这里只把它当作 monitor 使用:
    // synchronized (LOCK) 竞争它的 monitor; LOCK.wait()/notifyAll() 操作它的 wait set
    static final Object LOCK = new Object();
    static final Queue<String> QUEUE = new LinkedList<String>();

    public static void main(String[] args) throws Exception {
        step1_waitNotify();
        step2_join();
        step3_sleepVsWait();
        step4_interrupt();
        step5_parkUnpark();
    }

    // ========== 实验1: wait / notifyAll ==========
    static void step1_waitNotify() throws Exception {
        System.out.println("=== 实验1: wait / notifyAll (生产者-消费者) ===");

        // wait() 不是"等锁", 而是"释放锁并等通知". 它在 JVM 里原子地做三件事:
        //   1. 校验当前线程持有 LOCK 的 monitor; 没有就抛 IllegalMonitorStateException
        //   2. 把当前线程加入 LOCK 的 wait set, 线程状态 RUNNABLE -> WAITING
        //   3. 释放 monitor, 让其他线程能进入 synchronized (LOCK) 修改条件
        // 返回前还要重新抢到 monitor; 返回后条件不一定成立, 所以外层用 while 重新判断
        try {
            LOCK.wait(); // 这里不在 synchronized (LOCK) 里, 第 1 步就失败
        } catch (IllegalMonitorStateException e) {
            System.out.println("[main] 不持锁直接 wait -> IllegalMonitorStateException");
        }

        // new Thread(Runnable, String): 第二个参数是线程名, 日志和 jstack 都用它标识线程
        Thread consumer = new Thread(new Runnable() {
            @Override
            public void run() {
                // 进入 synchronized 时获取 LOCK 的 monitor; 正常退出或抛异常都会释放
                synchronized (LOCK) {
                    while (QUEUE.isEmpty()) { // 用 while 防虚假唤醒, 被唤醒后重新检查条件
                        try {
                            System.out.println("[" + Thread.currentThread().getName()
                                    + "] 队列空, wait 并释放锁");
                            // 调用前已持有 LOCK; 调用后锁被释放, 线程进入 WAITING,
                            // 直到被 producer 唤醒并重新抢到 LOCK 才从 wait() 返回
                            LOCK.wait();
                        } catch (InterruptedException e) {
                            // 阻塞在 wait() 时被 interrupt: 线程被移出 wait set,
                            // 重新抢到 monitor 后抛 InterruptedException, 同时中断标志被清除;
                            // 这里恢复标志, 把中断信号交给上层处理
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    System.out.println("[" + Thread.currentThread().getName()
                            + "] 取到 " + QUEUE.poll());
                    // notifyAll(): 把 LOCK wait set 里的所有线程移出, 让它们重新竞争 monitor.
                    // 它不释放锁, 也不把锁交给谁; 被唤醒的线程只是从 WAITING 变为可运行/阻塞.
                    // 调用者必须持有 monitor, 否则 IllegalMonitorStateException.
                    // 相比 notify() 只随机唤醒一个, notifyAll() 不会因为唤醒错对象而漏掉该醒的线程
                    LOCK.notifyAll();
                }
            }
        }, "consumer");

        Thread producer = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (LOCK) {
                    QUEUE.add("订单-1");
                    System.out.println("[" + Thread.currentThread().getName()
                            + "] 放入 订单-1, notifyAll");
                    // 此时 consumer 在 LOCK 的 wait set 里; notifyAll 只是通知条件可能变了,
                    // consumer 仍然要参与 monitor 竞争才能继续执行
                    LOCK.notifyAll();
                }
            }
        }, "producer");

        // start(): JVM 创建新线程并调度 run(); 立即返回, 不等待 run() 结束.
        // 直接调用 run() 不会开新线程, 只会在当前线程里顺序执行
        consumer.start();
        Thread.sleep(100); // sleep: 当前线程 TIMED_WAITING, 不释放任何 monitor; 这里保证 consumer 先进 wait
        producer.start();
        // join(): 当前线程阻塞, 直到目标线程终止. 内部是 synchronized(目标线程) + 目标线程.wait(),
        // 线程终止时 JVM 会对 Thread 对象调用 notifyAll
        consumer.join();
        producer.join();
        System.out.println(">> wait 必须配合 while 判断; 被唤醒后要重新抢锁才能从 wait 返回\n");
    }

    // ========== 实验2: join ==========
    static void step2_join() throws Exception {
        System.out.println("=== 实验2: join ===");
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    System.out.println("[" + Thread.currentThread().getName()
                            + "] 开始, 准备睡 300ms");
                    // sleep(300): 当前线程进入 TIMED_WAITING; 不释放任何 monitor; 被 interrupt 会抛异常
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    // InterruptedException 抛出时中断标志已被清除, 恢复它以免上层丢失中断信号
                    Thread.currentThread().interrupt();
                }
                System.out.println("[" + Thread.currentThread().getName() + "] 执行结束");
            }
        }, "worker");

        long start = System.currentTimeMillis();
        worker.start(); // 启动 worker; 主线程从这里继续往下走, 不等待 run() 结束
        // join(): 主线程进入 WAITING, 直到 worker 终止.
        // JDK 内部等价于: synchronized (worker) { while (worker.isAlive()) worker.wait(); }
        // worker 结束时 JVM 对 Thread 对象 notifyAll, 主线程被唤醒后继续
        worker.join();
        long cost = System.currentTimeMillis() - start;
        System.out.println("[main] join 返回, 耗时 " + cost + "ms (worker 睡 300ms)");
        System.out.println(">> join 本质是 wait 目标线程对象, 线程终止时被 notifyAll\n");
    }

    // ========== 实验3: sleep 不释放锁, wait 释放锁 ==========
    static void step3_sleepVsWait() throws Exception {
        System.out.println("=== 实验3: sleep 不释放锁, wait 释放锁 ===");

        final Object lock = new Object();
        Thread sleeper = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    System.out.println("[" + Thread.currentThread().getName()
                            + "] 持锁并 sleep 300ms");
                    try {
                        // sleep 只让出 CPU: 线程 TIMED_WAITING, 但已经持有的 lock 不会释放,
                        // 所以 sleepWaiter 只能一直阻塞在 synchronized 入口
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "sleep-holder");
        Thread sleepWaiter = new Thread(new Runnable() {
            @Override
            public void run() {
                long start = System.currentTimeMillis();
                // 抢 lock 的 monitor; 抢不到时线程状态是 BLOCKED, 不是 WAITING
                synchronized (lock) {
                    long cost = System.currentTimeMillis() - start;
                    System.out.println("[" + Thread.currentThread().getName()
                            + "] 拿到锁, 等待了 " + cost + "ms");
                }
            }
        }, "sleep-waiter");

        sleeper.start();
        Thread.sleep(50); // 让 sleeper 先拿到 lock
        sleepWaiter.start();
        sleeper.join();
        sleepWaiter.join();

        final Object lock2 = new Object();
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (lock2) {
                    System.out.println("[" + Thread.currentThread().getName()
                            + "] 持锁并 wait 300ms");
                    try {
                        // wait(300) 与 wait() 一样会释放 lock2, 区别是多了超时:
                        // 被 notify 唤醒、超时、被 interrupt 三种情况都会返回;
                        // 返回前重新抢回 lock2, 且返回值不告诉你到底是哪种原因
                        lock2.wait(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "wait-holder");
        Thread waitWaiter = new Thread(new Runnable() {
            @Override
            public void run() {
                long start = System.currentTimeMillis();
                // waiter 处于 wait 时已经释放 lock2, 所以这里几乎立即能拿到锁
                synchronized (lock2) {
                    long cost = System.currentTimeMillis() - start;
                    System.out.println("[" + Thread.currentThread().getName()
                            + "] 在 wait 期间拿到锁, 等待了 " + cost + "ms");
                }
            }
        }, "wait-waiter");

        waiter.start();
        Thread.sleep(50);
        waitWaiter.start();
        waiter.join(); // join 的语义见实验2: 阻塞到目标线程终止
        waitWaiter.join();
        System.out.println(">> sleep 持锁不放(别人等约 250ms); wait 释放锁(别人几乎立即进入)\n");
    }

    // ========== 实验4: interrupt ==========
    static void step4_interrupt() throws Exception {
        System.out.println("=== 实验4: interrupt ===");
        Thread sleeper = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    System.out.println("[" + Thread.currentThread().getName() + "] sleep 10s");
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    // 阻塞在 sleep/wait/join 的线程被 interrupt 时:
                    // JVM 立即唤醒它并抛 InterruptedException, 抛之前把中断标志清零
                    System.out.println("[" + Thread.currentThread().getName()
                            + "] 被 interrupt 唤醒, 中断标志 = "
                            + Thread.currentThread().isInterrupted());
                }
            }
        }, "sleeper");

        sleeper.start();
        Thread.sleep(100);
        // interrupt() 本质只做一件事: 把目标线程的中断标志置为 true.
        // 如果目标线程正阻塞在 sleep/wait/join: 立即唤醒并抛 InterruptedException(标志随后清零).
        // 如果目标线程正在运行: 只置标志, 不会打断执行, 需要代码自己检查标志.
        // 如果目标线程阻塞在 park(): park 返回, 但不抛异常, 标志保持 true.
        // 如果目标线程阻塞在 synchronized 入口(BLOCKED): 不会唤醒, 只置标志.
        // interrupt 不是强制杀死线程, 线程是否退出由代码决定
        sleeper.interrupt();
        sleeper.join(); // 等 sleeper 处理完中断并终止
        System.out.println(">> 抛 InterruptedException 时中断标志已被清除, 需要自己决定是否恢复");

        Thread flag = new Thread(new Runnable() {
            @Override
            public void run() {
                // 给自己置中断标志; 它不会打断当前正在执行的代码
                Thread.currentThread().interrupt();
                // isInterrupted(): 实例方法, 只读取标志, 不清除
                System.out.println("[" + Thread.currentThread().getName()
                        + "] interrupt() 后 isInterrupted = "
                        + Thread.currentThread().isInterrupted());
                // Thread.interrupted(): 静态方法, 作用于当前线程, 读取并清除标志
                System.out.println("[" + Thread.currentThread().getName()
                        + "] Thread.interrupted() = " + Thread.interrupted()
                        + " (读取并清除标志)");
                // 上一步已经清除, 所以这里是 false
                System.out.println("[" + Thread.currentThread().getName()
                        + "] 再 isInterrupted = " + Thread.currentThread().isInterrupted());
            }
        }, "flag-thread");
        flag.start();
        flag.join();
        System.out.println(">> interrupt 只是设置标志 / 唤醒阻塞, 不是强制杀死线程\n");
    }

    // ========== 实验5: park / unpark ==========
    static void step5_parkUnpark() throws Exception {
        System.out.println("=== 实验5: LockSupport.park / unpark (许可制) ===");

        // unpark(t): 给线程 t 发放 1 个许可(permit). 许可最多存 1 个, 不会累加.
        //   如果 t 正阻塞在 park(): 立即唤醒它;
        //   如果 t 还没 park: 许可先存着, 下一次 park() 直接消耗并立即返回.
        // park(): 没有许可时当前线程进入 WAITING, 不依赖 monitor, 不需要 synchronized;
        //   可能被 unpark(t)、interrupt() 或虚假唤醒返回.
        // 被 interrupt 唤醒时 park 不抛异常, 但中断标志保持 true(与 wait/sleep 不同)
        LockSupport.unpark(Thread.currentThread());
        LockSupport.park(); // 许可已经被提前发放, 所以这里立即返回
        System.out.println("[main] 先 unpark 再 park, park 立即返回");

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("[" + Thread.currentThread().getName() + "] park");
                // worker 没有许可, 进入 WAITING, 直到主线程 unpark 它
                LockSupport.park();
                System.out.println("[" + Thread.currentThread().getName() + "] 被 unpark 唤醒");
            }
        }, "worker");
        worker.start();
        Thread.sleep(100); // 等 worker 进入 park
        // unpark 可以精确指定线程; wait/notify 只能操作整个 wait set, 无法指定唤醒哪一个
        LockSupport.unpark(worker);
        worker.join();
        System.out.println(">> 与 wait/notify 不同: 不需要先持锁, 且能精确唤醒指定线程\n");
    }
}
