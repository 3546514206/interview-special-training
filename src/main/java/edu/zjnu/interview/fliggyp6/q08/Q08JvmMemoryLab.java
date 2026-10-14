package edu.zjnu.interview.fliggyp6.q08;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.nio.ByteBuffer;

/**
 * Q8 · JVM 内存分实验 (运行时直接看各内存池)
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.fliggyp6.q08.Q08JvmMemoryLab
 * (想看栈深度变化可加 -Dexec.args="-Xss256k" 类参数说明见文末)
 *
 * 验证面试点:
 * 1. 堆内分代池: Eden + 2*Survivor + Old (GC 主战场)
 * 2. Metaspace 在本地内存, 不在堆里 (JDK8+)
 * 3. 程序计数器唯一不 OOM; 栈会 StackOverflow; 直接内存独立于 -Xmx
 */
public class Q08JvmMemoryLab {

    public static void main(String[] args) throws Exception {
        System.out.println("JVM 参数: " + ManagementFactory.getRuntimeMXBean().getInputArguments());
        System.out.println("GC 收集器: " + gcNames() + "\n");

        step1_memoryPools();
        step2_metaspaceVsHeap();
        step3_stackOverflow();
        step4_directMemory();
        step5_stringPool();
    }

    static void step1_memoryPools() {
        System.out.println("=== 实验1: 运行时内存池清单 (热点区域实时占用) ===");
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getUsage().getMax() < 0) continue;
            System.out.printf("  %-28s used=%-10d max=%d%n",
                    pool.getName(), pool.getUsage().getUsed(), pool.getUsage().getMax());
        }
        System.out.println(">> 能看到 Eden / Survivor / Tenured Gen(Old) —— 分代就长在堆上;");
        System.out.println(">> Metaspace / Code Cache 的 max 不占 -Xmx 额度 (本地内存)\n");
    }

    static void step2_metaspaceVsHeap() {
        System.out.println("=== 实验2: Metaspace vs 堆 ===");
        System.out.println("  JDK7 之前: 方法区实现=永久代(PermGen), 在堆里, 容易 OutOfMemoryError: PermGen space");
        System.out.println("  JDK8+:    方法区实现=Metaspace, 搬到本地内存, 受 -XX:MaxMetaspaceSize 限制");
        System.out.println("  换代原因: 永久代大小难调 + 类元数据回收和对象回收耦合, 分开后各自独立\n");
    }

    static int depth = 0;

    static void step3_stackOverflow() {
        System.out.println("=== 实验3: 虚拟机栈溢出 (StackOverflowError) ===");
        try {
            recurse();
        } catch (StackOverflowError e) {
            System.out.println("  递归到第 " + depth + " 层爆栈 (默认 -Xss1m 约能撑 1 万+ 层)");
            System.out.println("  每层方法调用压一个栈帧: 局部变量表 + 操作数栈 + 动态链接 + 返回地址");
        }
        System.out.println("  >> 面试追问'栈越大越好?': 总内存固定, -Xss 越大单线程能递归越深, 但能开的线程数越少\n");
    }

    static void recurse() {
        depth++;
        recurse();
    }

    static void step4_directMemory() {
        System.out.println("=== 实验4: 直接内存 (堆外, NIO DirectByteBuffer) ===");
        ByteBuffer direct = ByteBuffer.allocateDirect(64 * 1024 * 1024); // 64MB 堆外
        System.out.println("  分配了 64MB 直接内存, 堆使用量几乎不变 (不被 -Xmx 统计)");
        System.out.println("  受 -XX:MaxDirectMemorySize 限制; 回收靠 Cleaner 虚引用, GC 感知弱 -> 大量用要小心\n");
        // ponytail: 不显式释放, 进程退出回收; 演示场景 64MB 无压力
    }

    static void step5_stringPool() {
        System.out.println("=== 实验5: 字符串常量池位置 (JDK7+ 在堆里) ===");
        String a = new String("hello");          // 堆里新对象
        String b = "hello";                      // 常量池
        String c = new String("hello").intern(); // intern 主动入池, 返回池内引用
        System.out.println("  new String == 字面量        : " + (a == b) + "  (两个不同对象)");
        System.out.println("  intern() == 字面量          : " + (c == b) + "  (都指向池里同一个)");
        System.out.println("  >> JDK7 起常量池从永久代挪到堆, intern 才可能返回堆中首次出现的引用; 常量池可被 GC 回收\n");
    }

    static String gcNames() {
        StringBuilder sb = new StringBuilder();
        for (java.lang.management.GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            sb.append(g.getName()).append(" ");
        }
        return sb.toString();
    }
}
