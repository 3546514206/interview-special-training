package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.Span;
import edu.zjnu.interview.ai.core.StopReason;
import edu.zjnu.interview.ai.core.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 降级链模型：主模型失败 / 超时 → 依次尝试备选模型。
 *
 * <p><b>降级与重试的区别</b>（面试常问）：
 * <ul>
 *   <li><b>重试</b>是「同一个模型再试一次」——前提是这个模型可能只是抖了一下；</li>
 *   <li><b>降级</b>是「换一个能力/成本/稳定性不同的模型」——前提是这个模型本身出了问题
 *       （限流、配额耗尽、区域故障）。</li>
 * </ul>
 * 所以正确顺序通常是：<b>先对主模型做有限重试，重试仍失败再降级</b>。
 * 本类只负责降级，重试由 {@link RetryingChatModel} 负责，两者可以叠加使用。
 *
 * <p><b>降级事件必须落 span</b>：降级是「有代价的妥协」，
 * 不落 trace 就无法回答「今天有多少请求被降级了」「降级后用户的回答质量下降了多少」。
 *
 * <p><b>绝不抛异常</b>：整个链路最终失败时返回结构化错误响应，
 * 而不是把异常抛给上层（见 {@code ChatModel} SPI 契约）。
 */
public final class FallbackChatModel implements ChatModel {

    private final ChatModel primary;
    private final List<ChatModel> fallbacks;
    private final Tracer tracer;
    private final List<DegradationEvent> degradations = new CopyOnWriteArrayList<>();
    private final FailureClassifier classifier = FailureClassifier.defaultClassifier();

    public FallbackChatModel(ChatModel primary, ChatModel... fallbacks) {
        this(primary, List.of(fallbacks), null);
    }

    public FallbackChatModel(ChatModel primary, List<ChatModel> fallbacks) {
        this(primary, fallbacks, null);
    }

    public FallbackChatModel(ChatModel primary, List<ChatModel> fallbacks, Tracer tracer) {
        if (primary == null) {
            throw new IllegalArgumentException("主模型不能为空");
        }
        this.primary = primary;
        this.fallbacks = List.copyOf(fallbacks);
        this.tracer = tracer;
    }

    @Override
    public String name() {
        return primary.name();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        if (tracer == null) {
            return chain(request).response();
        }
        try (Tracer.Scope scope = tracer.span("llm.chat", Span.KIND_LLM)) {
            ChainResult result = chain(request);
            scope.attr(SpanAttrs.MODEL, result.effectiveModel());
            scope.attr(SpanAttrs.STOP_REASON, result.response().stopReason().name());
            scope.attr(SpanAttrs.PROMPT_TOKENS, result.response().usage().promptTokens());
            scope.attr(SpanAttrs.COMPLETION_TOKENS, result.response().usage().completionTokens());
            scope.attr(SpanAttrs.TOTAL_TOKENS, result.response().usage().totalTokens());
            if (result.degraded()) {
                scope.attr(SpanAttrs.DEGRADED, true);
                scope.attr(SpanAttrs.DEGRADED_FROM, primary.name());
                scope.attr(SpanAttrs.DEGRADED_TO, result.effectiveModel());
            }
            if (result.response().stopReason() == StopReason.ERROR) {
                scope.markError(result.response().content());
            }
            return result.response();
        }
    }

    private ChainResult chain(ChatRequest request) {
        ChatResponse primaryResponse = attempt(primary, request);
        if (isSuccess(primaryResponse)) {
            return new ChainResult(primaryResponse, primary.name(), false);
        }
        FailureCategory category = classifier.categorize(primaryResponse);
        String reason = ModelErrors.messageOf(primaryResponse.content());

        for (ChatModel fallback : fallbacks) {
            ChatResponse response = attempt(fallback, request.withModel(fallback.name()));
            if (isSuccess(response)) {
                DegradationEvent event = new DegradationEvent(primary.name(), fallback.name(),
                        "主模型失败（" + category + "）：" + reason, "fallback_model", 0.0, 0.0, 0L, 0L);
                degradations.add(event);
                emitDegradation(event, category);
                return new ChainResult(response, fallback.name(), true);
            }
        }

        // 全部失败：返回结构化错误
        String payload = ModelErrors.payload(category, 0,
                "主模型与 " + fallbacks.size() + " 个备选模型均失败：" + reason);
        return new ChainResult(new ChatResponse(payload, List.of(), TokenUsage.ZERO, StopReason.ERROR, 0L),
                primary.name(), false);
    }

    private void emitDegradation(DegradationEvent event, FailureCategory category) {
        if (tracer == null) {
            return;
        }
        try (Tracer.Scope scope = tracer.span("llm.degrade", Span.KIND_LLM)) {
            scope.attr(SpanAttrs.DEGRADED, true);
            scope.attr(SpanAttrs.DEGRADED_FROM, event.fromModel());
            scope.attr(SpanAttrs.DEGRADED_TO, event.toModel());
            scope.attr(SpanAttrs.FAILURE_CATEGORY, category.name());
            scope.markError(category.name(), event.reason());
        }
    }

    /** 调用一个模型；把异常转成结构化错误响应（模型实现不应该抛异常，但我们要兜底）。 */
    private ChatResponse attempt(ChatModel model, ChatRequest request) {
        try {
            ChatResponse response = model.chat(request);
            return response == null
                    ? new ChatResponse(ModelErrors.payload(FailureCategory.UNKNOWN, 0, "模型返回空响应"),
                    List.of(), TokenUsage.ZERO, StopReason.ERROR, 0L)
                    : response;
        } catch (RuntimeException e) {
            FailureCategory category = classifier.categorize(e);
            return new ChatResponse(ModelErrors.payload(category, 0, String.valueOf(e.getMessage())),
                    List.of(), TokenUsage.ZERO, StopReason.ERROR, 0L);
        }
    }

    private boolean isSuccess(ChatResponse response) {
        return response != null && response.stopReason() != StopReason.ERROR;
    }

    /** 已发生的降级事件。 */
    public List<DegradationEvent> degradations() {
        return List.copyOf(degradations);
    }

    /** 降级链上的全部模型（主模型在前）。 */
    public List<String> chainNames() {
        List<String> names = new ArrayList<>();
        names.add(primary.name());
        fallbacks.forEach(f -> names.add(f.name()));
        return names;
    }

    /** 链路结果。 */
    private record ChainResult(ChatResponse response, String effectiveModel, boolean degraded) {
    }
}
