package edu.zjnu.interview.database.q01;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.zjnu.interview.database.env.EnvLoader;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据库面试 Q01 实验:HikariCP 连接池 + 并发获取连接演示.
 * <p>
 * 跑法:
 *   mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.database.q01.PoolLab
 * <p>
 * 演示点:
 *   1. 连接池初始化:最小空闲 / 最大连接 / 超时
 *   2. 并发 100 抢 10 个连接 → 等待而非失败
 *   3. 连接泄漏:用完不 close 会撑爆池
 *   4. 统计借/还/等待时长
 *
 * @author 杨海波 (SetsunaYang)
 */
public class PoolLab {

    // 敏感配置从 .env 读取 (DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD)
    private static final String HOST = EnvLoader.getRequired("DB_HOST");
    private static final String PORT = EnvLoader.getRequired("DB_PORT");
    private static final String NAME = EnvLoader.getRequired("DB_NAME");
    private static final String URL  = "jdbc:mysql://" + HOST + ":" + PORT + "/" + NAME
            + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
    private static final String USER = EnvLoader.getRequired("DB_USER");
    private static final String PASS = EnvLoader.getRequired("DB_PASSWORD");

    public static void main(String[] args) throws Exception {
        demoBasic();
        demoConcurrentContention();
    }

    /** 基础:取一个连接,跑 SELECT VERSION(),看池里总连接数. */
    private static void demoBasic() throws SQLException {
        System.out.println("\n========== Demo 1: 基础连接池 ==========");
        try (HikariDataSource ds = newHikariDataSource(5)) {
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT VERSION() AS v")) {
                rs.next();
                System.out.println("MySQL version: " + rs.getString("v"));
                System.out.println("active  conn  = " + ds.getHikariPoolMXBean().getActiveConnections());
                System.out.println("idle    conn  = " + ds.getHikariPoolMXBean().getIdleConnections());
                System.out.println("total   conn  = " + ds.getHikariPoolMXBean().getTotalConnections());
            }
        }
    }

    /**
     * 并发演示:100 个线程同时借连接,池只有 10 个 → 90 个排队等待.
     * 演示"连接池"和"无池每次新建连接"的耗时差.
     */
    private static void demoConcurrentContention() throws InterruptedException {
        System.out.println("\n========== Demo 2: 100 线程抢 10 连接 ==========");
        int threads = 100;
        int poolSize = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail    = new AtomicInteger();
        List<Long> waitTimes  = new ArrayList<>();

        try (HikariDataSource ds = newHikariDataSource(poolSize)) {
            // Java 8 的 ExecutorService 不支持 try-with-resources, 需要手动 shutdown
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {

            long t0 = System.nanoTime();
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        long s = System.nanoTime();
                        try (Connection conn = ds.getConnection();
                             Statement stmt = conn.createStatement();
                             ResultSet rs = stmt.executeQuery("SELECT SLEEP(0.05)")) { // 50ms 模拟业务
                            rs.next();
                            synchronized (waitTimes) {
                                waitTimes.add((System.nanoTime() - s) / 1_000_000);
                            }
                            success.incrementAndGet();
                        }
                    } catch (Exception e) {
                        fail.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await(30, TimeUnit.SECONDS);
            long elapsed = (System.nanoTime() - t0) / 1_000_000;

            System.out.printf("并发 %d 线程,池大小 %d,总耗时 %d ms%n", threads, poolSize, elapsed);
            System.out.println("成功: " + success.get() + " / 失败: " + fail.get());

            long[] sorted = waitTimes.stream().mapToLong(Long::longValue).sorted().toArray();
            if (sorted.length > 0) {
                long p50 = sorted[sorted.length / 2];
                long p99 = sorted[(int) (sorted.length * 0.99)];
                long max = sorted[sorted.length - 1];
                System.out.printf("等待连接耗时 p50=%d ms / p99=%d ms / max=%d ms%n", p50, p99, max);
            }
        } finally {
            pool.shutdown();
        }
        }
    }

    private static HikariDataSource newHikariDataSource(int maxPool) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(URL);
        cfg.setUsername(USER);
        cfg.setPassword(PASS);
        cfg.setMaximumPoolSize(maxPool);
        cfg.setMinimumIdle(Math.min(2, maxPool));
        cfg.setConnectionTimeout(5_000);
        cfg.setIdleTimeout(60_000);
        cfg.setMaxLifetime(30 * 60_000);
        cfg.setPoolName("interview-lab-pool");
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return new HikariDataSource(cfg);
    }
}