package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.StopReason;
import edu.zjnu.interview.ai.core.TokenUsage;
import edu.zjnu.interview.ai.core.mock.MockChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成本与预算回归：软阈值降级、硬阈值拒绝、会话 / 请求两种 scope。
 */
class BudgetPolicyTest {

    private static final ChatRequest REQUEST = ChatRequest.of(
            List.of(ChatMessage.user("解释一下货币乘数"))).withModel("qwen-plus");

    @Test
    @DisplayName("软阈值：达到 80% 预算 → 降级到便宜模型并产出降级事件")
    void softThresholdTriggersDegrade() {
        CostMeter meter = new CostMeter();
        // qwen-plus 输入 0.11 / 百万 token：80k token ≈ $0.0088 → 占 0.01 预算的 88%
        meter.charge("qwen-plus", TokenUsage.of(80000, 0));

        BudgetPolicy policy = BudgetPolicy.builder()
                .scope(BudgetPolicy.Scope.SESSION)
                .usdLimit(0.01)
                .tokenLimit(100000)
                .softRatio(0.8)
                .degradedModel("qwen-turbo")
                .sessionMeter(meter)
                .build();

        BudgetDecision decision = policy.decide("qwen-plus", UsageSnapshot.ZERO);
        assertEquals(BudgetPolicy.Action.DEGRADE, decision.action());
        assertEquals("qwen-turbo", decision.effectiveModel());
        assertTrue(decision.degraded());
        assertTrue(decision.allowed(), "降级不等于拒绝");
        assertNotNull(decision.degradation());
        assertEquals("budget_soft_threshold", decision.degradation().trigger());
        assertEquals("qwen-plus", decision.degradation().fromModel());
    }

    @Test
    @DisplayName("硬阈值：超过预算 → 拒绝并返回结构化错误")
    void hardThresholdRejects() {
        CostMeter meter = new CostMeter();
        meter.charge("qwen-plus", TokenUsage.of(200000, 0));

        BudgetPolicy policy = BudgetPolicy.builder()
                .scope(BudgetPolicy.Scope.SESSION)
                .usdLimit(0.01)
                .softRatio(0.8)
                .degradedModel("qwen-turbo")
                .sessionMeter(meter)
                .build();

        BudgetDecision decision = policy.decide("qwen-plus", UsageSnapshot.ZERO);
        assertEquals(BudgetPolicy.Action.REJECT, decision.action());
        assertTrue(decision.rejected());
        assertFalse(decision.allowed());
        assertEquals("BUDGET_EXCEEDED", decision.toErrorPayload().get("error_code"));
        assertTrue(String.valueOf(decision.reason()).contains("预算"));
    }

    @Test
    @DisplayName("token 上限同样可以触发硬阈值拒绝")
    void tokenLimitTriggersReject() {
        BudgetPolicy policy = BudgetPolicy.forRequest(100.0, 1000);
        BudgetDecision decision = policy.decide("gpt-4o", UsageSnapshot.of(0.0, 1200));
        assertEquals(BudgetPolicy.Action.REJECT, decision.action());
        assertTrue(String.valueOf(decision.reason()).contains("token"));
    }

    @Test
    @DisplayName("请求级 scope 使用调用方传入的用量；会话级 scope 以计量器为准")
    void scopeDeterminesWhichUsageCounts() {
        BudgetPolicy requestPolicy = BudgetPolicy.forRequest(0.01, 0);
        assertEquals(BudgetPolicy.Action.ALLOW, requestPolicy.decide("gpt-4o", UsageSnapshot.of(0.001, 10)).action());
        // 没有配置降级目标模型时，即使越过软阈值也只能放行（不能凭空换模型）
        assertEquals(BudgetPolicy.Action.ALLOW, requestPolicy.decide("gpt-4o", UsageSnapshot.of(0.009, 10)).action(),
                "无降级目标时直接放行");

        CostMeter meter = new CostMeter();
        meter.charge("gpt-4o", TokenUsage.of(100000, 0));
        BudgetPolicy sessionPolicy = BudgetPolicy.forSession(meter, 0.01, 0);
        BudgetDecision decision = sessionPolicy.decide("gpt-4o", UsageSnapshot.of(0.0, 0));
        assertEquals(BudgetPolicy.Action.REJECT, decision.action(), "会话级以计量器为准");
    }

    @Test
    @DisplayName("BudgetAwareChatModel：降级时真的换模型，并落降级 span")
    void budgetAwareModelDegradesToCheapModel() {
        CostMeter meter = new CostMeter();
        meter.charge("qwen-plus", TokenUsage.of(80000, 0));
        BudgetPolicy policy = BudgetPolicy.builder()
                .scope(BudgetPolicy.Scope.SESSION)
                .usdLimit(0.01)
                .softRatio(0.8)
                .degradedModel("qwen-turbo")
                .sessionMeter(meter)
                .build();

        MockChatModel expensive = MockChatModel.builder("qwen-plus")
                .fallback(ChatResponse.text("【贵模型】")).build();
        MockChatModel cheap = MockChatModel.builder("qwen-turbo")
                .fallback(ChatResponse.text("【便宜模型】")).build();

        InMemoryTraceSink sink = new InMemoryTraceSink();
        Tracer tracer = new Tracer(sink);
        BudgetAwareChatModel model = new BudgetAwareChatModel(expensive, cheap, policy, meter, tracer);

        ChatResponse response = model.chat(REQUEST);
        assertEquals("【便宜模型】", response.content(), "应当使用降级后的模型");
        assertEquals(1, model.degradations().size());
        assertEquals("qwen-turbo", model.degradations().get(0).toModel());
        assertEquals(1, sink.spansOfName("llm.degrade").size(), "降级事件必须落 span");
        assertEquals(Boolean.TRUE, sink.spansOfName("llm.chat").get(0).attributes().get(SpanAttrs.DEGRADED));
    }

    @Test
    @DisplayName("BudgetAwareChatModel：拒绝时不调用模型，返回结构化错误")
    void budgetAwareModelRejectsWithoutCallingModel() {
        CostMeter meter = new CostMeter();
        meter.charge("qwen-plus", TokenUsage.of(200000, 0));
        BudgetPolicy policy = BudgetPolicy.builder()
                .scope(BudgetPolicy.Scope.SESSION)
                .usdLimit(0.01)
                .degradedModel("qwen-turbo")
                .sessionMeter(meter)
                .build();

        MockChatModel expensive = MockChatModel.builder("qwen-plus")
                .fallback(ChatResponse.text("不该被调用")).build();
        BudgetAwareChatModel model = new BudgetAwareChatModel(expensive, null, policy, meter, null);

        ChatResponse response = model.chat(REQUEST);
        assertEquals(StopReason.ERROR, response.stopReason());
        assertTrue(response.content().contains("BUDGET_EXCEEDED"));
        assertEquals(0, expensive.callCount(), "被拒绝时不应调用底层模型");
    }
}
