package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.TokenUsage;
import edu.zjnu.interview.ai.core.UsageMeter;

/**
 * 预算策略：按会话 / 按请求设置 USD 预算与 token 上限，
 * 软阈值降级、硬阈值拒绝。
 *
 * <p><b>为什么要分软硬两个阈值</b>：
 * <ul>
 *   <li><b>软阈值</b>（默认 80%）：钱还够，但已经不宽裕 → 降级到便宜模型。
 *       此时用户仍能拿到答案，只是质量下降，业务不中断。</li>
 *   <li><b>硬阈值</b>（100%）：钱花完了 → 拒绝新调用，返回结构化错误。
 *       此时继续调用就是「无预算地烧钱」，必须挡住。</li>
 * </ul>
 * 只有一档阈值会导致两种都很糟：设高了来不及降级，设低了频繁拒绝。
 *
 * <p><b>两种 scope</b>：
 * <ul>
 *   <li>{@link Scope#SESSION}：一个会话（一次用户任务）共享一份预算，
 *       防止「Agent 循环转 50 轮把预算烧光」。</li>
 *   <li>{@link Scope#REQUEST}：每一次请求单独限额，防止「单次超长上下文」把额度一次吃掉。</li>
 * </ul>
 * 真实系统通常两者都要：会话级兜底 + 请求级防单点击穿。
 *
 * <p><b>判定是纯函数</b>：输入「已用用量」，输出「动作」。
 * 不做 IO、不改状态，因此可以被单元测试穷举，也可以被复用于预检（调用前）与结算（调用后）。
 */
public final class BudgetPolicy {

    /** 预算作用域。 */
    public enum Scope {
        /** 会话级：共享一个 {@link UsageMeter}。 */
        SESSION,
        /** 请求级：每次请求单独累计。 */
        REQUEST
    }

    /** 判定动作。 */
    public enum Action {
        /** 放行。 */
        ALLOW,
        /** 降级到便宜模型。 */
        DEGRADE,
        /** 拒绝。 */
        REJECT
    }

    private final Scope scope;
    private final double usdLimit;
    private final long tokenLimit;
    private final double softRatio;
    private final String degradedModel;
    private final UsageMeter sessionMeter;

    private BudgetPolicy(Scope scope, double usdLimit, long tokenLimit, double softRatio,
                         String degradedModel, UsageMeter sessionMeter) {
        this.scope = scope;
        this.usdLimit = usdLimit;
        this.tokenLimit = tokenLimit;
        this.softRatio = softRatio;
        this.degradedModel = degradedModel;
        this.sessionMeter = sessionMeter;
    }

    /** 构造器。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 会话级预算：共享一个计量器。 */
    public static BudgetPolicy forSession(UsageMeter meter, double usdLimit, long tokenLimit) {
        return builder().scope(Scope.SESSION).usdLimit(usdLimit).tokenLimit(tokenLimit)
                .sessionMeter(meter).build();
    }

    /** 请求级预算。 */
    public static BudgetPolicy forRequest(double usdLimit, long tokenLimit) {
        return builder().scope(Scope.REQUEST).usdLimit(usdLimit).tokenLimit(tokenLimit).build();
    }

    /**
     * 判定。
     *
     * @param requestedModel 请求要用的模型
     * @param requestUsage   当前用量（会话 scope 下若配置了 sessionMeter 则以 meter 为准）
     */
    public BudgetDecision decide(String requestedModel, UsageSnapshot requestUsage) {
        UsageSnapshot usage = resolveUsage(requestUsage);
        double ratio = usage.ratio(usdLimit, tokenLimit);

        boolean usdHard = usdLimit > 0 && usage.spentUsd() >= usdLimit;
        boolean tokenHard = tokenLimit > 0 && usage.tokens() >= tokenLimit;
        if (usdHard || tokenHard) {
            String reason = usdHard
                    ? String.format("会话预算耗尽：已花费 $%.6f，达到上限 $%.6f", usage.spentUsd(), usdLimit)
                    : String.format("token 预算耗尽：已消耗 %d token，达到上限 %d", usage.tokens(), tokenLimit);
            return BudgetDecision.reject(requestedModel, reason, usage, usdLimit, tokenLimit);
        }

        boolean softHit = ratio >= softRatio;
        if (softHit && degradedModel != null && !degradedModel.isBlank()
                && !degradedModel.equals(requestedModel)) {
            String reason = String.format("已达预算软阈值 %.0f%%（已用 %.2f%%），模型 %s 降级为 %s",
                    softRatio * 100, ratio * 100, requestedModel, degradedModel);
            DegradationEvent event = DegradationEvent.budget(requestedModel, degradedModel, reason,
                    usage.spentUsd(), usdLimit, usage.tokens(), tokenLimit);
            return BudgetDecision.degrade(requestedModel, degradedModel, reason, usage, usdLimit, tokenLimit, event);
        }

        return BudgetDecision.allow(requestedModel, "预算充足", usage, usdLimit, tokenLimit);
    }

