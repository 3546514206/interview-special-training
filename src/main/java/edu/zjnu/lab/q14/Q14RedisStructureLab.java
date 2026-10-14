package edu.zjnu.lab.q14;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Q14 · Redis 底层数据结构实验SDS / 跳表 / embstr 44 字节
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.lab.q14.Q14RedisStructureLab
 *
 * 验证面试点:
 * 1. SDS: len 字段 O(1) 取长度, 二进制安全 (对比 C 字符串)
 * 2. skiplist: ZSet 的核心, O(logN) 插入/范围, 层数按概率随机 (P=1/4)
 * 3. embstr 44 字节魔数: 64 - 16(redisObject) - 3(sdshdr8 头) - 1(\0) = 44
 */
public class Q14RedisStructureLab {

    public static void main(String[] args) {
        step1_sds();
        step2_skiplist();
        step3_embstr44();
    }

    // ========== 实验1: SDS vs C 字符串 ==========

    static class Sds {
        int len;    // 已用长度: strlen O(1)
        int alloc;  // 剩余空间: 预分配, 减少 realloc
        byte[] buf; // 二进制安全: 内容里可以有 \0

        Sds(String s) {
            buf = s.getBytes();
            len = buf.length;
            alloc = 0;
        }

        Sds append(String s) {
            byte[] add = s.getBytes();
            if (alloc < add.length) { // 不够才扩容, 顺带验证 Redis 扩容策略
                int newLen = len + add.length;
                int capacity = newLen < (1 << 20) ? newLen * 2 : newLen + (1 << 20); // <1MB 翻倍, >=1MB 每次+1MB
                byte[] nb = new byte[capacity];
                System.arraycopy(buf, 0, nb, 0, len);
                buf = nb;
                alloc = capacity - newLen;
            }
            System.arraycopy(add, 0, buf, len, add.length);
            len += add.length;
            alloc -= add.length;
            return this;
        }
    }

    static void step1_sds() {
        System.out.println("=== 实验1: SDS (Simple Dynamic String) ===");
        Sds s = new Sds("hello");
        s.append(" world");
        System.out.println("  len=" + s.len + " (strlen 直接读字段, O(1))");
        System.out.println("  C 字符串 strlen 要从头数到 \\0, O(n); 且遇到 \\0 就截断, 存不了二进制");
        System.out.println("  append 扩容策略: <1MB 翻倍, >=1MB 只 +1MB —— 空间换时间+防浪费\n");
    }

    // ========== 实验2: 跳表 (ZSet 的 skiplist 编码) ==========

    static class SkipList {
        static final double P = 0.25;  // Redis 层数概率: 每层以 25% 概率再升一层
        static final int MAX_LEVEL = 32;

        static class Node {
            double score;
            String member;
            Node[] next; // 该节点在每一层的后继

            Node(double score, String member, int level) {
                this.score = score;
                this.member = member;
                this.next = new Node[level];
            }
        }

        final Node head = new Node(Double.MIN_VALUE, "HEAD", MAX_LEVEL);
        int level = 1, size;

        int randomLevel() { // ZSL_RANDOM_LEVEL
            int l = 1;
            while (ThreadLocalRandom.current().nextDouble() < P && l < MAX_LEVEL) l++;
            return l;
        }

        void zadd(double score, String member) { // 简化版 zslInsert
            Node[] update = new Node[MAX_LEVEL];
            Node x = head;
            for (int i = level - 1; i >= 0; i--) { // 从最高层往下找插入位置
                while (x.next[i] != null && x.next[i].score < score) x = x.next[i];
                update[i] = x;
            }
            int lvl = randomLevel();
            if (lvl > level) {
                for (int i = level; i < lvl; i++) update[i] = head;
                level = lvl;
            }
            Node n = new Node(score, member, lvl);
            for (int i = 0; i < lvl; i++) { // 逐层挂链
                n.next[i] = update[i].next[i];
                update[i].next[i] = n;
            }
            size++;
        }

        List<String> zrangebyscore(double min, double max) {
            List<String> out = new ArrayList<>();
            Node x = head;
            for (int i = level - 1; i >= 0; i--) {
                while (x.next[i] != null && x.next[i].score < min) x = x.next[i];
            }
            for (x = x.next[0]; x != null && x.score <= max; x = x.next[0]) out.add(x.member);
            return out;
        }
    }

    static void step2_skiplist() {
        System.out.println("=== 实验2: skiplist (ZADD / ZRANGEBYSCORE) ===");
        SkipList zset = new SkipList();
        int n = 100_000;
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < n; i++) zset.zadd(ThreadLocalRandom.current().nextDouble(1000), "member" + i);
        System.out.println("  ZADD " + n + " 个成员耗时 " + (System.currentTimeMillis() - t0) + "ms (O(logN), 期望层数 1/(1-0.25)≈1.33)");
        t0 = System.currentTimeMillis();
        List<String> hits = zset.zrangebyscore(500, 501);
        System.out.println("  ZRANGEBYSCORE 500..501 命中 " + hits.size() + " 个, 耗时 " + (System.currentTimeMillis() - t0) + "ms (定位 O(logN) + 沿底层链表扫)");
        System.out.println("  >> ZSet 双结构: dict 负责 O(1) 查 score, skiplist 负责排序/范围 —— 各取所长");
        System.out.println("  >> 元素 >128 或单 member >64 字节才从 listpack 切到 skiplist (阈值面试要背)\n");
    }

    // ========== 实验3: embstr 44 字节魔数 ==========

    static void step3_embstr44() {
        System.out.println("=== 实验3: embstr 为什么是 44 字节 ===");
        System.out.println("  jemalloc 64 字节内存桶 (2^6)");
        System.out.println("  - 16  redisObject (type4+encoding4+lru24+refcount+ptr)");
        System.out.println("  - 3   sdshdr8 头部 (len/alloc/flags)");
        System.out.println("  - 1   结尾 \\0");
        System.out.println("  = 44  字节留给内容 => 一次 malloc, redisObject 和 SDS 挤同一个 64B 块");
        System.out.println("  >> 核心目的: 一次分配 + 64B 对齐; cache line 友好是附带效果");
        System.out.println("  >> 超过 44 字节切 raw 编码: 两次 malloc + 指针跳转\n");

        System.out.println("=== 编码切换阈值速记 (Redis 8) ===");
        System.out.println("  String: int / <=44B embstr / raw");
        System.out.println("  List:   listpack -> quicklist(节点>128项或单值>64B)");
        System.out.println("  Hash:   listpack -> hashtable(>128 或 >64B)");
        System.out.println("  Set:    intset(全整数<=512) -> listpack -> hashtable");
        System.out.println("  ZSet:   listpack -> skiplist+dict(>128 或 >64B)");
        System.out.println("  >> ziplist 被淘汰的原因: 记录前驱节点长度 -> 连锁更新; listpack 只记自己长度, 根治");
    }
}
