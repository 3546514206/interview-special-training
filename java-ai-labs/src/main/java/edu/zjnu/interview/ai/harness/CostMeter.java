package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.TokenUsage;
import edu.zjnu.interview.ai.core.UsageMeter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 成本计量器：精确计费 + 按模型分组。
 *
 * <p><b>关键设计：计量发生在调用路径上</b>。
 * {@link UsageMeter} 这个 SPI 存在的意义就是让「每一步都能问一句还能再花吗」成为可能。
 * 如果成本是事后从日志里聚合出来的，那么预算控制只能是「事后追责」而不是「事前拦截」——
 * 这正是很多团队预算失控的原因：账单出来的时候钱已经花完了。
 *
 * <p><b>精确到模型分组</b>：降级链路上，同一次用户请求可能先打贵模型（失败）
 * 再打便宜模型（成功）。如果只记总额，就无法回答「降级到底省了多少钱」。
 *
 * <p><b>线程安全</b>：Agent Loop 常常并发扇出多个工具调用，计量器会被多线程同时写，
 * 所以用 {@link ConcurrentHashMap} + {@link AtomicLong}；所有金额用 {@code double} 累加，
 * 精度足够（单次调用级别的量级），但如果要做财务级对账应当改成 {@code BigDecimal} 或整数「微美元」。
 */
public final class CostMeter implements UsageMeter {

    private final ModelPricing pricing;
    private final ConcurrentMap<String, Counter> byModel = new ConcurrentHashMap<>();
    private final AtomicLong totalPromptTokens = new AtomicLong();
    private final AtomicLong totalCompletionTokens = new AtomicLong();
    private final AtomicLong totalCalls = new AtomicLong();

    /** 单模型的累加器。 */
    private static final class Counter {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong promptTokens = new AtomicLong();
        private final AtomicLong completionTokens = new AtomicLong();
        private final AtomicLong inputMicrousd = new AtomicLong();
        private final AtomicLong outputMicrousd = new AtomicLong();

        /** 以「微美元」（1e-6 USD）整数累加，避免浮点累加误差。 */
        void add(ModelPricing.Price price, TokenUsage usage) {
            calls.incrementAndGet();
            promptTokens.addAndGet(usage.promptTokens());
            completionTokens.addAndGet(usage.completionTokens());
            inputMicrousd.addAndGet(Math.round(price.inputUsdPerMillion() * usage.promptTokens()));
            outputMicrousd.addAndGet(Math.round(price.outputUsdPerMillion() * usage.completionTokens()));
        }

        double inputUsd() {
            return inputMicrousd.get() / 1_000_000.0;
        }

        double outputUsd() {
            return outputMicrousd.get() / 1_000_000.0;
        }
    }

    public CostMeter() {
        this(ModelPricing.defaults());
    }

    public CostMeter(ModelPricing pricing) {
        this.pricing = pricing == null ? ModelPricing.defaults() : pricing;
    }

    @Override
    public void charge(String model, TokenUsage usage) {
        if (usage == null) {
            return;
        }
        String key = model == null ? "unknown" : model;
        ModelPricing.Price price = pricing.priceOf(key);
        byModel.computeIfAbsent(key, k -> new Counter()).add(price, usage);
        totalPromptTokens.addAndGet(usage.promptTokens());
        totalCompletionTokens.addAndGet(usage.completionTokens());
        totalCalls.incrementAndGet();
    }

    @Override
    public double spentUsd() {
        double sum = 0.0;
        for (Counter counter : byModel.values()) {
            sum += counter.inputUsd() + counter.outputUsd();
        }
        return round(sum);
    }

    /**
     * 金额收敛到 1e-12 美元精度。
     *
     * <p>double 累加会产生 {@code 0.012199999999999999} 这样的尾数噪声，
     * 写进 JSON / 报表里既难看又会引发「两次汇总对不上」的排查成本。
     * 1e-12 美元的精度远高于任何真实计费需求，收敛它没有业务风险。
     */
    private static double round(double value) {
        return Math.round(value * 1_000_000_000_000.0) / 1_000_000_000_000.0;
    }

    @Override
    public TokenUsage totalTokens() {
        return TokenUsage.of(clamp(totalPromptTokens.get()), clamp(totalCompletionTokens.get()));
    }

    /** 调用次数。 */
    public long callCount() {
        return totalCalls.get();
    }

    /** 按模型分组的成本明细。 */
    public Map<String, CostReport.ModelCost> byModel() {
        Map<String, CostReport.ModelCost> out = new java.util.LinkedHashMap<>();
        byModel.forEach((model, counter) -> {
            long prompt = counter.promptTokens.get();
            long completion = counter.completionTokens.get();
            out.put(model, new CostReport.ModelCost(model, counter.calls.get(), prompt, completion,
                    prompt + completion, round(counter.inputUsd()), round(counter.outputUsd()),
                    round(counter.inputUsd() + counter.outputUsd())));
        });
        return out;
    }

    /** 成本明细报告。 */
    public CostReport report() {
        return new CostReport(byModel(), spentUsd(), totalTokens(), totalCalls.get());
    }

    /** 某个模型已产生的成本。 */
    public double spentUsdOf(String model) {
        Counter counter = byModel.get(model);
        return counter == null ? 0.0 : counter.inputUsd() + counter.outputUsd();
    }

    /** 某个模型已消耗的 token。 */
    public TokenUsage tokensOf(String model) {
        Counter counter = byModel.get(model);
        if (counter == null) {
            return TokenUsage.ZERO;
        }
        return TokenUsage.of(clamp(counter.promptTokens.get()), clamp(counter.completionTokens.get()));
    }

    /** token 计数用 long 累加（防溢出），但对外暴露的 {@link TokenUsage} 用 int，这里做安全收敛。 */
    private static int clamp(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
    }
}
