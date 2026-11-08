package edu.zjnu.interview.ai.agent.lab;

import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.StopReason;
import edu.zjnu.interview.ai.core.TokenCounter;
import edu.zjnu.interview.ai.core.TokenUsage;

import java.util.List;
import java.util.function.Function;

/**
 * 脚本化模型：按「剧本」逐轮返回响应。
 *
 * <p><b>为什么不用 core.mock.MockChatModel</b>：MockChatModel 按「最后一条消息的关键词」匹配，
 * 而 Agent Loop 的最后一条消息每轮都在变（用户输入 → 工具结果 → 工具结果…），
 * 用关键词匹配写多轮剧本既难读又脆弱。
 * 这里直接给一个「函数列表」，每个函数拿到当轮请求自己决定返回什么，
 * 剧本可以写成普通 Java 代码，逻辑一目了然，而且完全确定性。
 *
 * <p>token 用量照常估算，让预算与成本逻辑在离线环境里也真实生效。
 */
public final class ScriptedChatModel implements ChatModel {

    private final String name;
    private final List<Function<ChatRequest, ChatResponse>> steps;
    private int index;

    public ScriptedChatModel(String name, List<Function<ChatRequest, ChatResponse>> steps) {
        this.name = name;
        this.steps = List.copyOf(steps);
    }

    /** 单步剧本（永远返回同一个响应）。 */
    public static ScriptedChatModel constant(String name, ChatResponse response) {
        return new ScriptedChatModel(name, List.of(request -> response));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        if (steps.isEmpty()) {
            return ChatResponse.text("（空剧本）");
        }
        int position = Math.min(index, steps.size() - 1);
        index++;
        ChatResponse response = steps.get(position).apply(request);
        if (response == null) {
            response = ChatResponse.text("（剧本返回 null）");
        }
        return withUsage(response, request);
    }

    /** 已执行的轮数。 */
    public int callCount() {
        return index;
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
}
