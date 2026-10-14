package edu.zjnu.lab.q11;

import java.util.ArrayList;
import java.util.List;

/**
 * Q11 · MySQL InnoDB B+ 树实验为什么 3~4 层就能撑亿级行
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.lab.q11.Q11BPlusTreeLab
 *
 * 验证面试点:
 * 1. 非叶子节点只存 key+指针 -> 单节点(16KB)塞得多 -> 树矮 -> IO 少
 * 2. 同规模下二叉搜索树要 ~20 层, B+ 树只要 3~4 层
 * 3. 叶子双向链表 -> 范围查询顺藤摸瓜, 不用回到根
 */
public class Q11BPlusTreeLab {

    /** 简化 B+ 树: 每节点最多 100 个索引项, 模拟 InnoDB 一页 16KB 放百个"主键+页指针" */
    static class BPlusTree {
        static final int M = 100;

        Node root = new Leaf();
        long upKey; // 分裂时提升到父节点的 key (返回值传节点, key 走成员变量)

        abstract class Node {
            List<Long> keys = new ArrayList<>();
            abstract Leaf findLeaf(long key);
        }

        class Internal extends Node {
            List<Node> children = new ArrayList<>();

            Leaf findLeaf(long key) {
                int i = 0;
                while (i < keys.size() && key >= keys.get(i)) i++;
                return children.get(i).findLeaf(key);
            }
        }

        class Leaf extends Node {
            Leaf next; // 叶子链表(演示用单向, InnoDB 是双向)

            Leaf findLeaf(long key) { return this; }
        }

        synchronized void insert(long key) {
            Node newRight = insertRec(root, key);
            if (newRight != null) { // 根也满了 -> 整棵树长高一层
                Internal newRoot = new Internal();
                newRoot.keys.add(upKey);
                newRoot.children.add(root);
                newRoot.children.add(newRight);
                root = newRoot;
            }
        }

        /** 返回分裂出的右节点, 没分裂返回 null */
        Node insertRec(Node node, long key) {
            if (node instanceof Leaf) {
                Leaf l = (Leaf) node;
                int pos = 0;
                while (pos < l.keys.size() && l.keys.get(pos) < key) pos++;
                if (pos < l.keys.size() && l.keys.get(pos) == key) return null; // 已存在
                l.keys.add(pos, key);
                return l.keys.size() > M ? splitLeaf(l) : null;
            }
            Internal in = (Internal) node;
            int i = 0;
            while (i < in.keys.size() && key >= in.keys.get(i)) i++;
            Node newRight = insertRec(in.children.get(i), key);
            if (newRight == null) return null;
            in.keys.add(i, upKey);
            in.children.add(i + 1, newRight);
            return in.keys.size() > M ? splitInternal(in) : null;
        }

        Leaf splitLeaf(Leaf l) {
            Leaf r = new Leaf();
            int mid = l.keys.size() / 2;
            r.keys.addAll(l.keys.subList(mid, l.keys.size()));
            l.keys.subList(mid, l.keys.size()).clear();
            r.next = l.next;
            l.next = r;
            upKey = r.keys.get(0); // B+ 树: 提升的 key 在右节点里保留副本
            return r;
        }

        Internal splitInternal(Internal in) {
            Internal r = new Internal();
            int mid = in.keys.size() / 2;
            upKey = in.keys.get(mid); // 内部节点: 提升 key 上移走, 不保留
            r.keys.addAll(in.keys.subList(mid + 1, in.keys.size()));
            r.children.addAll(in.children.subList(mid + 1, in.children.size()));
            in.keys.subList(mid, in.keys.size()).clear();
            in.children.subList(mid + 1, in.children.size()).clear();
            return r;
        }

        int height() {
            int h = 1;
            for (Node n = root; n instanceof Internal; n = ((Internal) n).children.get(0)) h++;
            return h;
        }
    }

    public static void main(String[] args) {
        int n = 1_000_000;
        System.out.println("=== 实验: 插入 " + n + " 个主键, 每节点最多 100 索引项 (模拟 16KB 页) ===");
        BPlusTree tree = new BPlusTree();
        long t0 = System.currentTimeMillis();
        for (long i = 0; i < n; i++) tree.insert(i);
        System.out.println("  插入耗时 " + (System.currentTimeMillis() - t0) + "ms");
        System.out.println("  B+ 树高度 = " + tree.height() + " 层");
        System.out.println("  对照: 二叉搜索树高度 = log2(" + n + ") ~= " + (int) (Math.log(n) / Math.log(2)) + " 层");
        System.out.println("  >> 树高 = 一次主键查询要读的页数(IO 次数): 3~4 次 IO vs 二叉树 20 次");
        System.out.println("  >> 口算: 100 项/页, 3 层 = 100*100*100 = 100 万行, 4 层 = 1 亿行 (面试爱考)");
        System.out.println("  >> vs B 树: B 树内部节点也存整行数据 -> 单页只能放十几个 key -> 树更高 -> IO 更多\n");

        System.out.println("=== 范围查询 [999_990, 1_000_000): 定位一次叶子, 沿链表顺读 ===");
        BPlusTree.Leaf leaf = tree.root.findLeaf(999_990);
        StringBuilder sb = new StringBuilder();
        outer:
        while (leaf != null) {
            for (long k : leaf.keys) {
                if (k >= 999_990) {
                    sb.append(k).append(" ");
                    if (k == n - 1) break outer;
                }
            }
            leaf = leaf.next; // 不回到根, 直接跳下一个叶子页
        }
        System.out.println("  " + sb);
        System.out.println("  >> 这就是 'WHERE id BETWEEN a AND b' 快的根因; 红黑树/跳表(内存结构)做磁盘索引会退化成随机 IO");
    }
}
