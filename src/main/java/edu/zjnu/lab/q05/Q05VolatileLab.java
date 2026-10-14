package edu.zjnu.lab.q05;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Q5 · volatile 实验可见性 / 禁重排 / 不保证原子性
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.lab.q05.Q05VolatileLab
 *
 * 验证面试点:
 * 1. 可见性: 无 volatile 时读线程可能永远看不到 flag 变化 (JIT 把读提升出循环)
 * 2. 不保证原子性: volatile int 的 i++ 并发必丢数; AtomicInteger 才正确
 * 3. DCL 单例为什么必须 volatile: new 分 3 步, 重排后别的线程拿到"半成品"
 */
public class Q05VolatileLab {

    // 实验1: 把 flag 的 volatile 注掉再跑, 读线程大概率永远退不出循环
    static volatile boolean stop = false;

    // 实验2
    static volatile int vi = 0;
    static final AtomicInteger ai = new AtomicInteger();
    static int si = 0;
    static final Object lock = new Object();

    public static void main(String[] args) throws Exception {
        step1_visibility();
        step2_atomicity();
        step3_dcl();
    }

    static void step1_visibility() throws Exception {
        System.out.println("=== 实验1: 可见性 —— volatile flag 一改, 读线程立刻看到 ===");
        Thread reader = new Thread(() -> {
            long spin = 0;
            while (!stop) spin++; // 读 volatile: LoadLoad+LoadStore 屏障, 禁止把读缓存到寄存器
            System.out.println("读线程退出, 自旋了 " + spin + " 次");
        });
        reader.start();
        Thread.sleep(100);
        stop = true; // 写 volatile: StoreStore+StoreLoad 屏障, StoreLoad 最贵 (全同步点)
        reader.join(2000);
        System.out.println(">> 把上面 volatile 关键字删掉再跑: reader 可能永远不退出 (主存 vs 工作内存的经典演示)");
        System.out.println(">> 追问'StoreLoad 为什么最贵': 要清空写缓冲 + 等所有读完成\n");
    }

    static void step2_atomicity() throws Exception {
        System.out.println("=== 实验2: volatile 不保证原子性 (i++ = 读+加+写 三步) ===");
        int threads = 10, times = 100_000;
        run(threads, () -> {
            for (int i = 0; i < times; i++) vi++;
        });
        run(threads, () -> {
            for (int i = 0; i < times; i++) ai.incrementAndGet(); // CAS 循环, 原子
        });
        run(threads, () -> {
            for (int i = 0; i < times; i++) synchronized (lock) { si++; } // 互斥, 原子
        });
        System.out.printf("volatile int i++ : 期望 %d, 实际 %d  => 丢了 %d 次%n", threads * times, vi, threads * times - vi);
        System.out.printf("AtomicInteger    : 期望 %d, 实际 %d  => 精确%n", threads * times, ai.get());
        System.out.printf("synchronized     : 期望 %d, 实际 %d  => 精确%n", threads * times, si);
        System.out.println(">> 易错点: volatile 管'看得见', 不管'改得准'; i++ 这种复合操作必须 CAS 或加锁\n");
    }

    static void step3_dcl() {
        System.out.println("=== 实验3: DCL 单例, instance 为什么必须 volatile ===");
        System.out.println("new Singleton() 字节码层面是 3 步:");
        System.out.println("  1) 分配内存   2) 初始化对象   3) 引用赋值给 instance");
        System.out.println("  无 volatile 时 CPU/编译器可能重排成 1->3->2:");
        System.out.println("  线程A 执行到 3 (instance 已非 null 但对象没初始化), 线程B 第一次判 null 通过,");
        System.out.println("  直接 return -> 拿到半成品, 字段全是默认值!");
        System.out.println("  volatile 的 StoreStore 屏障保证: 写 instance 之前的初始化动作全部对其他线程可见。\n");
        Singleton s1 = Singleton.getInstance();
        Singleton s2 = Singleton.getInstance();
        System.out.println("两次 getInstance 是同一实例: " + (s1 == s2));
    }

    static void run(int threads, Runnable body) throws Exception {
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    body.run();
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();
    }

    static class Singleton {
        private static volatile Singleton instance; // 删掉 volatile 看注释理解风险

        static Singleton getInstance() {
            if (instance == null) {                    // 第一次检查 (无锁快路径)
                synchronized (Singleton.class) {
                    if (instance == null) {            // 第二次检查 (进锁后再确认)
                        instance = new Singleton();    // 非原子: 分配/初始化/赋值
                    }
                }
            }
            return instance;
        }
    }
}
