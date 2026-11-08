package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.core.Span;
import edu.zjnu.interview.ai.core.StopReason;
import edu.zjnu.interview.ai.core.TokenUsage;
import edu.zjnu.interview.ai.core.UsageMeter;

import java.util.ArrayList;
import java.util.List;

/**
 * 带预算闸门的模型装饰器：调用前问预算，调用后记账并落 span。
 *
 * <p><b>这是「预算」从文档变成运行时约束的地方</b>。
 * 单独一个 {@link BudgetPolicy} 只是一段逻辑；只有把它插到
 * 「模型调用」这个必经之路上，预算才真正生效。
 *
 * <p>三态行为：
 * <ul>
 *   <li>ALLOW：用主模型调用；</li>
 *   <li>DEGRADE：换便宜模型调用，并记录一条降级事件（进 span、可被上层读取）；</li>
 *   <li>REJECT：不调用，返回结构化错误（{@code stopReason=ERROR}，内容是机器可读 JSON）。</li>
 * </ul>
 *
 * <p><b>为什么拒绝时返回结构化错误而不是抛异常</b>：
 * 异常会一路冒到用户面前变成 500；而预算耗尽是一个<b>正常的业务结果</b>，
 * 上层 Agent 完全可以据此换策略（缩短上下文、换更便宜的模型、告诉用户额度用完了）。
 */
public final class BudgetAwareChatModel implements ChatModel {

    private final ChatModel primary;
    private final ChatModel degraded;
    private final BudgetPolicy policy;
    private final UsageMeter meter;
    private final Tracer tracer;
    private final BudgetPolicy.RequestBudget requestBudget;
    private final ModelPricing pricing;
    private final List<DegradationEvent> degradations = new ArrayList<>();

    public BudgetAwareChatModel(ChatModel primary,
                                ChatModel degraded,
                                BudgetPolicy policy,
                                UsageMeter meter,
                                Tracer tracer) {
        this(primary, degraded, policy, meter, tracer, null, ModelPricing.defaults());
    }

    public BudgetAwareChatModel(ChatModel primary,
                                ChatModel degraded,
                                BudgetPolicy policy,
                                UsageMeter meter,
                                Tracer tracer,
                                BudgetPolicy.RequestBudget requestBudget) {
        this(primary, degraded, policy, meter, tracer, requestBudget, ModelPricing.defaults());
    }

    public BudgetAwareChatModel(ChatModel primary,
                                ChatModel degraded,
                                BudgetPolicy policy,
                                UsageMeter meter,
                                Tracer tracer,
                                BudgetPolicy.RequestBudget requestBudget,
                                ModelPricing pricing) {
        this.primary = primary;
        this.degraded = degraded;
        this.policy = policy;
        this.meter = meter;
        this.tracer = tracer;
        this.requestBudget = requestBudget;
        this.pricing = pricing == null ? ModelPricing.defaults() : pricing;
    }

    @Override
    public String name() {
        return primary.name();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        UsageSnapshot usage = requestBudget == null ? UsageSnapshot.ZERO : requestBudget.snapshot();
        BudgetDecision decision = policy.decide(request.model(), usage);

        if (decision.rejected()) {
            if (tracer != null) {
                try (Tracer.Scope scope = tracer.span("llm.chat", Span.KIND_LLM)) {
                    scope.attr(SpanAttrs.BUDGET_ACTION, decision.action().name());
                    scope.attr(SpanAttrs.BUDGET_SPENT_USD, decision.spentUsd());
                    scope.attr(SpanAttrs.BUDGET_LIMIT_USD, decision.usdLimit());
                    scope.markError("BUDGET_EXCEEDED", decision.reason());
                }
            }
            return new ChatResponse(Json.write(decision.toErrorPayload()), List.of(), TokenUsage.ZERO,
                    StopReason.ERROR, 0L);
        }

        ChatModel target = primary;
        ChatRequest effective = request;
        if (decision.degraded()) {
            if (degraded == null) {
                // 没有配置便宜模型：只能继续用主模型，但把降级意图记录下来，避免「静默超支」。
                if (tracer != null) {
                    try (Tracer.Scope scope = tracer.span("llm.degrade", Span.KIND_LLM)) {
                        scope.attr(SpanAttrs.DEGRADED, true);
                        scope.attr(SpanAttrs.BUDGET_ACTION, decision.action().name());
                        scope.markError("NO_DEGRADE_TARGET", decision.reason());
                    }
                }
            } else {
                target = degraded;
                effective = request.withModel(decision.effectiveModel());
                if (decision.degradation() != null) {
                    degradations.add(decision.degradation());
                }
            }
        }

        ChatResponse response;
        if (tracer != null) {
            try (Tracer.Scope scope = tracer.span("llm.chat", Span.KIND_LLM)) {
                scope.attr(SpanAttrs.MODEL, target.name());
                if (decision.degraded()) {
                    scope.attr(SpanAttrs.DEGRADED, true);
                    scope.attr(SpanAttrs.DEGRADED_TO, target.name());
                    if (decision.degradation() != null) {
                        scope.attr(SpanAttrs.DEGRADED_FROM, decision.degradation().fromModel());
                        // 单独落一条降级 span：降级是「有代价的妥协」，
                        // 必须能被独立统计（发生率、影响的请求数），而不是只挂在父 span 的属性上。
                        try (Tracer.Scope degradeScope = tracer.span("llm.degrade", Span.KIND_LLM)) {
                            degradeScope.attr(SpanAttrs.DEGRADED, true);
                            degradeScope.attr(SpanAttrs.DEGRADED_FROM, decision.degradation().fromModel());
                            degradeScope.attr(SpanAttrs.DEGRADED_TO, decision.degradation().toModel());
                            degradeScope.attr(SpanAttrs.BUDGET_ACTION, decision.action().name());
                            degradeScope.markError("BUDGET_DEGRADED", decision.reason());
                        }
                    }
                }
                response = target.chat(effective);
                scope.attr(SpanAttrs.STOP_REASON, response.stopReason().name());
                scope.attr(SpanAttrs.PROMPT_TOKENS, response.usage().promptTokens());
                scope.attr(SpanAttrs.COMPLETION_TOKENS, response.usage().completionTokens());
                scope.attr(SpanAttrs.TOTAL_TOKENS, response.usage().totalTokens());
                if (meter != null) {
                    meter.charge(target.name(), response.usage());
                    scope.attr(SpanAttrs.COST_USD, pricing.costUsd(target.name(), response.usage()));
                }
                if (response.stopReason() == StopReason.ERROR) {
                    scope.markError(response.content());
                }
            }
        } else {
            response = target.chat(effective);
            if (meter != null) {
                meter.charge(target.name(), response.usage());
            }
        }

        if (requestBudget != null) {
            requestBudget.charge(target.name(), response.usage());
        }
        return response;
    }

    /** 已发生的降级事件。 */
    public List<DegradationEvent> degradations() {
        return List.copyOf(degradations);
    }
}
