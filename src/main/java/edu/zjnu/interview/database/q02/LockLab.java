package edu.zjnu.interview.database.q02;

import edu.zjnu.interview.database.env.EnvLoader;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据库面试 Q02 实验:行锁 / 间隙锁 / 死锁排查.
 * <p>
 * 跑法:
 *   mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.database.q02.LockLab
 * <p>
 * 演示点:
 *   1. SELECT ... FOR UPDATE 行锁:两个事务并发更新不同 id 不阻塞,同 id 阻塞
 *   2. 范围 FOR UPDATE 加间隙锁:阻塞区间内 INSERT
 *   3. 死锁模拟:AB 互持对方要的资源 → MySQL 自动回滚一个
 *   4. 当前读 vs 快照读:同事务内 UPDATE 后 SELECT 看到自己的修改
 *
 * @author 杨海波 (SetsunaYang)
 */
public class LockLab {

    // 敏感配置从 .env 读取 (DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD)
    private static final String HOST = EnvLoader.getRequired("DB_HOST");
    private static final String PORT = EnvLoader.getRequired("DB_PORT");
    private static final String NAME = EnvLoader.getRequired("DB_NAME");
    private static final String URL  = "jdbc:mysql://" + HOST + ":" + PORT + "/" + NAME
            + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
    private static final String USER = EnvLoader.getRequired("DB_USER");
    private static final String PASS = EnvLoader.getRequired("DB_PASSWORD");

    public static void main(String[] args) throws Exception {
        demoRowLock();
        demoGapLock();
        demoDeadlock();
        demoCurrentRead();
    }

    /**
     * 行锁:事务 A 锁 id=1,事务 B 同时锁 id=2 → 不阻塞;锁 id=1 → 阻塞.
     */
    private static void demoRowLock() throws InterruptedException {
        System.out.println("\n========== Demo 1: 行锁(id 不同不冲突) ==========");
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger okCount = new AtomicInteger();

        new Thread(() -> runTx("A", () -> {
            try (Connection c = newConn()) {
                c.setAutoCommit(false);
                // 加行锁
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT * FROM users WHERE id = ? FOR UPDATE")) {
                    ps.setInt(1, 1);
                    ps.executeQuery();
                }
                System.out.println("[A] 锁 id=1,睡眠 3s");
                Thread.sleep(3_000);
                c.commit();
                okCount.incrementAndGet();
            } catch (SQLException e) {
                System.out.println("[A] err: " + e.getMessage());
            } finally {
                done.countDown();
            }
        })).start();

        Thread.sleep(500); // 让 A 先拿到锁

