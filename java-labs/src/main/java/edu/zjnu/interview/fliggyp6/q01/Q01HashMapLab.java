package edu.zjnu.interview.fliggyp6.q01;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Q1 · HashMap 底层实现实验
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.fliggyp6.q01.Q01HashMapLab
 *
 * 验证面试点:
 * 1. 容量恒为 2 的幂, (n-1)&hash 等价取模
 * 2. 树化条件 = 链表 >=8 【且】数组 >=64 —— 只满足前者时只扩容不转树
 * 3. 扩容后节点用 hash & oldCap 定位新桶, 不重新 hash
 */
public class Q01HashMapLab {

    public static void main(String[] args) throws Exception {
        step1_powerOfTwoAndBitMod();
        step2_treeifyNeedsArray64();
        step3_resizeSplit();
        step5_resizeObserve();
    }

    /** 1. 为什么容量取 2 的幂: (n-1)&hash == hash%n，但位运算更快 */
    static void step1_powerOfTwoAndBitMod() {
        System.out.println("=== 实验1: (n-1)&hash 等价 hash%n (n 为 2 的幂) ===");
        int n = 16;
        for (int hash : new int[]{1, 17, 33, 100, 12345}) {
            int byBit = (n - 1) & hash;
            int byMod = hash % n;
            System.out.printf("hash=%-6d (n-1)&hash=%-3d hash%%n=%-3d 相等=%b%n", hash, byBit, byMod, byBit == byMod);
        }
        System.out.println(">> 面试点: 主要原因是位运算比取模快, 分布均匀是附带效果\n");
    }

    /** 2. 树化双条件: 链表 >=8 且数组 >=64 */
    static void step2_treeifyNeedsArray64() throws Exception {
        System.out.println("=== 实验2: 链表长度 8 但数组 <64 => 只扩容不转红黑树 ===");
        HashMap<String, Integer> map = new HashMap<>(16); // 明确初始 16

        // 找 8 个扰动后 (hash&15) 相同的 key => 容量16时全部落同一个桶, 造出长度8的链表
        int found = 0;
        for (int i = 0; found < 8; i++) {
            String k = "k" + i;
            if (sameBucket("k0", k)) {
                map.put(k, i);
                found++;
            }
        }
        // 第 9 个 put 后链表长度 8: treeifyBin 检查发现 table=16 < 64, 走 resize 而不是转树
        map.put("k0", 0);
        printTableState(map, "链表长度刚到 8 (put 完立刻观察)");
        System.out.println(">> 扩容后数组长度 = " + tableLen(map) + " (16->32, 只扩容没转树, 长链被拆成两条短链)");
        System.out.println(">> 面试易错点: 不是链表>=8就转树, 必须 数组>=64; 此时树化条件没满足, HashMap 选择扩容来缩短链表\n");
    }

    /** 3. 扩容: hash&oldCap == 0 留原位, ==1 去新位 index+oldCap */
    static void step3_resizeSplit() throws Exception {
        System.out.println("=== 实验3: 扩容后桶下标 = 原下标 或 原下标+oldCap ===");
        int oldCap = 16, newCap = 32;
        for (int hash : new int[]{1, 17, 33, 49}) {
            int oldIdx = (oldCap - 1) & hash;
            int move = hash & oldCap;          // 扩容时的分流判断, 不需要重新计算 hash
            int newIdx = move == 0 ? oldIdx : oldIdx + oldCap;
            System.out.printf("hash=%-3d 旧桶=%-2d hash&oldCap=%d 新桶=%-2d (验证: hash&31=%d)%n",
                    hash, oldIdx, move, newIdx, (newCap - 1) & hash);
        }
        System.out.println(">> 面试点: 2的幂设计让扩容免 rehash, 节点只会留在原位或原位+oldCap\n");

        System.out.println("=== 实验4: 真正触发树化 (数组 >=64 后) ===");
        HashMap<String, Integer> map = new HashMap<>(64);
        int found = 0;
        for (int i = 0; found < 9; i++) {
            String k = "t" + i;
            if (sameBucketMod("t0", k, 64)) {
                map.put(k, i);
                found++;
            }
        }
        printTableState(map, "数组64 + 同桶9个key");
        System.out.println(">> 面试点: 此时桶内节点类型是 TreeNode (红黑树), 查询 O(logN)\n");
    }

    /** 5. 观察一次完整扩容: 第13次put触发(putVal路径), 同链节点按 hash&oldCap 分流 */
    static void step5_resizeObserve() throws Exception {
        System.out.println("=== 实验5: 亲眼观察扩容 (第13次put, putVal路径) ===");
        HashMap<Integer, Integer> map = new HashMap<>(16);

        // 12 个 key: 1,17,33,49 在容量16下同落桶1 (Integer的hashCode=值本身, 小整数扰动后不变)
        map.put(1, 1); map.put(17, 1); map.put(33, 1); map.put(49, 1);
        for (int k = 2; k <= 9; k++) map.put(k, k);   // 8个 => 共12个, 到门槛

        System.out.printf("第12个put后: size=%d threshold=12 数组=%d%n", map.size(), tableLen(map));
        printTableState(map, "扩容前");

        map.put(10, 10);  // 第13个: ++size=13 > 12 => resize, 16->32
        System.out.printf("第13个put后: size=%d 数组=%d%n", map.size(), tableLen(map));
        printTableState(map, "扩容后");

        // 桶1那条链的4个key去哪了: hash&16==0 留原位, ==16 去桶1+16
        for (int k : new int[]{1, 17, 33, 49}) {
            System.out.printf("key %-3d hash&oldCap=%-2d 桶%d -> 桶%d%n", k, k & 16, k & 15, k & 31);
        }
        System.out.println(">> 面试点: 扩容=数组翻倍+老链拆两条(hash&oldCap分流), 免重新hash\n");
    }

    // ---------- 工具 ----------

    static int spread(int h) { // 复刻 HashMap.hash
        return h ^ (h >>> 16);
    }

    static boolean sameBucket(String a, String b) { // 容量16同桶
        return spread(a.hashCode()) % 16 == spread(b.hashCode()) % 16 && spread(a.hashCode()) != spread(b.hashCode());
    }

    static boolean sameBucketMod(String a, String b, int n) {
        int ha = spread(a.hashCode()), hb = spread(b.hashCode());
        return ha != hb && (ha & (n - 1)) == (hb & (n - 1));
    }

    static int tableLen(HashMap<?, ?> map) throws Exception {
        Object table = tableField().get(map);
        return table == null ? 0 : java.lang.reflect.Array.getLength(table);
    }

    static void printTableState(HashMap<?, ?> map, String label) throws Exception {
        Object table = tableField().get(map);
        int len = java.lang.reflect.Array.getLength(table);
        for (int i = 0; i < len; i++) {
            Object node = java.lang.reflect.Array.get(table, i);
            if (node != null) {
                int chain = 0;
                String type = node.getClass().getSimpleName();
                for (Object p = node; p != null; p = next(p)) chain++;
                System.out.printf("[%s] 桶%d: 节点类型=%s 链长=%d%n", label, i, type, chain);
            }
        }
    }

    static Object next(Object node) throws Exception {
        // getDeclaredField 只查本类, TreeNode 的 next 继承自 Node -> 沿父类链向上找
        for (Class<?> c = node.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("next");
                f.setAccessible(true);
                return f.get(node);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    static Field tableField() throws Exception {
        Field f = HashMap.class.getDeclaredField("table");
        f.setAccessible(true);
        return f;
    }
}
