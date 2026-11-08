package edu.zjnu.interview.ai.agent.multi;

import edu.zjnu.interview.ai.agent.AgentConfig;
import edu.zjnu.interview.ai.agent.AgentLoop;
import edu.zjnu.interview.ai.agent.AgentResult;
import edu.zjnu.interview.ai.agent.ToolRegistry;
import edu.zjnu.interview.ai.agent.memory.ConversationState;
import edu.zjnu.interview.ai.agent.memory.MemoryStore;
import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ToolAuthorizer;
import edu.zjnu.interview.ai.core.TraceSink;
import edu.zjnu.interview.ai.core.UsageMeter;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 专家 Agent（Specialist Agent）：有独立工具集、独立 system prompt、独立记忆作用域。
 *
 * <p><b>多 Agent 体系里「专家」到底指什么</b>：不是「换个模型再问一遍」，
 * 而是<b>一个能力边界受限的子 Agent</b>——它有自己的工具集（检索专家只能检索、算数专家只能计算），
 * 有自己的角色提示（system prompt），有自己的记忆作用域。
 * 三者隔离的意义：
 * <ul>
 *   <li><b>工具隔离</b>：把「能不能做某件事」变成运行时可裁决的策略，而不是提示词里的君子协定；
 *       检索专家拿不到写文件的工具，就不可能误删数据。</li>
 *   <li><b>提示隔离</b>：不同角色的关注点不同，塞进一个 prompt 会互相稀释注意力。</li>
 *   <li><b>记忆隔离</b>：专家之间通过共享记忆交换「事实」，但不共享各自的中间过程，
 *       避免上下文互相污染，也让每个专家的上下文保持短小。</li>
 * </ul>
 *
 * <p>专家跑完后会把结论写回 {@link MemoryStore}（用户级作用域），
 * 供其它专家与 Supervisor 读取——这是专家之间<b>唯一</b>的通信通道，
 * 比让专家互相调用更可控（没有循环依赖、没有隐式状态）。
 */
public final class SpecialistAgent {

    private final String name;
    private final String role;
    private final String systemPrompt;
    private final List<String> keywords;
    private final ToolRegistry tools;
    private final AgentLoop loop;
    private final MemoryStore memory;
    private final String memoryKeyPrefix;
    private final boolean generalist;

    private SpecialistAgent(Builder builder) {
        this.name = builder.name;
        this.role = builder.role;
        this.systemPrompt = builder.systemPrompt;
        this.keywords = List.copyOf(builder.keywords);
        this.tools = builder.tools;
        this.memory = builder.memory;
        this.memoryKeyPrefix = builder.memoryKeyPrefix;
        this.generalist = builder.generalist;

        ConversationState state = ConversationState.of(this.systemPrompt,
                builder.tokenBudget, builder.compactor);
        AgentConfig config = AgentConfig.builder(builder.model, this.tools)
                .authorizer(builder.authorizer)
                .traceSink(builder.traceSink)
                .usageMeter(builder.usageMeter)
                .maxSteps(builder.maxSteps)
                .workingDirectory(builder.workingDirectory)
                .state(state)
                .build();
        this.loop = new AgentLoop(config);
    }

    public static Builder builder(String name, String role, ChatModel model, ToolRegistry tools) {
        return new Builder(name, role, model, tools);
    }

    public String name() {
        return name;
    }

    /** 角色描述（Supervisor 用它来分派任务）。 */
    public String role() {
        return role;
    }

    /** 关键词（确定性路由器用它来匹配任务）。 */
    public List<String> keywords() {
        return keywords;
    }

    /** 是否是「通用专家」（所有任务兜底分派给它）。 */
    public boolean generalist() {
        return generalist;
    }

    public ToolRegistry tools() {
        return tools;
    }

    public AgentLoop loop() {
        return loop;
    }

    /** 专家的会话状态（独立于其它专家）。 */
    public ConversationState state() {
        return loop.state();
    }

    /**
     * 执行一个子任务。
     *
     * @param task 任务描述
     */
    public AgentResult run(String task) {
        String effectiveTask = buildTask(task);
        AgentResult result = loop.run(effectiveTask);
        remember(result);
        return result;
    }

