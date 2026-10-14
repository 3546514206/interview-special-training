package edu.zjnu.interview.fliggyp6.q07;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Q7 · ThreadPoolExecutor 七参数 + 4 拒绝策略实验
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.fliggyp6.q07.Q07ThreadPoolLab
 *
 * 验证面试点 (核心!):
 *   提交流程 = 建核心线程 -> 入队 -> 队列满才扩非核心 -> max 也满触发拒绝
 *   注意: 【先入队后扩容】! 队列不满永远不会建非核心线程
 */
public class Q07ThreadPoolLab {

    static void log(String event, ThreadPoolExecutor p) {
        System.out.printf("  [%s] 活跃=%d 池大小=%d 队列=%d 已完成=%d%n",
                event, p.getActiveCount(), p.getPoolSize(), p.getQueue().size(), p.getCompletedTaskCount());
    }

    public static void main(String[] args) throws Exception {
        step1_submitFlow();
        step2_rejectPolicies();
        step3_monitorHooks();
    }

    // ========== 实验1: 核心满 -> 入队 -> 队列满 -> 扩到 max -> 拒绝 ==========

    static void step1_submitFlow() throws Exception {
        System.out.println("=== 实验1: execute 流程 (core=2, queue=3, max=4) ===");
        AtomicInteger seq = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(3), r -> {
                    Thread t = new Thread(r);
                    t.setName("pool-worker-" + seq.incrementAndGet()); // 生产必做: 线程池线程要有名字, dump 才好排查
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());

        for (int i = 1; i <= 10; i++) {
            try {
                pool.execute(() -> sleep(300));
                System.out.print("提交任务 " + i + " 成功 ");
                log("观察", pool);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                System.out.println("提交任务 " + i + " 被拒绝! (核心2 + 队列3 + 非核心2 = 最多同时容纳 7 个)");
            }
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println(">> 逐条看上面输出: 1-2 建核心线程; 3-5 进队列(队列不满不扩容!);");
        System.out.println(">> 6-7 队列满了才建非核心; 8 起直接拒绝 —— 跟背的流程一模一样\n");
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ========== 实验2: 4 种拒绝策略行为对比 ==========

    static void step2_rejectPolicies() {
        System.out.println("=== 实验2: 4 种拒绝策略 (core=max=1, 队列1, 塞 3 个任务) ===");
        reject("AbortPolicy(默认)", new ThreadPoolExecutor.AbortPolicy());
        reject("CallerRunsPolicy", new ThreadPoolExecutor.CallerRunsPolicy());
        reject("DiscardPolicy", new ThreadPoolExecutor.DiscardPolicy());
        reject("DiscardOldestPolicy", new ThreadPoolExecutor.DiscardOldestPolicy());
        System.out.println(">> Abort 抛异常 / CallerRuns 提交线程自己跑(反压, 但提交者是主线程时主线程会被拖住!)");
        System.out.println(">> Discard 静默丢 / DiscardOldest 丢队头再重提 —— 后两个线上慎用, 出问题无感知\n");
    }

    static void reject(String name, RejectedExecutionHandler h) {
        ThreadPoolExecutor p = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(1), h);
        StringBuilder result = new StringBuilder();
        for (int i = 1; i <= 3; i++) {
            final int id = i;
            try {
                p.execute(() -> {
                    result.append("任务").append(id).append("执行 ");
                    sleep(50);
                });
                result.append("任务").append(id).append("已提交 ");
            } catch (java.util.concurrent.RejectedExecutionException e) {
                result.append("任务").append(id).append("被拒绝(抛异常) ");
            }
        }
        p.shutdown();
        try {
            p.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        System.out.printf("%-24s => %s%n", name, result);
    }

    // ========== 实验3: 生产监控姿势 (重写 before/afterExecute) ==========

    static void step3_monitorHooks() throws Exception {
        System.out.println("=== 实验3: 监控埋点 (before/afterExecute) ===");
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10)) {
            @Override
            protected void beforeExecute(Thread t, Runnable r) {
                System.out.println("  [监控] " + t.getName() + " 开始, 队列积压=" + getQueue().size());
            }

            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                System.out.println("  [监控] 结平, 已完成=" + getCompletedTaskCount()); // 生产接 Micrometer 上报 Prometheus
            }
        };
        for (int i = 0; i < 3; i++) pool.execute(() -> sleep(50));
        pool.shutdown();
        pool.awaitTermination(3, TimeUnit.SECONDS);
        System.out.println(">> 追问'线程数怎么定': CPU 密集 N+1; IO 密集 N*U*(1+W/C), 一切以压测为准, 没有'2N 就对'\n");
    }
}
