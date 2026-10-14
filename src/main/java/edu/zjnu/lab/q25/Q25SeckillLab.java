package edu.zjnu.lab.q25;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Q25 · 秒杀系统实验1000 并发抢 100 库存, 五种方案对比 (能亲眼看到超卖!)
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.lab.q25.Q25SeckillLab
 *
 * 验证面试点:
 * 1. 方案A check-then-act: 必超卖 (经典事故现场)
 * 2. 方案B/C synchronized / Atomic: 单机正确
 * 3. 方案D Redis Lua 原子预扣: 面试标准答案 (扣库存 + 用户去重一段脚本原子完成)
 * 4. 方案E MQ 异步削峰: 预扣成功进队列, 单消费者慢慢落库
 */
public class Q25SeckillLab {

    static final int STOCK = 100;      // 库存
    static final int USERS = 1000;     // 并发用户

    public static void main(String[] args) throws Exception {
        int[] soldA = planANaive();        // 裸奔: check-then-act
        int[] soldB = planBSynchronized(); // synchronized
        int[] soldC = planCAtomic();       // AtomicInteger
        int[] soldD = planDRedisLua();     // Redis Lua 原子预扣 (单机模拟)
        int[] soldE = planEMqAsync();      // MQ 削峰

        System.out.println("\n============ 结果汇总 (" + USERS + " 人抢 " + STOCK + " 件) ============");
        System.out.printf("A. 裸 check-then-act : 卖出 %d 件  %s%n", soldA[0], soldA[0] > STOCK ? "!! 超卖 " + (soldA[0] - STOCK) + " 件 !!!" : "(居然没超?重跑几次必超)");
        System.out.printf("B. synchronized      : 卖出 %d 件  正确, 但锁粒度=整个方法, 吞吐最低%n", soldB[0]);
        System.out.printf("C. AtomicInteger     : 卖出 %d 件  正确, CAS 无锁, 比 B 快%n", soldC[0]);
        System.out.printf("D. Redis Lua 预扣    : 卖出 %d 件, 去重后成交 %d 人  正确 (面试标准答案)%n", soldD[0], soldD[1]);
        System.out.printf("E. MQ 异步削峰       : 预扣 %d 件, 队列积压 %d, 最终落库 %d 单  正确%n", soldE[0], soldE[1], soldE[2]);
        System.out.println("\n>> 生产架构就是 D+E 组合: Redis Lua 原子预扣(挡住 99% 流量) -> MQ 削峰 -> DB WHERE stock>0 兜底");
        System.out.println(">> 热点商品再分桶: 库存100拆10个桶, 并发能力翻几倍; 支付超时走延迟队列回滚库存");
    }

    // ===== 方案A: check-then-act, 教科书级事故 =====
    static int[] planANaive() throws Exception {
        System.out.println("=== A. 裸 check-then-act (先查后改) ===");
        int[] stock = {STOCK};
        int[] sold = {0};
        // 拉宽 check 与 act 之间的窗口, 100% 复现丢更新 (真实场景靠 GC/线程调度随机触发)
        CountDownLatch allChecked = new CountDownLatch(USERS), go = new CountDownLatch(1), done = new CountDownLatch(USERS);
        for (int u = 0; u < USERS; u++) {
            new Thread(() -> {
                if (stock[0] > 0) {          // check: 此刻 1000 个线程看到的都是 100 > 0
                    allChecked.countDown();
                    try { go.await(); } catch (InterruptedException ignored) {}
                    stock[0] = stock[0] - 1; // act: 全员一起减, 丢更新 -> 卖爆
                    sold[0]++;
                } else {
                    allChecked.countDown();
                }
                done.countDown();
            }).start();
        }
        allChecked.await();
        go.countDown();
        done.await();
        return sold;
    }

    // ===== 方案B: synchronized 互斥 =====
    static int[] planBSynchronized() throws Exception {
        System.out.println("\n=== B. synchronized (互斥整个 check+act) ===");
        final Object[] box = {(long) STOCK, 0L};
        runUsers(u -> {
            synchronized (Q25SeckillLab.class) {
                long s = (Long) box[0];
                if (s > 0) {
                    box[0] = s - 1;
                    box[1] = (Long) box[1] + 1;
                }
            }
        });
        return new int[]{(int) (long) (Long) box[1]};
    }

    // ===== 方案C: Atomic CAS =====
    static int[] planCAtomic() throws Exception {
        System.out.println("\n=== C. AtomicInteger.decrementAndGet (CAS 失败即抢不到) ===");
        AtomicInteger stock = new AtomicInteger(STOCK), sold = new AtomicInteger();
        runUsers(u -> {
            if (stock.decrementAndGet() >= 0) sold.incrementAndGet(); // 原子减, 减完发现 <0 说明来晚了
        });
        return new int[]{sold.get()};
    }

    // ===== 方案D: Redis Lua 语义 (单线程串行执行脚本 = 原子) =====
    static int[] planDRedisLua() throws Exception {
        System.out.println("\n=== D. Redis Lua 原子预扣 (扣库存 + 记用户 一段脚本搞定) ===");
        final Object luaMonitor = new Object(); // synchronized 模拟 Redis 单线程: 脚本整体原子
        final int[] stock = {STOCK};
        final Set<Integer> buyers = new HashSet<>(); // ZSET history: 同一用户幂等
        final int[] sold = {0};
        runUsers(u -> {
            synchronized (luaMonitor) { // KEYS[1]=stock, KEYS[2]=history, ARGV[1]=userId
                if (stock[0] <= 0) return;         // local stock=GET; if stock<=0 then return 0
                if (!buyers.add(u)) return;        // ZSCORE history member 已存在则拒绝 (一人一单)
                stock[0]--;                        // DECR stock
                sold[0]++;                         // return 1
            }
        });
        return new int[]{sold[0], buyers.size()};
    }

    // ===== 方案E: MQ 削峰填谷 =====
    static int[] planEMqAsync() throws Exception {
        System.out.println("\n=== E. 预扣成功 -> 进 MQ -> 单消费者按自己节奏落库 ===");
        AtomicInteger preDeduct = new AtomicInteger(STOCK);
        ConcurrentLinkedQueue<Integer> mq = new ConcurrentLinkedQueue<>(); // RocketMQ/Kafka 队列
        runUsers(u -> {
            if (preDeduct.decrementAndGet() >= 0) mq.offer(u); // 预扣成功才发半消息
        });
        int backlog = mq.size();
        int[] orders = {0};
        Map<Integer, Integer> db = new HashMap<>(); // 消费者: 订单号唯一索引幂等
        Integer u;
        while ((u = mq.poll()) != null) {
            if (!db.containsKey(u)) {
                db.put(u, 1); // INSERT ... ON DUPLICATE KEY / 唯一索引兜底
                orders[0]++;
            }
        }
        System.out.println("  (前端秒杀已结束, 队列里的单子慢慢创建 —— 用户侧只见『排队中』)");
        int success = STOCK - Math.max(preDeduct.get(), 0); // 预扣成功次数
        return new int[]{success, backlog, orders[0]};
    }

    // ===== 工具: 模拟 1000 个并发用户 =====
    static void runUsers(java.util.function.IntConsumer action) throws Exception {
        int n = USERS;
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(n);
        for (int u = 0; u < n; u++) {
            final int userId = u;
            new Thread(() -> {
                try {
                    start.await();
                    action.accept(userId);
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();
    }
}
