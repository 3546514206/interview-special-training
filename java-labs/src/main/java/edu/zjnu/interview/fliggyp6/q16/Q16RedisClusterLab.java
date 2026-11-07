package edu.zjnu.interview.fliggyp6.q16;

/**
 * Q16 · Redis Cluster 16384 slot 实验CRC16 + hash tag
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.fliggyp6.q16.Q16RedisClusterLab
 *
 * 验证面试点:
 * 1. slot = CRC16(key) mod 16384 (标准 XMODEM CRC16, 本实验手写实现)
 * 2. hash tag {user:1000} 让相关 key 落同一 slot (mget/事务/lua 多 key 操作的前提)
 * 3. 为什么 16384 而不是 65535: 心跳包里 slot 位图大小 (16384bit=2KB vs 65536bit=8KB)
 */
public class Q16RedisClusterLab {

    public static void main(String[] args) {
        System.out.println("=== 自校验: CRC16-XMODEM(123456789) 应为 0x31C3 (12739) ===");
        int crc = crc16("123456789".getBytes());
        System.out.printf("  计算结果 = 0x%04X (%d) -> %s%n%n", crc, crc, crc == 0x31C3 ? "与标准一致" : "实现有误");

        System.out.println("=== 实验1: 不同 key 的 slot 分布 ===");
        for (String key : new String[]{"user:1000", "user:1001", "order:2000", "stock:sku:88"})
            System.out.printf("  slot(\"%-12s\") = %d%n", key, keyHashSlot(key));

        System.out.println("\n=== 实验2: hash tag 让相关 key 同槽 ===");
        String a = "{user:1000}.profile", b = "{user:1000}.orders", c = "{user:1000}.cart";
        System.out.printf("  %s -> %d%n  %s -> %d%n  %s -> %d%n", a, keyHashSlot(a), b, keyHashSlot(b), c, keyHashSlot(c));
        System.out.println("  >> 三个 key 同槽 => 可以 mget / MULTI / Lua 一起操作 (Cluster 模式跨槽操作直接报错 CROSSSLOT)");
        System.out.println("  >> 副作用: 同 hash tag 的 key 全落同一个节点, 数据倾斜风险\n");

        System.out.println("=== 实验3: 为什么是 16384 (2^14) 而不是 65536 ===");
        System.out.println("  ① 心跳包要带 slot 位图: 每槽 1 bit -> 16384/8 = 2KB (官方建议心跳包 <1MB, 留足余量)");
        System.out.println("     若 65536 槽 -> 位图 8KB, 每秒大量 ping/pong, 带宽浪费");
        System.out.println("  ② 官方设计上限 ~1000 master: 16384/1000 = 平均每节点 16 个槽, 够用");
        System.out.println("  ③ slot 越多, 节点路由表/迁移状态越大, 收益递减\n");

        System.out.println("=== 速记: 集群其他要点 ===");
        System.out.println("  gossip 协议: 节点间每 100ms ping/pong 交换状态");
        System.out.println("  扩容: redis-cli --cluster reshard 迁槽, 业务用 MOVED/ASK 重定向无感");
        System.out.println("  大 key: String>10KB / 集合>1万; DEL 是同步 O(n) 必卡主线程 -> 用 UNLINK 异步删");
    }

    /** 复刻 redis 的 keyHashSlot 逻辑: 只 hash 第一个 {..} 里的内容 */
    static int keyHashSlot(String key) {
        int open = key.indexOf('{');
        if (open >= 0) {
            int close = key.indexOf('}', open + 1);
            if (close > open + 1) return crc16(key.substring(open + 1, close).getBytes()) % 16384;
        }
        return crc16(key.getBytes()) % 16384;
    }

    /** CRC16-CCITT/XMODEM: poly=0x1021, init=0 (Redis 源码 crc16.c 同款) */
    static int crc16(byte[] bytes) {
        int crc = 0;
        for (byte b : bytes) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
            }
        }
        return crc & 0xFFFF;
    }
}
