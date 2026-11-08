package edu.zjnu.interview.ai.core.mock;

import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.StopReason;
import edu.zjnu.interview.ai.core.TokenCounter;
import edu.zjnu.interview.ai.core.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 确定性 Mock 模型：没有 API Key 也能跑通整条 Agent Loop。
 *
 * <p>匹配规则很简单：<b>只看最后一条消息</b>（可按角色过滤），命中第一个包含关键词的规则就返回对应响应。
 * 「只看最后一条」是有意为之——它让脚本化多轮流程（用户提问 → 模型调工具 → 工具结果回灌 → 模型收尾）
 * 可以用不同的关键词分别命中不同分支，同时保持完全可复现。
 *
 * <p>token 用量会被自动估算并回填，这样成本核算、预算控制这些逻辑在离线环境下也是真实生效的。
 */
public final class MockChatModel implements ChatModel {

    private final String name;
    private final List<Rule> rules;
    private final ChatResponse fallback;
    private final List<ChatRequest> requests = new ArrayList<>();

    /**
     * 一条匹配规则。
     *
     * @param role     只在该角色上匹配；null 表示不限角色
     * @param trigger  关键词（大小写不敏感的子串匹配）
     * @param response 命中后的响应
     */
    public record Rule(ChatMessage.Role role, String trigger, ChatResponse response) {
    }

    private MockChatModel(String name, List<Rule> rules, ChatResponse fallback) {
        this.name = name;
        this.rules = List.copyOf(rules);
        this.fallback = fallback;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** 规则构造器。 */
    public static final class Builder {
        private final String name;
        private final List<Rule> rules = new ArrayList<>();
        private ChatResponse fallback = ChatResponse.text("（mock 无匹配规则）");

        private Builder(String name) {
            this.name = name;
        }

        /** 任意角色下，末条消息包含 trigger 即命中。 */
        public Builder on(String trigger, ChatResponse response) {
            rules.add(new Rule(null, trigger, response));
            return this;
        }

        /** 指定角色下匹配。 */
        public Builder on(ChatMessage.Role role, String trigger, ChatResponse response) {
            rules.add(new Rule(role, trigger, response));
            return this;
        }

        public Builder fallback(ChatResponse response) {
            this.fallback = response;
            return this;
        }

        public MockChatModel build() {
            return new MockChatModel(name, rules, fallback);
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        requests.add(request);
        ChatMessage last = request.lastMessage();
        String probe = last == null ? "" : last.content();
        ChatMessage.Role role = last == null ? null : last.role();

        ChatResponse matched = null;
        for (Rule rule : rules) {
            if (rule.role() != null && rule.role() != role) {
                continue;
            }
            if (probe.toLowerCase(Locale.ROOT).contains(rule.trigger().toLowerCase(Locale.ROOT))) {
                matched = rule.response();
                break;
            }
        }
        ChatResponse response = matched == null ? fallback : matched;
        return withUsage(response, request);
    }

    private ChatResponse withUsage(ChatResponse response, ChatRequest request) {
        TokenUsage usage = response.usage();
        if (usage.totalTokens() == 0) {
            int promptTokens = TokenCounter.estimateMessages(request.messages());
            int completionTokens = TokenCounter.estimate(response.content());
            for (var call : response.toolCalls()) {
                completionTokens += TokenCounter.estimate(call.name())
                        + TokenCounter.estimate(call.argumentsJson());
            }
            usage = TokenUsage.of(promptTokens, completionTokens);
        }
        StopReason reason = response.stopReason();
        if (response.hasToolCalls() && reason == StopReason.END) {
            reason = StopReason.TOOL_CALLS;
        }
        return new ChatResponse(response.content(), response.toolCalls(), usage, reason, response.latencyMillis());
    }

    /** 已收到的请求序列（测试断言用）。 */
    public List<ChatRequest> requests() {
        return List.copyOf(requests);
    }

    public int callCount() {
        return requests.size();
    }
}
