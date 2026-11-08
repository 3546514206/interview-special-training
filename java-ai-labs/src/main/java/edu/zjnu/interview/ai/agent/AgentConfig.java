package edu.zjnu.interview.ai.agent;

import edu.zjnu.interview.ai.agent.memory.ConversationState;
import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ToolAuthorizer;
import edu.zjnu.interview.ai.core.TraceSink;
import edu.zjnu.interview.ai.core.UsageMeter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Agent Loop 的配置：一处声明「能跑多远、能不能花钱、能碰什么」。
 *
 * <p><b>为什么用不可变对象 + builder</b>：Agent 的行为受太多开关影响
 * （步数、预算、权限、工具集、工作目录…）。如果用 setter 改，
 * 就可能出现「跑了一半改了配置」这种无法复现的状态。
 * 不可变配置 + 显式构造，是让实验可回归的前提。
 *
 * <p>几个默认值的取舍：
 * <ul>
 *   <li>{@code maxSteps=8}：多数任务 3~5 步收敛，8 步足够覆盖「检索→计算→校验」这类组合，
 *       又能在模型跑偏时及时止损；</li>
 *   <li>{@code repeatCallThreshold=3}：同一工具同一参数连续 3 次几乎必然是死循环，
 *       阈值太小会误伤「查三次不同分页」这类合法重试；</li>
 *   <li>{@code maxConsecutiveToolFailures=3}：连续 3 次工具失败说明工具坏了或模型参数一直不对，
 *       再试也是浪费预算。</li>
 * </ul>
 */
public final class AgentConfig {

    private final ChatModel model;
    private final ToolRegistry tools;
    private final ToolAuthorizer authorizer;
    private final TraceSink traceSink;
    private final UsageMeter usageMeter;
    private final int maxSteps;
    private final int maxTotalTokens;
    private final double maxCostUsd;
    private final int repeatCallThreshold;
    private final int maxConsecutiveToolFailures;
    private final Duration toolTimeout;
    private final Path workingDirectory;
    private final ConversationState state;
    private final double temperature;
    private final int maxOutputTokens;

    private AgentConfig(Builder builder) {
        this.model = builder.model;
        this.tools = builder.tools;
        this.authorizer = builder.authorizer;
        this.traceSink = builder.traceSink;
        this.usageMeter = builder.usageMeter;
        this.maxSteps = builder.maxSteps;
        this.maxTotalTokens = builder.maxTotalTokens;
        this.maxCostUsd = builder.maxCostUsd;
        this.repeatCallThreshold = builder.repeatCallThreshold;
        this.maxConsecutiveToolFailures = builder.maxConsecutiveToolFailures;
        this.toolTimeout = builder.toolTimeout;
        this.workingDirectory = builder.workingDirectory;
        this.state = builder.state;
        this.temperature = builder.temperature;
        this.maxOutputTokens = builder.maxOutputTokens;
    }

    public static Builder builder(ChatModel model, ToolRegistry tools) {
        return new Builder(model, tools);
    }

    public ChatModel model() {
        return model;
    }

    public ToolRegistry tools() {
        return tools;
    }

    public ToolAuthorizer authorizer() {
        return authorizer;
    }

    public TraceSink traceSink() {
        return traceSink;
    }

    public UsageMeter usageMeter() {
        return usageMeter;
    }

    public int maxSteps() {
        return maxSteps;
    }

    /** token 预算上限；{@code <=0} 表示不限制。 */
    public int maxTotalTokens() {
        return maxTotalTokens;
    }

    /** 成本上限（美元）；{@link Double#MAX_VALUE} 表示不限制。 */
    public double maxCostUsd() {
        return maxCostUsd;
    }

    public int repeatCallThreshold() {
        return repeatCallThreshold;
    }

    public int maxConsecutiveToolFailures() {
        return maxConsecutiveToolFailures;
    }

    /** 单次工具执行超时；null 表示不限制。 */
    public Duration toolTimeout() {
        return toolTimeout;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    /** 外部注入的会话状态（可为 null，由 Loop 自己创建）。 */
    public ConversationState state() {
        return state;
    }

    public double temperature() {
        return temperature;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    /** 配置构造器。 */
    public static final class Builder {
        private final ChatModel model;
        private final ToolRegistry tools;
        private ToolAuthorizer authorizer = ToolAuthorizer.allowAll();
        private TraceSink traceSink = TraceSink.noop();
        private UsageMeter usageMeter = UsageMeter.noop();
        private int maxSteps = 8;
        private int maxTotalTokens = -1;
        private double maxCostUsd = Double.MAX_VALUE;
        private int repeatCallThreshold = 3;
        private int maxConsecutiveToolFailures = 3;
        private Duration toolTimeout = Duration.ofSeconds(30);
        private Path workingDirectory = Paths.get("").toAbsolutePath();
        private ConversationState state;
        private double temperature = 0.2;
        private int maxOutputTokens = 1024;

        private Builder(ChatModel model, ToolRegistry tools) {
            this.model = model;
            this.tools = tools == null ? new ToolRegistry() : tools;
        }

        public Builder authorizer(ToolAuthorizer value) {
            this.authorizer = value == null ? ToolAuthorizer.allowAll() : value;
            return this;
        }

        public Builder traceSink(TraceSink value) {
            this.traceSink = value == null ? TraceSink.noop() : value;
            return this;
        }

        public Builder usageMeter(UsageMeter value) {
            this.usageMeter = value == null ? UsageMeter.noop() : value;
            return this;
        }

        public Builder maxSteps(int value) {
            this.maxSteps = Math.max(1, value);
            return this;
        }

        public Builder maxTotalTokens(int value) {
            this.maxTotalTokens = value;
            return this;
        }

        public Builder maxCostUsd(double value) {
            this.maxCostUsd = value;
            return this;
        }

        public Builder repeatCallThreshold(int value) {
            this.repeatCallThreshold = Math.max(2, value);
            return this;
        }

        public Builder maxConsecutiveToolFailures(int value) {
            this.maxConsecutiveToolFailures = Math.max(1, value);
            return this;
        }

        public Builder toolTimeout(Duration value) {
            this.toolTimeout = value;
            return this;
        }

        public Builder workingDirectory(Path value) {
            this.workingDirectory = value == null ? Paths.get("").toAbsolutePath() : value;
            return this;
        }

        public Builder state(ConversationState value) {
            this.state = value;
            return this;
        }

        public Builder temperature(double value) {
            this.temperature = value;
            return this;
        }

        public Builder maxOutputTokens(int value) {
            this.maxOutputTokens = Math.max(16, value);
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(this);
        }
    }
}
