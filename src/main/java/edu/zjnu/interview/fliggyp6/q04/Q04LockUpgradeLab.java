package edu.zjnu.interview.fliggyp6.q04;

import org.openjdk.jol.info.ClassLayout;

/**
 * Q4 · synchronized 锁升级实验 (JOL 直接看对象头 mark word)
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.fliggyp6.q04.Q04LockUpgradeLab
 *
 * 验证面试点 (JDK 8 环境, 偏向锁默认开启但延迟 4 秒):
 *   无锁(001) -> 偏向(101) -> 轻量级(00) -> 重量级(10), 单向不可逆
 *
 * mark word 末 3 位 (64 位 JVM): 001 无锁 / 101 偏向 / 00 轻量 / 10 重量 / 11 GC 标记
 */
public class Q04LockUpgradeLab {

    public static void main(String[] args) throws Exception {
        System.out.println("JVM: " + System.getProperty("java.version") + "  (JDK8 默认开偏向锁, 但启动后延迟 4s 才生效)\n");

        step1_biasable();
        step2_biased();
        step3_lightweight();
        step4_heavyweight();
    }

    static void step1_biasable() {
        System.out.println("=== 实验1: 新对象状态 ===");
        Object o = new Object();
        print(o, "刚 new 出来");
        System.out.println(">> JVM 启动后 4s 内(BiasedLockingStartupDelay): 001 不可偏向;");
        System.out.println("   4s 后: 101 可偏向(还没偏向任何线程, 第一个线程进来直接 CAS 写线程ID)\n");
    }

    static void step2_biased() throws Exception {
        System.out.println("=== 实验2: 等过 4s 延迟后, 单线程第一次进入同步块 -> 偏向锁 (101) ===");
        Thread.sleep(4500); // 越过 BiasedLockingStartupDelay=4000ms
        Object o = new Object();
        synchronized (o) {
            print(o, "同步块内, 第一次加锁");
        }
        print(o, "退出同步块后"); // 偏向不会释放, mark word 里仍留着线程 ID
        System.out.println(">> 101 = 偏向锁, mark word 记录了当前线程 ID; 退出后仍是 101 —— 偏向保持, 下次同线程进入零成本");
        System.out.println(">> 面试点: JDK 15 起默认禁用偏向锁 (JEP 374), 现代应用多线程多, 撤销偏向的代价 > 收益\n");
    }

    static void step3_lightweight() {
        System.out.println("=== 实验3: 轻量级锁 (00x) —— 复现技巧: 先算 hashCode ===");
        System.out.println("(对象一旦写了 identity hash 就【永远无法偏向】, 第一次 synchronized 直接轻量级)");
        Object o = new Object();
        o.hashCode();          // 填充 identity hash -> 偏向被永久禁止
        print(o, "hashCode 后, 未加锁");
        synchronized (o) {
            print(o, "同步块内(第一次加锁)");  // 00x 轻量级: 栈帧 Lock Record + CAS
        }
        print(o, "退出同步块后");               // 释放 -> 回无锁
        System.out.println(">> 轻量级锁: 拷贝 mark word 进栈帧 Lock Record, CAS 换指针; 释放时 CAS 换回");
        System.out.println(">> 多线程交替执行时反复 CAS 自旋; 自旋次数 JDK6+ 【自适应】(看上次自旋结果), 不是固定10次\n");
    }

    static volatile boolean racing = true;

    static void step4_heavyweight() throws Exception {
        System.out.println("=== 实验4: 重量级锁 (10x) —— 4 线程持续竞争, 竞争【进行中】观察 ===");
        Object o = new Object();
        for (int i = 0; i < 4; i++) {
            new Thread(() -> {
                while (racing) {
                    synchronized (o) { // 4 线程互不相让, 自旋拿不到 -> 膨胀成 ObjectMonitor
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }).start();
        }
        Thread.sleep(500);          // 竞争已充分, monitor 已膨胀
        synchronized (o) {          // main 也下场抢 -> 拿到的一定是重量级锁
            print(o, "持续竞争中");
        }
        racing = false;
        System.out.println(">> 10 = 重量级锁: 关联 ObjectMonitor(owner/EntryList/WaitSet), 抢不到的线程 park 挂起不烧CPU");
        System.out.println(">> 锁升级单向不可逆: 偏向(101) -> 轻量(00) -> 重量(10)\n");
    }

    static void print(Object o, String label) {
        String s = ClassLayout.parseInstance(o).toPrintable();
        for (String line : s.split("\n")) {
            if (line.contains("(object header)")) {
                // JOL 0.10/JDK8 格式: ... 0d 01 00 00 (00001101 00000001 ...) (269)
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(([01]{8})").matcher(line);
                if (m.find()) {
                    String lowByte = m.group(1);       // mark word 最低字节(小端)
                    String tail3 = lowByte.substring(5); // 末 3 bit
                    System.out.printf("%-32s 最低字节=%s  末3位=%s => %s%n", label, lowByte, tail3, state(tail3));
                }
                return;
            }
        }
        System.out.println(label + ": mark word 解析失败\n" + s);
    }

    /** mark word 末 3 bit = 锁标志(2bit) + 偏向位(1bit) */
    static String state(String tail3) {
        String lock2 = tail3.substring(1); // 末 2 位: 锁标志
        boolean biasedBit = tail3.charAt(0) == '1';
        switch (lock2) {
            case "01": return biasedBit ? "可偏向(101)" : "无锁不可偏向(001)";
            case "00": return "轻量级锁(00x)";
            case "10": return "重量级锁(10x)";
            default:   return "GC标记(11)";
        }
    }
}
