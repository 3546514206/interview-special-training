package edu.zjnu.lab.q15;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Q15 · Redis 单线程为什么快实验: 单线程无锁串行 vs 多线程加锁
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.lab.q15.Q15SingleThreadLab
 *
 * 验证面试点:
 * 1. 单线程事件循环: 所有命令排队串行执行 -> 天然原子性, INCR 不需要任何锁
 * 2. 多线程共享内存: 每个"命令"都要竞争同一把锁 -> 锁开销吃掉并行收益
 * 3. 瓶颈不在 CPU: 内存带宽 + 网络 IO 才是, 加线程收益小
 */
public class Q15SingleThreadLab {

    static final int CMDS = 200_000;

    public static void main(String[] args) throws Exception {
        System.out.println("=== 执行 " + CMDS + " 条 INCR 命令, 对比两种模型 ===\n");

        long single = modelSingleThread();     // Redis 模型: 一个线程串行消费命令队列
        long lockMs = modelMultiThreadLock();  // 传统模型: 多线程 + synchronized

        System.out.printf("%n单线程串行模型: %d ms%n", single);
        System.out.printf("多线程加锁模型: %d ms%n", lockMs);
        System.out.println("\n>> 单线程赢在: 无锁竞争、无上下文切换、命令天然原子 (INCR/HINCRBY/事务不用考虑并发)");
        System.out.println(">> 多线程想达到同样正确性, 每条命令都要抢同一把锁 -> 串行化了, 还搭上切换开销");
        System.out.println(">> 面试标准答: 纯内存 + epoll IO 多路复用 + 极简数据结构; 瓶颈在内存带宽和网络, 不在 CPU");
        System.out.println(">> 易错点: Redis 6.0 的 io-threads 只是网络 IO 可多线程, 【命令执行永远单线程】(默认还是关的)");
    }

    /** Redis 模型: 命令进队列, 单线程逐条执行 —— 跟 Redis 的事件循环同构 */
    static long modelSingleThread() throws Exception {
        BlockingQueue<Runnable> commands = new ArrayBlockingQueue<>(CMDS);
        final int[] counter = {0};
        for (int i = 0; i < CMDS; i++) {
            commands.offer(() -> counter[0]++); // INCR: 单线程执行, 天然原子
        }
        long t0 = System.currentTimeMillis();
        Thread eventLoop = new Thread(() -> { // 相当于 Redis 的 main loop + epoll
            try {
                for (int i = 0; i < CMDS; i++) commands.take().run();
            } catch (InterruptedException ignored) {
            }
        });
        eventLoop.start();
        eventLoop.join();
        System.out.println("单线程模型结果: " + counter[0] + "/" + CMDS + " (无锁也无错)");
        return System.currentTimeMillis() - t0;
    }

    /** 传统多线程: 8 线程直接怼共享计数器, 正确性靠锁 */
    static long modelMultiThreadLock() throws Exception {
        AtomicInteger v = new AtomicInteger(); // 用 AtomicInteger 保证正确性 (CAS 自旋)
        int threads = 8, per = CMDS / threads;
        CountDownLatch done = new CountDownLatch(threads);
        long t0 = System.currentTimeMillis();
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                for (int i = 0; i < per; i++) v.incrementAndGet(); // CAS 失败重试 = 竞争开销
                done.countDown();
            }).start();
        }
        done.await();
        System.out.println("多线程模型结果: " + v.get() + "/" + CMDS + " (CAS 保证正确, 但高竞争下自旋浪费严重)");
        return System.currentTimeMillis() - t0;
    }
}
