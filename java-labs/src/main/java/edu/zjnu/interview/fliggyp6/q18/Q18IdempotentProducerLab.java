package edu.zjnu.interview.fliggyp6.q18;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Q18 · Kafka 不丢不重实验幂等生产者的 PID + SequenceNumber 机制
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.fliggyp6.q18.Q18IdempotentProducerLab
 *
 * 验证面试点:
 * 1. 幂等生产者原理: (PID, 分区) 单调序列号; broker 缓存最新 seq
 *    seq == last+1 接受 / seq <= last 去重 / seq > last+1 拒绝(乱序)
 * 2. 局限: 单分区 + 单会话 (Producer 重启换 PID, 幂等失效)
 * 3. 消费端幂等: 业务唯一键兜底
 */
public class Q18IdempotentProducerLab {

    // ===== 模拟组件 =====

    static class Producer {
        final long pid = PID_GEN++; // 每次 new 都换新 PID —— 这就是「单会话」局限的来源
        final Map<Integer, Integer> seqPerPartition = new HashMap<>(); // 每分区独立序列号
        int nextSeq(int partition) {
            int seq = seqPerPartition.getOrDefault(partition, 0);
            seqPerPartition.put(partition, seq + 1);
            return seq;
        }
    }

    static long PID_GEN = 1;

    static class Msg {
        final long pid;
        final int partition, seq;
        final String payload;

        Msg(long pid, int partition, int seq, String payload) {
            this.pid = pid;
            this.partition = partition;
            this.seq = seq;
            this.payload = payload;
        }
    }

    /** Broker: 对每个 (pid, partition) 缓存已提交的最大 seq —— enable.idempotence=true 时的行为 */
    static class Broker {
        Map<String, Integer> lastSeq = new HashMap<>(); // key = pid:p
        List<Msg> log = new ArrayList<>();

        String append(Msg m) {
            String k = m.pid + ":" + m.partition;
            Integer last = lastSeq.get(k);
            if (last == null || m.seq == last + 1) { // 正常顺序到达
                log.add(m);
                lastSeq.put(k, m.seq);
                return "接受";
            }
            if (m.seq <= last) return "重复(seq=" + m.seq + " <= last=" + last + ") -> 静默去重, 不入日志";
            return "乱序(seq=" + m.seq + " > last+1=" + (last + 1) + ") -> 拒绝并报错(OUT_OF_ORDER)";
        }
    }

    public static void main(String[] args) {
        System.out.println("=== 实验1: 开启幂等 (PID+seq), 网络重试重发也不重复 ===");
        Broker broker = new Broker();
        Producer p = new Producer();
        System.out.println("PID = " + p.pid);
        for (int i = 0; i < 5; i++) {
            Msg m = new Msg(p.pid, 0, p.nextSeq(0), "订单" + i);
            String r1 = broker.append(m);
            String r2 = broker.append(m); // 模拟 ack 丢失后 producer 重发同一条
            System.out.printf("  发送 %-6s seq=%d: 首次=%s, 重发=%s, 日志共 %d 条%n",
                    m.payload, m.seq, r1, r2, broker.log.size());
        }

        System.out.println("\n=== 实验2: 不开幂等 (broker 不校验 seq), 同样重发 ===");
        List<Msg> naiveLog = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Msg m = new Msg(99, 0, i, "订单" + i);
            naiveLog.add(m);
            naiveLog.add(m); // 重发 -> 重复入日志, 消费端会处理两次!
        }
        System.out.println("  日志条数 = " + naiveLog.size() + " (期望5, 实际" + naiveLog.size() + " -> 重复消费事故)\n");

        System.out.println("=== 实验3: 幂等的两大局限 ===");
        Broker b2 = new Broker();
        Producer restart = new Producer(); // 重启后的"新"生产者: PID 变了
        b2.append(new Msg(1, 0, 0, "旧PID最后一条"));
        String r = b2.append(new Msg(restart.pid, 0, 0, "重启后第一条"));
        System.out.println("  Producer 重启 -> 新 PID(" + restart.pid + "), 旧 PID 的 seq 缓存形同虚设: " + r);
        System.out.println("  >> 单分区单会话的幂等扛不住重启/换分区, 要 exactly-once 需要事务 (transactional.id 跨会话) + 消费端幂等\n");

        System.out.println("=== 实验4: 消费端业务幂等 (唯一索引) 兜底 ===");
        Set<String> orderIds = new HashSet<>(); // 模拟 DB 唯一索引
        int dup = 0;
        Random rnd = new Random(7);
        for (int i = 0; i < 100; i++) {
            String orderId = "ORD-" + rnd.nextInt(80); // 80 个订单被投递 100 次
            if (!orderIds.add(orderId)) dup++; // add 返回 false = 已存在, 重复投递被拦下
        }
        System.out.println("  100 次投递(80个订单) -> 唯一索引拦下 " + dup + " 次重复, 落库 " + orderIds.size() + " 条");
        System.out.println("\n=== 速记 ===");
        System.out.println("  不丢三件套: acks=all + enable.idempotence=true + min.insync.replicas>=2 (ISR 掉到2以下直接报错)");
        System.out.println("  HW = min(所有ISR的LEO), 消费者只能读到 HW 之前");
        System.out.println("  offset 提交: 先处理后提交=可能重(靠幂等兜), 先提交后处理=可能丢(不可接受)");
    }
}
