package edu.zjnu.lab.q19;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q19 · Kafka Rebalance 实验Range / RoundRobin / Sticky 分配算法对比
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.lab.q19.Q19RebalanceLab
 *
 * 验证面试点:
 * 1. Range: 按 topic 逐个切区间 -> 多 topic 时先到的 consumer 每次都多拿 (不均)
 * 2. RoundRobin: 全局轮询 -> 均衡
 * 3. Sticky: 均衡 + 尽量保持上一轮分配, rebalance 时迁移量最小
 * 4. CooperativeSticky (3.0+ 推荐): 只 revoke 要动的分区, 不 stop-the-world
 */
public class Q19RebalanceLab {

    public static void main(String[] args) {
        // 场景: 2 个 topic 各 7 个分区, 3 个消费者 —— Range 的经典翻车现场
        Map<String, List<Integer>> topics = new LinkedHashMap<>();
        topics.put("orders", java.util.Arrays.asList(0, 1, 2, 3, 4, 5, 6));
        topics.put("payments", java.util.Arrays.asList(0, 1, 2, 3, 4, 5, 6));
        List<String> consumers = java.util.Arrays.asList("C1", "C2", "C3");

        System.out.println("订阅: " + topics + "  消费者: " + consumers + "\n");

        Map<String, List<String>> range = rangeAssign(topics, consumers);
        print("RangeAssignor (JDK 默认时代)", range);
        System.out.println("  >> C1 两个 topic 都拿 3 个, C3 都只有 2 个 -> 多 topic 时先来者恒多, 不均!\n");

        Map<String, List<String>> round = roundRobinAssign(topics, consumers);
        print("RoundRobinAssignor", round);
        System.out.println("  >> 全局轮询所有分区, 均衡 (14 = 5+5+4)\n");

        // Sticky: 基于上一轮分配做最小迁移
        System.out.println("=== StickyAssignor: C3 掉线, 看迁移量 ===");
        Map<String, List<String>> before = round; // 以 RoundRobin 的结果当「上一轮」
        print("上一轮", before);
        List<String> afterConsumers = new ArrayList<>(consumers);
        afterConsumers.remove("C3");
        Map<String, List<String>> sticky = stickyAssign(topics, afterConsumers, before);
        print("C3 掉线后 (Sticky)", sticky);
        int moved = countMoved(before, sticky);
        System.out.println("  >> 只迁移了 C3 的分区 (" + moved + " 处变动中大部分是原样保留), C1/C2 的分区【原封不动】");
        System.out.println("  >> 对比 eager 协议: 所有 consumer 先 revoke 全部分区再重分配 -> 全组 stop-the-world");
        System.out.println("  >> CooperativeSticky = Sticky + 增量 revoke: 只有 C3 的分区短暂不可用, 其他照常消费 (3.0+ 推荐/4.0 默认)\n");

        System.out.println("=== 速记 ===");
        System.out.println("  Rebalance 触发: 组员增减 / 订阅 topic 分区数变化");
        System.out.println("  两阶段: JoinGroup(选leader) -> SyncGroup(leader 下发分配方案), 期间整组停消费(eager)");
        System.out.println("  避免不必要 rebalance: group.instance.id 静态成员 / 调大 session.timeout.ms / max.poll.interval.ms");
        System.out.println("  KafkaConsumer 非线程安全(多线程用每线程一个 consumer 或 consumer+worker池); Producer 线程安全可共享");
    }

    /** Range: 每个 topic 独立地把分区切成 n 段, 依次分给排序后的消费者 */
    static Map<String, List<String>> rangeAssign(Map<String, List<Integer>> topics, List<String> consumers) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String c : consumers) result.put(c, new ArrayList<>());
        for (Map.Entry<String, List<Integer>> e : topics.entrySet()) {
            int per = (int) Math.ceil(e.getValue().size() / (double) consumers.size());
            for (int i = 0; i < consumers.size(); i++) {
                int from = i * per, to = Math.min(from + per, e.getValue().size());
                for (int p = from; p < to; p++) result.get(consumers.get(i)).add(e.getKey() + "-" + e.getValue().get(p));
            }
        }
        return result;
    }

    /** RoundRobin: 所有 topic 的分区摊平排序后轮询发放 */
    static Map<String, List<String>> roundRobinAssign(Map<String, List<Integer>> topics, List<String> consumers) {
        List<String> all = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : topics.entrySet())
            for (int p : e.getValue()) all.add(e.getKey() + "-" + p);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String c : consumers) result.put(c, new ArrayList<>());
        for (int i = 0; i < all.size(); i++) result.get(consumers.get(i % consumers.size())).add(all.get(i));
        return result;
    }

    /** Sticky 简化版: 上轮有的尽量保留, 剩下的按 RoundRobin 补齐 */
    static Map<String, List<String>> stickyAssign(Map<String, List<Integer>> topics, List<String> consumers,
                                                  Map<String, List<String>> previous) {
        List<String> all = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : topics.entrySet())
            for (int p : e.getValue()) all.add(e.getKey() + "-" + p);

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String c : consumers) result.put(c, new ArrayList<>());
        List<String> remaining = new ArrayList<>();
        // 1) 上一轮还在的消费者, 保留自己原有分区
        for (String c : consumers) {
            List<String> prev = previous.getOrDefault(c, new ArrayList<>());
            for (String part : prev) if (all.contains(part)) result.get(c).add(part);
        }
        for (String part : all) {
            boolean kept = false;
            for (List<String> l : result.values()) if (l.contains(part)) { kept = true; break; }
            if (!kept) remaining.add(part);
        }
        // 2) 剩余分区给最闲的消费者 (简化均衡)
        for (String part : remaining) {
            String leastBusy = null;
            for (String c : consumers)
                if (leastBusy == null || result.get(c).size() < result.get(leastBusy).size()) leastBusy = c;
            result.get(leastBusy).add(part);
        }
        return result;
    }

    static int countMoved(Map<String, List<String>> before, Map<String, List<String>> after) {
        int moved = 0;
        for (Map.Entry<String, List<String>> e : before.entrySet()) {
            for (String part : e.getValue())
                if (!after.getOrDefault(e.getKey(), new ArrayList<>()).contains(part)) moved++;
        }
        return moved;
    }

    static void print(String title, Map<String, List<String>> assign) {
        System.out.println("=== " + title + " ===");
        for (Map.Entry<String, List<String>> e : assign.entrySet())
            System.out.printf("  %s (%d个): %s%n", e.getKey(), e.getValue().size(), e.getValue());
    }
}
