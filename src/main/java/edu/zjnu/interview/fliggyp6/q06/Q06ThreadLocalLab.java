package edu.zjnu.interview.fliggyp6.q06;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Q6 · ThreadLocal 实验泄漏 / 串号 / 斐波那契散列
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.fliggyp6.q06.Q06ThreadLocalLab
 *
 * 验证面试点:
 * 1. 线程池不 remove => 下一个任务读到上一个任务的值 (数据串号, 生产事故级)
 * 2. key 是弱引用被 GC 后 value 还在 => 内存泄漏的真正形态
 * 3. hash 增量 0x61c88647 (黄金分割) 让散列自然均匀
 * 4. 标准写法: static final + try/finally remove, 线程隔离且不串号
 */
public class Q06ThreadLocalLab {

    static ThreadLocal<String> user = new ThreadLocal<>();
    static final ThreadLocal<String> CTX = new ThreadLocal<String>() {
        @Override
        protected String initialValue() {
            return "未登录"; // withInitial 的本质就是重写这个方法
        }
    };

    public static void main(String[] args) throws Exception {
        // step1_poolDirtyData();
         step2_leak();
        // step3_fibonacciHash();
        // step4_correctUsage();
    }

    // ========== 实验1: 线程池串号 ==========
    static void step1_poolDirtyData() throws Exception {
        System.out.println("=== 实验1: 线程池 + 不 remove => 串号 ===");
        ExecutorService pool = Executors.newSingleThreadExecutor(); // 核心线程永存活

        Runnable task1 = new Runnable() {            // 任务1: 登录态设置
            @Override
            public void run() {
                user.set("用户A-张三");
            }
        };
        pool.submit(task1);
        Thread.sleep(100);

        Runnable task2 = new Runnable() {            // 任务2: 完全没 set, 直接 get
            @Override
            public void run() {
                System.out.println("任务2 拿到的 user = " + user.get() + "  <-- 根本没 set 过, 读到了别人的!");
            }
        };
        pool.submit(task2);
        Thread.sleep(100);

        Runnable task3 = new Runnable() {            // 正确姿势: try/finally 里 remove
            @Override
            public void run() {
                user.remove();
            }
        };
        pool.submit(task3);
        Thread.sleep(100);
        pool.shutdown();
        System.out.println(">> 线程池的线程是复用的, ThreadLocalMap 挂在 Thread 上跟着线程活");
        System.out.println(">> 规范: try { tl.set(x); ... } finally { tl.remove(); }\n");
    }

    // ========== 实验2: 弱引用 key 被回收, value 泄漏 ==========
    static void step2_leak() throws Exception {
        System.out.println("=== 实验2: key 被 GC, value 却泄漏 ===");
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                ThreadLocal<byte[]> tl = new ThreadLocal<>();
                byte[] value = new byte[1024 * 1024]; // 1MB, 后面由 Entry.value 强引用

                WeakReference<ThreadLocal<byte[]>> keyRef = new WeakReference<ThreadLocal<byte[]>>(tl);
                WeakReference<byte[]> valueRef = new WeakReference<byte[]>(value);

                tl.set(value); // Entry.key 弱引用 tl, Entry.value 强引用 value
                tl = null;     // 外部不再强引用 ThreadLocal 对象
                value = null;  // 外部不再强引用 value

                System.gc();   // 触发 GC: 只剩 Entry 弱引用的 key 会被回收

                System.out.println("key(ThreadLocal) 还在吗? " + (keyRef.get() != null));
                System.out.println("value(1MB) 还在吗?     " + (valueRef.get() != null));
                // 预期 false / true: key 没了, value 仍被当前线程 map 里的 Entry 强引用
            }
        });
        worker.start();
        worker.join();
        System.out.println(">> key=null 但 value(1MB) 还在被 Entry 强引用 —— 线程不死它就不释放");
        System.out.println(">> 为什么 key 设计成弱引用: 至少让 ThreadLocal 本身能被回收, 并给 set/get 时的启发式清理一个机会");
        System.out.println(">> 但根治只有 remove()。追问'InheritableThreadLocal': 只在子线程【创建时】拷贝, 线程池复用失效, 要用阿里 TTL\n");
    }

    // ========== 实验3: 0x61c88647 黄金分割散列 ==========
    static void step3_fibonacciHash() {
        System.out.println("=== 实验3: threadLocalHashCode 增量 0x61c88647 (黄金分割数) ===");
        int hash = 0;
        int len = 16; // ThreadLocalMap 初始容量
        StringBuilder slots = new StringBuilder();
        for (int i = 0; i < len; i++) {
            hash += 0x61c88647; // 每生成一个 ThreadLocal, hash 加这个增量
            slots.append((hash & (len - 1))).append(" ");
        }
        System.out.println("16 个连续 ThreadLocal 的桶分布: " + slots);
        System.out.println(">> 2 的幂容量 + 黄金分割增量 => 天然均匀铺满所有桶, 几乎不冲突 (HashMap 的扰动方案之外的另一种思路)");
    }

    // ========== 实验4: 标准写法 (static final + try/finally remove) ==========
    static void step4_correctUsage() throws Exception {
        System.out.println("=== 实验4: 标准写法 static final + try/finally remove ===");

        Thread t1 = new Thread(new Runnable() {
            @Override
            public void run() {
                handle("用户A");
            }
        });
        Thread t2 = new Thread(new Runnable() {
            @Override
            public void run() {
                handle("用户B");
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        System.out.println(">> static final: key 常驻, 不产生 stale Entry; finally remove: value 归还, 不泄漏不串号");
        System.out.println(">> 口诀: 线程隔离用 ThreadLocal, 线程共享用锁 / 原子类\n");
    }

    static void handle(String who) {
        try {
            CTX.set(who + " 的请求上下文");
            Thread.sleep(50); // 放大并发窗口, 两个任务同时持值
            System.out.println("[" + Thread.currentThread().getName() + "] 读取 = " + CTX.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            CTX.remove();
        }
        System.out.println("[" + Thread.currentThread().getName() + "] remove 后 get() = " + CTX.get());
    }

}
