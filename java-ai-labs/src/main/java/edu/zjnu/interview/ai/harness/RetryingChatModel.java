package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.Span;
import edu.zjnu.interview.ai.core.StopReason;
import edu.zjnu.interview.ai.core.TokenUsage;

import java.time.Duration;
import java.util.List;

/**
 * 重试装饰器：对「可重试失败」按 {@link RetryPolicy} 重试。
 *
 * <p><b>为什么不能直接用 {@code RetryPolicy.execute}</b>：
 * {@code ChatModel} SPI 约定「不抛异常，失败返回 ERROR 响应」。
 * 所以重试循环必须作用在<b>响应</b>上：拿到 ERROR 响应 → 判断分类是否可重试 → 退避 → 再试。
 * 这也正是为什么错误响应必须是<b>结构化</b>的（见 {@link ModelErrors}）：
 * 没有分类，就没法决定要不要重试。
 *
 * <p><b>退避用注入的 Sleeper</b>：测试里不 sleep，只记录退避序列。
 */
public final class RetryingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final RetryPolicy policy;
    private final Tracer tracer;
    private final FailureClassifier classifier = FailureClassifier.defaultClassifier();

    public RetryingChatModel(ChatModel delegate, RetryPolicy policy) {
        this(delegate, policy, null);
    }

    public RetryingChatModel(ChatModel delegate, RetryPolicy policy, Tracer tracer) {
        if (delegate == null) {
            throw new IllegalArgumentException("被装饰的模型不能为空");
        }
        this.delegate = delegate;
        this.policy = policy == null ? RetryPolicy.defaults() : policy;
        this.tracer = tracer;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        int attempt = 1;
        while (true) {
            ChatResponse response = attempt(request);
            if (response.stopReason() != StopReason.ERROR) {
                return response;
            }
            FailureCategory category = classifier.categorize(response);
            if (attempt >= policy.maxAttempts() || !category.transientFailure()) {
                return response;
            }
            Duration delay = policy.backoffFor(attempt);
            emitRetry(attempt, category, delay);
            policy.pause(delay);
            attempt++;
        }
    }

    private ChatResponse attempt(ChatRequest request) {
        try {
            ChatResponse response = delegate.chat(request);
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

    private void emitRetry(int attempt, FailureCategory category, Duration delay) {
        if (tracer == null) {
            return;
        }
        try (Tracer.Scope scope = tracer.span("llm.retry", Span.KIND_LLM)) {
            scope.attr(SpanAttrs.ATTEMPTS, attempt);
            scope.attr(SpanAttrs.BACKOFF_MILLIS, delay.toMillis());
            scope.attr(SpanAttrs.FAILURE_CATEGORY, category.name());
            scope.markError(category.name(), "第 " + attempt + " 次尝试失败，" + delay.toMillis() + "ms 后重试");
        }
    }
}