    /** 判定（便捷重载）。 */
    public BudgetDecision decide(String requestedModel, double requestSpentUsd, long requestTokens) {
        return decide(requestedModel, UsageSnapshot.of(requestSpentUsd, requestTokens));
    }

    private UsageSnapshot resolveUsage(UsageSnapshot requestUsage) {
        if (scope == Scope.SESSION && sessionMeter != null) {
            return UsageSnapshot.of(sessionMeter.spentUsd(), sessionMeter.totalTokens().totalTokens());
        }
        return requestUsage == null ? UsageSnapshot.ZERO : requestUsage;
    }

    public Scope scope() {
        return scope;
    }

    public double usdLimit() {
        return usdLimit;
    }

    public long tokenLimit() {
        return tokenLimit;
    }

    public double softRatio() {
        return softRatio;
    }

    public String degradedModel() {
        return degradedModel;
    }

    /** 构造器。 */
    public static final class Builder {
        private Scope scope = Scope.SESSION;
        private double usdLimit;
        private long tokenLimit;
        private double softRatio = 0.8;
        private String degradedModel;
        private UsageMeter sessionMeter;

        public Builder scope(Scope value) {
            this.scope = value;
            return this;
        }

        /** USD 预算上限；<=0 表示不限。 */
        public Builder usdLimit(double value) {
            this.usdLimit = value;
            return this;
        }

        /** token 上限；<=0 表示不限。 */
        public Builder tokenLimit(long value) {
            this.tokenLimit = value;
            return this;
        }

        /** 软阈值比例（0~1）。 */
        public Builder softRatio(double value) {
            this.softRatio = Math.min(1.0, Math.max(0.0, value));
            return this;
        }

        /** 降级目标模型。 */
        public Builder degradedModel(String value) {
            this.degradedModel = value;
            return this;
        }

        /** 会话级计量器。 */
        public Builder sessionMeter(UsageMeter value) {
            this.sessionMeter = value;
            return this;
        }

        public BudgetPolicy build() {
            return new BudgetPolicy(scope, usdLimit, tokenLimit, softRatio, degradedModel, sessionMeter);
        }
    }

    /**
     * 请求级用量累加器。
     *
     * <p>请求级预算需要一个「这次请求自己的账本」；
     * 把它独立成类而不是塞进 {@link BudgetPolicy}，是因为预算策略应该是纯函数，
     * 而累加是有状态的。
     */
    public static final class RequestBudget {
        private final ModelPricing pricing;
        private long promptTokens;
        private long completionTokens;
        private double spentUsd;

        public RequestBudget() {
            this(ModelPricing.defaults());
        }

        public RequestBudget(ModelPricing pricing) {
            this.pricing = pricing == null ? ModelPricing.defaults() : pricing;
        }

        /** 记一次用量。 */
        public synchronized void charge(String model, TokenUsage usage) {
            if (usage == null) {
                return;
            }
            promptTokens += usage.promptTokens();
            completionTokens += usage.completionTokens();
            spentUsd += pricing.costUsd(model, usage);
        }

        public synchronized UsageSnapshot snapshot() {
            return UsageSnapshot.of(spentUsd, promptTokens + completionTokens);
        }

        public synchronized double spentUsd() {
            return spentUsd;
        }
    }
}
