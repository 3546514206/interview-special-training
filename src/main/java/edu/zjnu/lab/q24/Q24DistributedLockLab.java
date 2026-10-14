package edu.zjnu.lab.q24;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Q24 · 分布式锁实验Redis 锁的「误删他人锁」bug + Lua 原子删 + fencing token
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.lab.q24.Q24DistributedLockLab
 *
 * 验证面试点:
 * 1. 锁超时误删: A 的锁过期后 B 拿到锁, A 干完活 DEL —— 删的是 B 的锁!
 * 2. 正确姿势: DEL 前比对 value(UUID), 且「比对+删除」必须原子 => Lua 脚本
 * 3. Kleppmann 的 GC 暂停场景连 Lua 也救不了 => 资源侧 fencing token 兜底
 */
public class Q24DistributedLockLab {

    /** 模拟单线程 Redis: 所有方法天然串行 = 原子性 (真实 Redis 命令也单线程) */
    static class FakeRedis {
        final Map<String, String> store = new HashMap<>();

        String set(String k, String v) { return store.put(k, v); }
        String get(String k) { return store.get(k); }
        String del(String k) { return store.remove(k) == null ? "0" : "1"; }

        /** Lua 脚本语义: if GET==expected then DEL (整段原子执行, 中途不会有人插入) */
        String delIfValueEquals(String k, String expected) {
            // synchronized 模拟 Redis 单线程: 判断和删除之间不可能被其他命令插入
            synchronized (this) {
                if (expected.equals(store.get(k))) {
                    store.remove(k);
                    return "1 (自己的锁, 删了)";
                }
                return "0 (锁是别人的, 不动)";
            }
        }
    }

    static class Client {
        final String name;
        final String lockValue = UUID.randomUUID().toString(); // 每个客户端的锁标识
        final FakeRedis redis;

        Client(String name, FakeRedis redis) {
            this.name = name;
            this.redis = redis;
        }

        boolean tryLock(String key) {
            if (redis.get(key) == null) { // 真实用 SET NX PX 30000 一条原子命令
                redis.set(key, lockValue);
                return true;
            }
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 场景1: 裸 DEL 的误删事故 ===");
        FakeRedis redis = new FakeRedis();
        Client a = new Client("客户端A", redis), b = new Client("客户端B", redis), c = new Client("客户端C", redis);
        String LOCK = "lock:order:1001";

        System.out.println("A tryLock: " + a.tryLock(LOCK) + " (value=" + a.lockValue.substring(0, 8) + "..)");
        System.out.println("... A 的业务执行太久(GC/慢SQL), 锁 30s TTL 到期自动释放...");
        redis.set(LOCK, b.lockValue); // 模拟 TTL 过期后 B 抢到 (真实: Redis 过期删除 + B SETNX 成功)
        System.out.println("B tryLock: " + (redis.get(LOCK).equals(b.lockValue)) + " (value=" + b.lockValue.substring(0, 8) + "..)");
        System.out.println("... A 终于干完, 无脑执行 DEL lock ...");
        System.out.println("A DEL 结果: " + redis.del(LOCK) + "  <-- 把 B 的锁删了!!");
        System.out.println("C tryLock: " + c.tryLock(LOCK) + "  <-- C 也进来了, B 和 C 同时持有『锁』, 互斥性崩塌\n");

        System.out.println("=== 场景2: Lua 原子比对删除 (正确姿势) ===");
        redis.store.clear();
        Client a2 = new Client("A2", redis), b2 = new Client("B2", redis);
        a2.tryLock(LOCK);
        System.out.println("... A2 超时, B2 拿到锁 ...");
        redis.set(LOCK, b2.lockValue);
        System.out.println("A2 完成后调 Lua (GET==自己的value 才 DEL): "
                + redis.delIfValueEquals(LOCK, a2.lockValue));
        System.out.println("B2 还持锁? " + LOCK + " -> " + redis.get(LOCK).substring(0, 8) + ".. = B2的value  <- 锁保住了\n");

        System.out.println("=== 场景3: 为什么还需要 fencing token (Kleppmann 反驳 RedLock 的核心) ===");
        System.out.println("  时序: A 拿锁(token=33) -> A GC 停顿 40s -> 锁过期 -> B 拿锁(token=34) -> B 写入完成");
        System.out.println("        -> A 苏醒继续写(它以为自己还有锁) —— 就算 Lua 也没机会执行");
        System.out.println("  解法: 锁服务发单调递增 token, 存储侧记录已见过的最大 token, 拒绝旧 token 的写入:");
        long dbLastToken = 34;                     // DB 已接受 token=34 的写入
        long aToken = 33;                          // A 苏醒后带着旧 token 来写
        if (aToken > dbLastToken) {
            System.out.println("  A(token=33) 写入: 接受");
        } else {
            System.out.println("  A(token=" + aToken + ") 写入: 拒绝! (DB 已见过 token=" + dbLastToken + ")  <- 最后防线兜住");
        }
        System.out.println("\n=== 速记 ===");
        System.out.println("  生产推荐: Redisson (看门狗默认30s每10s续期 + Hash结构实现可重入 {threadId:count}) + DB乐观锁/唯一索引兜底");
        System.out.println("  强一致场景: ZooKeeper 临时顺序节点(CP, 公平) / etcd Lease");
        System.out.println("  RedLock 多数派: 学术上不安全(GC暂停+时钟跳变), 业务能容忍极端失效才可用");
        System.out.println("  本质认知: Redis 锁是【性能优化】, 不是正确性保证; 正确性靠资源侧(token/唯一索引)");
    }
}