        new Thread(() -> runTx("B", () -> {
            try (Connection c = newConn()) {
                c.setAutoCommit(false);
                long t = System.currentTimeMillis();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT * FROM users WHERE id = ? FOR UPDATE")) {
                    ps.setInt(1, 2);
                    ps.executeQuery();
                }
                long el = System.currentTimeMillis() - t;
                System.out.println("[B] 锁 id=2 用时 " + el + " ms (应该 < 100ms,无阻塞)");
                c.commit();
                okCount.incrementAndGet();
            } catch (SQLException e) {
                System.out.println("[B] err: " + e.getMessage());
            } finally {
                done.countDown();
            }
        })).start();

        done.await(10, TimeUnit.SECONDS);
        System.out.println("✅ 完成: " + okCount.get() + " / 2 (期望 2)");
    }

    /**
     * 间隙锁:RR 级别下,FOR UPDATE 范围查询锁区间 → 阻塞区间内 INSERT.
     */
    private static void demoGapLock() throws InterruptedException {
        System.out.println("\n========== Demo 2: 间隙锁 (RR + 范围查询) ==========");
        CountDownLatch done = new CountDownLatch(2);

        new Thread(() -> runTx("A", () -> {
            try (Connection c = newConn()) {
                c.setAutoCommit(false);
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT * FROM users WHERE id BETWEEN ? AND ? FOR UPDATE")) {
                    ps.setInt(1, 5);
                    ps.setInt(2, 15);
                    ps.executeQuery();
                }
                System.out.println("[A] 范围锁 id 5-15,睡眠 5s");
                Thread.sleep(5_000);
                c.commit();
            } catch (SQLException e) {
                System.out.println("[A] err: " + e.getMessage());
            } finally {
                done.countDown();
            }
        })).start();

        Thread.sleep(500);

        new Thread(() -> runTx("B", () -> {
            try (Connection c = newConn()) {
                c.setAutoCommit(false);
                long t = System.currentTimeMillis();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO users(name, phone, age) VALUES('间隙测试', ?, 30)")) {
                    ps.setString(1, "139" + System.currentTimeMillis() % 100000000);
                    ps.executeUpdate();
                }
                long el = System.currentTimeMillis() - t;
                System.out.println("[B] INSERT id 在间隙内用时 " + el + " ms (应该 ≥ 5000ms,被间隙锁阻塞)");
                c.commit();
            } catch (SQLException e) {
                System.out.println("[B] err (期望是 lock wait timeout): " + e.getMessage());
            } finally {
                done.countDown();
            }
        })).start();

        done.await(20, TimeUnit.SECONDS);
    }

    /**
     * 死锁:A 锁 id=1 等 id=2,B 锁 id=2 等 id=1 → MySQL 检测死锁,自动回滚一个.
     */
    private static void demoDeadlock() throws InterruptedException {
        System.out.println("\n========== Demo 3: 死锁检测 ==========");
        CountDownLatch done = new CountDownLatch(2);

        new Thread(() -> runTx("A", () -> {
            try (Connection c = newConn()) {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE users SET age = age + 1 WHERE id = ?")) {
                    ps.setInt(1, 1);
                    ps.executeUpdate();
                }
                System.out.println("[A] 持 id=1,等 2s 后要 id=2");
                Thread.sleep(2_000);
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE users SET age = age + 1 WHERE id = ?")) {
                    ps.setInt(1, 2);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                System.out.println("[A] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                done.countDown();
            }
        })).start();

        Thread.sleep(500); // A 先抢 id=1

        new Thread(() -> runTx("B", () -> {
            try (Connection c = newConn()) {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE users SET age = age + 1 WHERE id = ?")) {
                    ps.setInt(1, 2);
                    ps.executeUpdate();
                }
                System.out.println("[B] 持 id=2,等 2s 后要 id=1");
                Thread.sleep(2_000);
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE users SET age = age + 1 WHERE id = ?")) {
                    ps.setInt(1, 1);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                System.out.println("[B] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                done.countDown();
            }
        })).start();

        done.await(15, TimeUnit.SECONDS);
    }

    /**
     * 当前读 vs 快照读:RR 事务内,UPDATE 后 SELECT 走当前读 → 能看到自己的修改.
     */
    private static void demoCurrentRead() throws SQLException {
        System.out.println("\n========== Demo 4: 当前读 vs 快照读 ==========");
        try (Connection c = newConn()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            // 快照读
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT age FROM users WHERE id = 1");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                System.out.println("[快照读 1] age = " + rs.getInt(1));
            }

            // 自己 UPDATE (当前读 + 写)
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET age = age + 1 WHERE id = 1")) {
                ps.executeUpdate();
            }

            // 再快照读
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT age FROM users WHERE id = 1");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                System.out.println("[快照读 2] age = " + rs.getInt(1) + " (还是原值)");
            }

            // 当前读
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT age FROM users WHERE id = 1 FOR UPDATE");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                System.out.println("[当前读]   age = " + rs.getInt(1) + " (看到自己 UPDATE 后的值)");
            }

            // 回滚避免污染
            c.rollback();
        }
    }

    private static Connection newConn() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    private static void runTx(String tag, Runnable r) {
        Thread t = new Thread(r, "tx-" + tag);
        t.setDaemon(true);
        t.start();
    }
}