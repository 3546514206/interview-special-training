package edu.zjnu.lab.q12;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Q12+Q13 · 事务隔离级别 + MVCC 实验 (单机模拟 InnoDB 的 ReadView 可见性判断)
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.lab.q12.Q12MvccLab
 *
 * 这是最值得动手的一个实验: 把 ReadView 的 5 条可见性规则写成可执行代码,
 * 然后跑 RC / RR 两个场景, 亲眼看到:
 *   RC  每次 SELECT 生成新 ReadView -> 不可重复读
 *   RR  整个事务复用第一次的 ReadView -> 可重复读
 */
public class Q12MvccLab {

    // ================= InnoDB 机制的纯 Java 模拟 =================

    static long trxSeq = 100; // 全局事务 id 发号器

    static class Trx {
        final long id = ++trxSeq;
        boolean committed;
    }

    /** 一行数据 = 版本链上的一个节点 (对应 DB_TRX_ID + DB_ROLL_PTR 指向的旧版本) */
    static class RowVersion {
        final long trxId;         // 最后修改这行的事务 id (DB_TRX_ID)
        final Object value;
        RowVersion prev;          // DB_ROLL_PTR -> undo log 里的上一版本

        RowVersion(long trxId, Object value) {
            this.trxId = trxId;
            this.value = value;
        }
    }

    /** ReadView: 一致性快照 (RC 每次 select 新建, RR 事务内复用) */
    static class ReadView {
        final List<Long> activeIds = new ArrayList<>(); // 生成瞬间所有【活跃(未提交)】事务
        final long minTrxId, maxTrxId, creatorTrxId;    // m_min / m_max(=活跃最大+1) / m_creator

        ReadView(Trx creator, List<Trx> allActive) {
            for (Trx t : allActive) if (!t.committed) activeIds.add(t.id);
            maxTrxId = trxSeq + 1; // 下一个将分配的事务号
            // 无活跃事务时 min 取 max, 保证不变量 min <= max (InnoDB 里 min=活跃最小事务id)
            minTrxId = activeIds.isEmpty() ? maxTrxId : activeIds.get(0);
            creatorTrxId = creator.id;
        }

        /** 核心: 版本链可见性判断 —— 5 条规则原样翻译 (面试背的就是这段) */
        boolean visible(RowVersion v) {
            if (v.trxId == creatorTrxId) return true;                       // 1. 自己改的, 可见
            if (v.trxId < minTrxId) return true;                            // 2. 快照前已提交, 可见
            if (v.trxId >= maxTrxId) return false;                          // 3. 快照后才开启, 不可见
            return !activeIds.contains(v.trxId);                            // 4/5. 区间内: 还活跃=不可见, 已提交=可见
        }
    }

    /** 模拟一张单行表: 每次 UPDATE 头插一个新版本, 旧版本挂 undo 链 */
    static class Table {
        RowVersion head;
        final List<Trx> world = new ArrayList<>(); // 曾存在过的所有事务

        void update(Trx t, Object newValue) {
            RowVersion v = new RowVersion(t.id, newValue);
            v.prev = head;
            head = v;
        }

        /** SELECT ... (快照读): 沿版本链找第一个对 readView 可见的版本 */
        Object select(ReadView rv) {
            for (RowVersion v = head; v != null; v = v.prev) {
                if (rv.visible(v)) return v.value; // 这行被跳过 = 相当于 InnoDB "顺着 DB_ROLL_PTR 回溯 undo log"
            }
            return null; // 整条链都不可见 = 行对本次查询不存在 (RR 防幻读的快照基础)
        }

        ReadView createReadView(Trx creator) { return new ReadView(creator, world); }
    }

    // ================= 场景演示 =================

    public static void main(String[] args) {
        rc_notRepeatableRead();
        rr_repeatableRead();
        rr_phantomEdgeCase();
    }

    /** RC (读已提交): 每次 select 一个新 ReadView -> 能看到别人新提交的修改 */
    static void rc_notRepeatableRead() {
        System.out.println("=== 场景1: RC 下不可重复读 ===");
        Table t = new Table();
        Trx me = new Trx(); t.world.add(me);
        t.update(me, 1000); me.committed = true; // 初始: 余额 1000

        Trx other = new Trx(); t.world.add(other);
        t.update(other, 800);                    // other 改成 800, 先不提交

        ReadView rv1 = t.createReadView(me);
        System.out.println("第一次读: " + t.select(rv1) + "   (800 的版本不可见, 读到已提交的 1000)");

        other.committed = true;                  // other 提交!
        ReadView rv2 = t.createReadView(me);     // RC: 又建了新 ReadView
        System.out.println("第二次读: " + t.select(rv2) + "   (新快照里 other 已提交 -> 看到 800)");
        System.out.println(">> 同一事务两次读不一样 = 不可重复读。根因: RC 每次 SELECT 都生成新 ReadView\n");
    }

    /** RR (可重复读, InnoDB 默认): 事务内复用第一个 ReadView */
    static void rr_repeatableRead() {
        System.out.println("=== 场景2: RR 下可重复读 ===");
        Table t = new Table();
        Trx me = new Trx(); t.world.add(me);
        t.update(me, 1000); me.committed = true;

        Trx other = new Trx(); t.world.add(other);
        t.update(other, 800);

        ReadView rv = t.createReadView(me);      // RR: 整个事务就这一个 ReadView
        System.out.println("第一次读: " + t.select(rv));

        other.committed = true;                  // other 提交
        System.out.println("第二次读: " + t.select(rv) + "   (还是旧快照, 800 的 trxId 仍在 activeIds 里 -> 不可见)");
        System.out.println(">> 复用 ReadView -> 两次读一致 = 可重复读\n");
    }

    /** RR 的幻读边缘场景: 快照读看不见, 当前读看得见 */
    static void rr_phantomEdgeCase() {
        System.out.println("=== 场景3: RR 防幻读的边界 ===");
        Table t = new Table(); // 模拟"一行范围扫描的聚合"
        Trx me = new Trx(); t.world.add(me);
        t.update(me, "初始行"); me.committed = true;

        ReadView rv = t.createReadView(me);

        Trx late = new Trx(); t.world.add(late);
        t.update(late, "别人插入的新行");
        late.committed = true;                   // 快照之后才开启并提交的事务

        System.out.println("快照读(select):     " + t.select(rv) + "   (late.trxId >= maxTrxId -> 规则3 判不可见, 防住了)");
        System.out.println("当前读(select for update / update): 直接读最新版本 -> " + t.head.value + "  (看得见!)");
        System.out.println(">> RR 靠 MVCC 防快照读幻读, 靠 Next-Key Lock(间隙锁) 防当前读幻读;");
        System.out.println(">> 先快照读再当前读的混合场景仍可能'幻影出现' —— 面试答'防大部分, 非绝对'\n");

        System.out.println("=== 配套速记 ===");
        System.out.println("  ACID: A=undo log, C=业务+约束, I=锁+MVCC, D=redo log");
        System.out.println("  两阶段: redo(prepare) -> 写 binlog -> redo(commit); 崩溃恢复时两者都在才算提交成功");
        System.out.println("  undo 版本链没人再需要旧版本时, 由 purge 线程异步清理 -> RR 长事务会拖住 purge, 面试常追问");
    }
}
