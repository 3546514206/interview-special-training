package edu.zjnu.interview.ai.agent.workflow;

import java.time.Duration;

/**
 * 工作流节点：名字 + 处理函数 + 超时 + 重试次数。
 *
 * <p><b>为什么超时与重试要挂在节点上，而不是全局配置</b>：
 * 工作流里的节点代价差异巨大——调外部 API 可能要 3 秒，本地计算只要 1 毫秒。
 * 统一超时要么把慢节点掐死，要么把快节点的卡死拖成全局事故。
 * 「重试次数同理」：读接口可以重试 3 次，写操作重试 3 次就是重复扣款，
 * 所以重试次数必须是节点级决策。
 *
 * <p>重试<b>不带退避间隔</b>：本工程要求离线可跑、测试确定性，
 * 加 sleep 会让测试变慢且引入时序抖动。生产环境应加指数退避 + 抖动。
 */
public final class WorkflowNode {

    /**
     * 节点处理函数。
     */
    @FunctionalInterface
    public interface NodeHandler {
        /**
         * 处理节点逻辑。
         *
         * @param ctx 工作流上下文（可读写状态）
         * @return 节点输出
         * @throws Exception 抛出异常即视为该次尝试失败，会触发重试
         */
        NodeOutput handle(WorkflowContext ctx) throws Exception;
    }

    private final String name;
    private final NodeHandler handler;
    private final Duration timeout;
    private final int retries;

    private WorkflowNode(String name, NodeHandler handler, Duration timeout, int retries) {
        this.name = name;
        this.handler = handler;
        this.timeout = timeout;
        this.retries = retries;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** 便捷构造：只给名字和处理函数。 */
    public static WorkflowNode of(String name, NodeHandler handler) {
        return new WorkflowNode(name, handler, null, 0);
    }

    public String name() {
        return name;
    }

    public NodeHandler handler() {
        return handler;
    }

    /** 超时；null 表示不限制。 */
    public Duration timeout() {
        return timeout;
    }

    /** 失败后的重试次数（不含首次执行）。 */
    public int retries() {
        return retries;
    }

    @Override
    public String toString() {
        return "WorkflowNode[" + name + "]";
    }

    /** 节点构造器。 */
    public static final class Builder {
        private final String name;
        private NodeHandler handler;
        private Duration timeout;
        private int retries;

        private Builder(String name) {
            this.name = name;
        }

        public Builder handler(NodeHandler value) {
            this.handler = value;
            return this;
        }

        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        public Builder retries(int value) {
            this.retries = Math.max(0, value);
            return this;
        }

        public WorkflowNode build() {
            if (handler == null) {
                throw new IllegalArgumentException("节点 " + name + " 缺少处理函数");
            }
            return new WorkflowNode(name, handler, timeout, retries);
        }
    }
}
