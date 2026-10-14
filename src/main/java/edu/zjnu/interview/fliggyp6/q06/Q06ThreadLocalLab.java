package edu.zjnu.interview.fliggyp6.q06;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
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
 */
public class Q06ThreadLocalLab {

    static ThreadLocal<String> user = new ThreadLocal<>();

    public static void main(String[] args) throws Exception {
        step1_poolDirtyData();
        step2_leak();
        step3_fibonacciHash();
    }

    // ========== 实验1: 线程池串号 ==========
    static void step1_poolDirtyData() throws Exception {
        System.out.println("=== 实验1: 线程池 + 不 remove => 串号 ===");
        ExecutorService pool = Executors.newSingleThreadExecutor(); // 核心线程永存活

        pool.submit(() -> user.set("用户A-张三")); // 任务1: 登录态设置
        Thread.sleep(100);

        pool.submit(() -> {                          // 任务2: 完全没 set, 直接 get
            System.out.println("任务2 拿到的 user = " + user.get() + "  <-- 根本没 set 过, 读到了别人的!");
        });
        Thread.sleep(100);
        pool.submit(() -> user.remove());            // 正确姿势: try/finally 里 remove
        Thread.sleep(100);
        pool.shutdown();
        System.out.println(">> 线程池的线程是复用的, ThreadLocalMap 挂在 Thread 上跟着线程活");
        System.out.println(">> 规范: try { tl.set(x); ... } finally { tl.remove(); }\n");
    }

    // ========== 实验2: 弱引用 key 被回收, value 泄漏 ==========
    static void step2_leak() throws Exception {
        System.out.println("=== 实验2: key 被 GC, value 却泄漏 ===");
        Thread worker = new Thread(() -> {
            ThreadLocal<byte[]> tl = new ThreadLocal<>();
            tl.set(new byte[1024 * 1024]); // 1MB value, 强引用挂在 Entry 上
            tl = null;                     // 外部不再引用这个 ThreadLocal 对象
            System.gc();                   // 触发 GC: Entry.key (弱引用) 被回收

            // 反射扒开当前线程的 ThreadLocalMap 看尸检结果
            try {
                Object map = field(Thread.class, "threadLocals").get(Thread.currentThread());
                Field tableF = map.getClass().getDeclaredField("table");
                tableF.setAccessible(true);
                Object table = tableF.get(map);
                for (int i = 0; i < java.lang.reflect.Array.getLength(table); i++) {
                    Object entry = java.lang.reflect.Array.get(table, i);
                    if (entry == null) continue;
                    Object key = ((WeakReference<?>) entry).get(); // Entry extends WeakReference<ThreadLocal>
                    Object value = field(entry.getClass(), "value").get(entry);
                    if (value != null) {
                        System.out.printf("Entry[%d]: key=%s, value=%s (%d bytes)%n",
                                i, key, value.getClass().getSimpleName(), ((byte[]) value).length);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
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

    static Field field(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
