package edu.zjnu.interview.fliggyp6.q09;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.ArrayList;
import java.util.List;

/**
 * Q9 · GC 实验分代假设 + 分代回收行为观察
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.fliggyp6.q09.Q09GcLab
 *
 * 验证面试点:
 * 1. 分代假设: 大多数对象朝生夕死 -> Minor GC 后 Eden 几乎全清空, 老年代几乎不动
 * 2. 存活对象逐轮晋升: Survivor -> Old
 * 3. 长命对象最终全部落在老年代
 */
public class Q09GcLab {

    static final List<byte[]> keep = new ArrayList<>(); // 模拟"长期存活"的对象

    public static void main(String[] args) throws Exception {
        System.out.println("GC: " + gcNames());
        System.out.println("(想看 GC 日志可加 MAVEN_OPTS=\"-XX:+PrintGCDetails -Xmx256m\" 再跑)\n");

        System.out.println("=== 实验1: 朝生夕死的对象 -> Minor GC 全回收 ===");
        snapshot("初始");
        for (int i = 0; i < 200; i++) {
            byte[] tmp = new byte[512 * 1024]; // 每轮分配 512KB, 方法结束即不可达
            if (tmp.length == -1) keep.add(tmp); // 永不成立, 防止 JIT "逃逸分析式"优化掉分配
        }
        System.gc(); // 演示用: 主动触发一次 Full GC
        Thread.sleep(200);
        snapshot("分配100MB短命对象+GC后");
        System.out.println(">> Eden 被清空但老年代几乎没涨 —— 这批对象死得快, Minor GC 就消化了 (分代假设)\n");

        System.out.println("=== 实验2: 存活对象 -> 一路晋升到老年代 ===");
        for (int round = 1; round <= 6; round++) {
            keep.add(new byte[10 * 1024 * 1024]); // 每轮留 10MB 不放, 模拟长命对象
            System.gc();                          // 触发, 让存活对象经历多轮 GC 提升年龄
            Thread.sleep(100);
            snapshot("第" + round + "轮留10MB后");
        }
        System.out.println(">> Old Used 持续上涨: 对象每熬过一轮 GC 年龄+1, 超过 MaxTenuringThreshold(默认15)");
        System.out.println("   或 Survivor 装不下, 就晋升老年代 —— 这就是'分代收集各自用不同算法'的数据依据\n");

        System.out.println("=== 收集器速记 (对照输出) ===");
        System.out.println("  Serial: 单线程 STW, 客户端;  Parallel(本机默认): 吞吐优先, 多线程 STW");
        System.out.println("  CMS: JDK 8 可用, 并发标记清除, 低停顿, 有碎片+浮动垃圾 (后续 JDK 已移除)");
        System.out.println("  G1: JDK 8 可选, Region 化(2048个1-32MB) + 可预测停顿 MaxGCPauseMillis (后续版本默认)");
        System.out.println("  ZGC: JDK 11 才引入, JDK 8 不可用; 染色指针+读屏障, 并发整理, 低停顿\n");
    }

    static void snapshot(String label) {
        long eden = 0, old = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            String n = p.getName();
            if (n.contains("Eden")) eden = p.getUsage().getUsed() / 1024 / 1024;
            if (n.contains("Old") || n.contains("Tenured")) old = p.getUsage().getUsed() / 1024 / 1024;
        }
        System.out.printf("  [%s] Eden used=%dMB, Old used=%dMB%n", label, eden, old);
    }

    static String gcNames() {
        StringBuilder sb = new StringBuilder();
        for (java.lang.management.GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans())
            sb.append(g.getName()).append(" ");
        return sb.toString();
    }
}