    /** 把「已知事实」拼进任务里——这是专家读取共享记忆的方式。 */
    private String buildTask(String task) {
        StringBuilder sb = new StringBuilder();
        String facts = recallFacts();
        if (!facts.isBlank()) {
            sb.append("【已知事实】\n").append(facts).append('\n');
        }
        sb.append("【任务】\n").append(task == null ? "" : task);
        return sb.toString();
    }

    /** 读取共享记忆里属于自己的事实。 */
    private String recallFacts() {
        if (memory == null) {
            return "";
        }
        List<MemoryStore.Fact> facts = memory.find(taskKeywordsProbe(), MemoryStore.Scope.USER);
        if (facts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (MemoryStore.Fact fact : facts) {
            sb.append("- ").append(fact.key()).append(": ").append(fact.value()).append('\n');
        }
        return sb.toString().strip();
    }

    /** 用专家的关键词做一次模糊检索，捞出相关事实。 */
    private String taskKeywordsProbe() {
        return String.join(" ", keywords);
    }

    /** 把本次结论写回共享记忆。 */
    private void remember(AgentResult result) {
        if (memory == null || result == null || result.finalAnswer().isBlank()) {
            return;
        }
        String key = memoryKeyPrefix + ":lastConclusion";
        String value = result.finalAnswer().length() > 300
                ? result.finalAnswer().substring(0, 300) + "…"
                : result.finalAnswer();
        memory.put(key, value, MemoryStore.Scope.USER, Duration.ofHours(24));
    }

    /** 专家构造器。 */
    public static final class Builder {
        private final String name;
        private final String role;
        private final ChatModel model;
        private final ToolRegistry tools;
        private String systemPrompt = "";
        private List<String> keywords = List.of();
        private MemoryStore memory;
        private String memoryKeyPrefix;
        private boolean generalist;
        private ToolAuthorizer authorizer = ToolAuthorizer.allowAll();
        private TraceSink traceSink = TraceSink.noop();
        private UsageMeter usageMeter = UsageMeter.noop();
        private int maxSteps = 6;
        private int tokenBudget = ConversationState.UNLIMITED;
        private edu.zjnu.interview.ai.agent.memory.MemoryCompactor compactor;
        private Path workingDirectory;

        private Builder(String name, String role, ChatModel model, ToolRegistry tools) {
            this.name = name;
            this.role = role;
            this.model = model;
            this.tools = tools == null ? new ToolRegistry() : tools;
            this.memoryKeyPrefix = "agent:" + name;
        }

        /** 独立 system prompt。 */
        public Builder systemPrompt(String value) {
            this.systemPrompt = value == null ? "" : value;
            return this;
        }

        /** 关键词（确定性路由器用）。 */
        public Builder keywords(String... values) {
            this.keywords = List.of(values);
            return this;
        }

        /** 共享记忆库（专家之间通过它交换事实）。 */
        public Builder memory(MemoryStore value) {
            this.memory = value;
            return this;
        }

        /** 记忆键前缀（默认 {@code agent:<name>}）。 */
        public Builder memoryKeyPrefix(String value) {
            this.memoryKeyPrefix = value;
            return this;
        }

        /** 是否是兜底专家。 */
        public Builder generalist(boolean value) {
            this.generalist = value;
            return this;
        }

        public Builder authorizer(ToolAuthorizer value) {
            this.authorizer = value;
            return this;
        }

        public Builder traceSink(TraceSink value) {
            this.traceSink = value;
            return this;
        }

        public Builder usageMeter(UsageMeter value) {
            this.usageMeter = value;
            return this;
        }

        public Builder maxSteps(int value) {
            this.maxSteps = value;
            return this;
        }

        public Builder tokenBudget(int value) {
            this.tokenBudget = value;
            return this;
        }

        public Builder compactor(edu.zjnu.interview.ai.agent.memory.MemoryCompactor value) {
            this.compactor = value;
            return this;
        }

        public Builder workingDirectory(Path value) {
            this.workingDirectory = value;
            return this;
        }

        public SpecialistAgent build() {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("专家必须有名字");
            }
            if (systemPrompt.isBlank()) {
                systemPrompt = "你是「" + name + "」专家，职责：" + role + "。只使用分配给你的工具完成任务。";
            }
            return new SpecialistAgent(this);
        }
    }
}
