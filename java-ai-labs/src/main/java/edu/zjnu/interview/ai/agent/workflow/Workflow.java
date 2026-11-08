package edu.zjnu.interview.ai.agent.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * 工作流定义：节点 + 依赖边 + 条件边。
 *
 * <p>模型是<b>有向图</b>而不是「串起来的步骤列表」，因为真实业务里既有并行也有分支回环：
 * <ul>
 *   <li><b>并行</b>：同时查「订单」和「风控」两个源，谁先回来都能推进；</li>
 *   <li><b>回环</b>：检索→生成→校验，校验不过就回到检索重来（带次数上限）。</li>
 * </ul>
 *
 * <p><b>拓扑序的用途</b>：{@link #topologicalOrder()} 给出「忽略环之后的依赖顺序」，
 * 用来做两件事——(1) 构建期检查节点/边是否悬空；(2) 展示与审计工作流结构。
 * 但<b>真正的执行顺序是运行时沿边走的</b>（见 {@link WorkflowRunner}），
 * 因为条件边的走向在运行前不可知。
 *
 * <p><b>条件边的顺序即优先级</b>：出边按声明顺序求值，命中第一个就走。
 * 所以「优先走特殊分支、把兜底分支放最后」是本实现的约定。
 */
public final class Workflow {

    /**
     * 一条边。
     *
     * @param from      来源节点
     * @param to        目标节点
     * @param condition 路由条件；为 null 表示无条件（兜底边）
     * @param label     人类可读的边标签（用于 trace 与文档）
     */
    public record Edge(String from, String to, BiPredicate<WorkflowContext, NodeOutput> condition, String label) {

        public Edge {
            label = label == null ? "" : label;
        }

        /** 无条件边。 */
        public static Edge always(String from, String to) {
            return new Edge(from, to, null, "");
        }

        /** 条件边。 */
        public static Edge when(String from, String to, BiPredicate<WorkflowContext, NodeOutput> condition, String label) {
            return new Edge(from, to, condition, label);
        }

        public boolean conditional() {
            return condition != null;
        }
    }

    private final String name;
    private final Map<String, WorkflowNode> nodes;
    private final List<Edge> edges;
    private final String entry;

    private Workflow(String name, Map<String, WorkflowNode> nodes, List<Edge> edges, String entry) {
        this.name = name;
        this.nodes = nodes;
        this.edges = edges;
        this.entry = entry;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public String entry() {
        return entry;
    }

    public Optional<WorkflowNode> node(String nodeName) {
        return Optional.ofNullable(nodes.get(nodeName));
    }

    public List<Edge> edges() {
        return edges;
    }

    public Set<String> nodeNames() {
        return new LinkedHashSet<>(nodes.keySet());
    }

    /** 某节点的出边（按声明顺序）。 */
    public List<Edge> outgoing(String nodeName) {
        List<Edge> out = new ArrayList<>();
        for (Edge edge : edges) {
            if (edge.from().equals(nodeName)) {
                out.add(edge);
            }
        }
        return out;
    }

    /**
     * 拓扑排序（Kahn 算法）。
     *
     * <p>工作流允许有条件回环（校验不过回到检索），因此严格拓扑序不一定存在。
     * 这里用「容忍环」的 Kahn：能排的先排，剩下位于环里的节点按声明顺序追加，
     * 保证返回值始终覆盖所有节点（便于展示与校验），并在最后标出环上的节点。
     *
     * @return 节点顺序
     */
    public List<String> topologicalOrder() {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (String node : nodes.keySet()) {
            indegree.put(node, 0);
        }
        // 只用「无条件边」参与拓扑计算：条件边的走向在运行前不可知，
        // 把它们算进去会把正常的分支流程误判成环。
        for (Edge edge : edges) {
            if (!edge.conditional() && indegree.containsKey(edge.to())) {
                indegree.merge(edge.to(), 1, Integer::sum);
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }
        List<String> order = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        while (!ready.isEmpty()) {
            String current = ready.poll();
            if (!visited.add(current)) {
                continue;
            }
            order.add(current);
            for (Edge edge : edges) {
                if (edge.conditional() || !edge.from().equals(current)) {
                    continue;
                }
                int remaining = indegree.merge(edge.to(), -1, Integer::sum);
                if (remaining == 0 && visited.add(edge.to())) {
                    ready.add(edge.to());
                }
            }
        }
        // 环上的节点：按声明顺序追加，保证不丢节点
        for (String node : nodes.keySet()) {
            if (!order.contains(node)) {
                order.add(node);
            }
        }
        return List.copyOf(order);
    }

    @Override
    public String toString() {
        return "Workflow[" + name + ", nodes=" + nodes.keySet() + ", edges=" + edges.size() + "]";
    }

    /** 工作流构造器。 */
    public static final class Builder {
        private final String name;
        private final Map<String, WorkflowNode> nodes = new LinkedHashMap<>();
        private final List<Edge> edges = new ArrayList<>();
        private String entry;

        private Builder(String name) {
            this.name = name;
        }

        /** 添加节点（同名覆盖）。 */
        public Builder node(WorkflowNode node) {
            nodes.put(node.name(), node);
            if (entry == null) {
                entry = node.name();
            }
            return this;
        }

        /** 添加节点（便捷写法）。 */
        public Builder node(String nodeName, WorkflowNode.NodeHandler handler) {
            return node(WorkflowNode.of(nodeName, handler));
        }

        /** 无条件边。 */
        public Builder edge(String from, String to) {
            edges.add(Edge.always(from, to));
            return this;
        }

        /** 条件边。 */
        public Builder edge(String from, String to, BiPredicate<WorkflowContext, NodeOutput> condition, String label) {
            edges.add(Edge.when(from, to, condition, label));
            return this;
        }

        /** 指定入口节点。 */
        public Builder entry(String nodeName) {
            this.entry = nodeName;
            return this;
        }

        public Workflow build() {
            if (nodes.isEmpty()) {
                throw new IllegalArgumentException("工作流 " + name + " 没有任何节点");
            }
            if (entry == null || !nodes.containsKey(entry)) {
                throw new IllegalArgumentException("工作流 " + name + " 的入口节点不存在: " + entry);
            }
            for (Edge edge : edges) {
                if (!nodes.containsKey(edge.from())) {
                    throw new IllegalArgumentException("边的起点节点不存在: " + edge.from());
                }
                if (!nodes.containsKey(edge.to())) {
                    throw new IllegalArgumentException("边的终点节点不存在: " + edge.to());
                }
            }
            return new Workflow(name, Map.copyOf(nodes), List.copyOf(edges), entry);
        }
    }
}
