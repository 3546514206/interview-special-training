package edu.zjnu.lab.q17;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Q17 · Kafka 底层存储实验顺序写 vs 随机写 + 稀疏索引
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.lab.q17.Q17KafkaStorageLab
 *
 * 验证面试点:
 * 1. append-only 顺序写 ≈ 内存速度, 随机写慢 1~3 个数量级 (Kafka 高吞吐的地基)
 * 2. 稀疏索引: 每 4KB 数据一条索引项, 索引文件小到常驻 page cache, 二分定位 + 顺扫
 * 3. Kafka 不自建缓存, 全靠 OS page cache (进程内缓存还要额外 GC 压力)
 */
public class Q17KafkaStorageLab {

    static final int TOTAL = 128 * 1024 * 1024; // 每组实验写 128MB
    static final int REC = 1024;                // 每条消息 1KB

    public static void main(String[] args) throws Exception {
        File dir = new File("target/kafka-lab");
        dir.mkdirs();
        step1_sequentialVsRandom(dir);
        step2_sparseIndex(dir);
        clean(dir);
    }

    static void step1_sequentialVsRandom(File dir) throws Exception {
        System.out.println("=== 实验1: 顺序写 vs 随机写 (各 " + (TOTAL / 1024 / 1024) + "MB, 1KB/条) ===");
        byte[] rec = new byte[REC];

        long seq = timed(() -> { // 顺序: 只管往后追加, 模拟 Kafka 的 .log
            try (FileChannel ch = new RandomAccessFile(new File(dir, "seq.log"), "rw").getChannel()) {
                for (int i = 0; i < TOTAL / REC; i++) ch.write(java.nio.ByteBuffer.wrap(rec));
            } catch (IOException ignored) {
            }
        });
        System.out.printf("  顺序写: %d ms (%.0f MB/s)%n", seq, TOTAL / 1024.0 / 1024 / (seq / 1000.0));

        long rand = timed(() -> { // 随机: 每条都 seek 到随机位置
            try (RandomAccessFile f = new RandomAccessFile(new File(dir, "rand.log"), "rw")) {
                java.util.Random r = new java.util.Random(42);
                for (int i = 0; i < TOTAL / REC / 4; i++) { // 随机写太慢, 只写 1/4 量再 x4 折算
                    f.seek(r.nextInt(TOTAL - REC));
                    f.write(rec);
                }
            } catch (IOException ignored) {
            }
        });
        System.out.printf("  随机写: %d ms (1/4 量, 折算全程约 %d ms, 慢约 %.0f 倍)%n",
                rand, rand * 4, rand * 4.0 / seq);
        System.out.println("  >> Kafka 吞吐根基: partition 目录 + segment 文件 append-only, 磁盘顺序写接近内存");
        System.out.println("  >> segment 滚动: 1GB (log.segment.bytes) 或 7 天; 旧 segment 只读 immutable\n");
    }

    static void step2_sparseIndex(File dir) throws Exception {
        System.out.println("=== 实验2: 稀疏索引 (offset -> 文件位置) ===");
        File logF = new File(dir, "indexed.log");
        List<Long> idxOffsets = new ArrayList<>(); // .index: 每 64 条(模拟4KB)记一项
        List<Long> idxPositions = new ArrayList<>();
        try (FileChannel ch = new RandomAccessFile(logF, "rw").getChannel()) {
            byte[] rec = new byte[REC];
            for (long off = 0; off < 100_000; off++) {
                if (off % 64 == 0) { // 稀疏: 不是每条都建索引!
                    idxOffsets.add(off);
                    idxPositions.add(ch.position());
                }
                rec[0] = (byte) off; // 简单留个标记
                ch.write(java.nio.ByteBuffer.wrap(rec));
            }
        }
        System.out.println("  写入 100000 条消息, 索引只存 " + idxOffsets.size() + " 项 (每64条一项, 真实 Kafka 每4KB一项)");
        System.out.printf("  索引内存占用: %d 项 x 16B = %d KB -> 轻松常驻 page cache%n",
                idxOffsets.size(), idxOffsets.size() * 16 / 1024);

        long target = 77_777; // 查 offset=77777
        long t0 = System.nanoTime();
        int lo = 0; // 二分找 <= target 的最大索引项
        while (lo < idxOffsets.size() - 1 && idxOffsets.get(lo + 1) <= target) lo++;
        long position = idxPositions.get(lo);
        // 从该位置顺扫到目标 (最多64条)
        try (RandomAccessFile f = new RandomAccessFile(logF, "r")) {
            f.seek(position);
            for (long off = idxOffsets.get(lo); off <= target; off++) f.read(new byte[REC]);
        }
        System.out.printf("  定位 offset=%d: 二分找索引项(%d) + 顺扫 %d 条, 耗时 %.3f ms%n",
                target, lo, target - idxOffsets.get(lo), (System.nanoTime() - t0) / 1e6);
        System.out.println("  >> 这就是 .index 的设计: 稀疏换小体积, 小到常驻内存, 顺扫兜底");
        System.out.println("  >> .timeindex 同理, 支持按时间戳找 offset (回溯消费场景)\n");
    }

    static long timed(Runnable r) {
        long t0 = System.currentTimeMillis();
        r.run();
        return System.currentTimeMillis() - t0;
    }

    static void clean(File dir) {
        for (File f : dir.listFiles()) f.delete();
        dir.delete();
    }
}
