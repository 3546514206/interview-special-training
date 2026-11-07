package edu.zjnu.interview.fliggyp6.q02;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Q2 · ConcurrentHashMap 实验复合操作的原子性
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.fliggyp6.q02.Q02ConcurrentMapLab
 *
 * 验证面试点:
 * 1. CHM 保证的是【单个】方法的线程安全, get+put 复合操作照样丢更新 (race)
 * 2. 正确姿势: compute / computeIfAbsent / merge 才是原子的
 * 3. size() 是 baseCount + CounterCell 的近似值
 */
public class Q02ConcurrentMapLab {

    static final int THREADS = 10, TIMES = 10_000;

    public static void main(String[] args) throws Exception {
        System.out.println("=== 实验1: get + put 复合操作 (错误姿势) ===");
        ConcurrentHashMap<String, Integer> bad = new ConcurrentHashMap<>();
        runConcurrent(() -> {
            Integer v = bad.get("cnt");            // 读
            bad.put("cnt", v == null ? 1 : v + 1); // 写 —— 读和写之间可能被别的线程插进来
        });
        System.out.println("期望 100000, 实际 " + bad.get("cnt") + "  => 丢了 " + (THREADS * TIMES - bad.get("cnt")) + " 次更新");
        System.out.println(">> 面试点: CHM 单方法线程安全 != 复合操作原子, get+put 照样 race\n");

        System.out.println("=== 实验2: compute 原子复合操作 (正确姿势) ===");
        ConcurrentHashMap<String, Integer> good = new ConcurrentHashMap<>();
        runConcurrent(() -> good.compute("cnt", (k, v) -> v == null ? 1 : v + 1));
        System.out.println("期望 100000, 实际 " + good.get("cnt") + "  => " + (good.get("cnt") == THREADS * TIMES ? "精确无丢失" : "异常"));

        System.out.println("\n=== 实验3: computeIfAbsent 实现「存在才初始化」 ===");
        ConcurrentHashMap<String, Integer> once = new ConcurrentHashMap<>();
        runConcurrent(() -> once.computeIfAbsent("init", k -> verySlowInit()));
        System.out.println("slowInit 只应执行 1 次, 实际执行 " + initTimes + " 次 (computeIfAbsent 对同 key 加锁, 天然防重复初始化)");

        System.out.println("\n=== 实验4: size() 只是近似 ===");
        for (int i = 0; i < 5; i++) {
            System.out.println("第" + (i + 1) + "次 size() = " + good.size());
        }
        System.out.println(">> 面试点: size 用 baseCount + CounterCell[] 分散计数 (类似 LongAdder), 遍历所有格子再求和, 并发写入时读到的是近似值");
    }

    static int initTimes = 0;

    static Integer verySlowInit() {
        initTimes++; // 真实场景这里可能是昂贵的 DB 查询
        return 42;
    }

    static void runConcurrent(Runnable task) throws Exception {
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(THREADS);
        for (int t = 0; t < THREADS; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < TIMES; i++) task.run();
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown(); // 10 线程同时起跑, 放大竞争
        done.await();
    }
}
