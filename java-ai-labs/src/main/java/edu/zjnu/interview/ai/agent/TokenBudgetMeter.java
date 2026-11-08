package edu.zjnu.interview.ai.agent;

import edu.zjnu.interview.ai.core.TokenUsage;
import edu.zjnu.interview.ai.core.UsageMeter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地用量计量器：把 token 用量与成本累加起来，供 Agent Loop 做预算熔断。
 *
 * <p><b>为什么不直接用 core.UsageMeter.noop()</b>：noop 不记账，
 * 预算熔断就永远不触发，预算这条线就没法回归测试了。
 * 这里是<b>模块内最小可用实现</b>（线程安全、纯内存、零依赖），
 * 生产环境应换成接真实账务 / 配额系统的实现（见 harness 包的实现）。
 *
 * <p>计价：{@code 单价(美元/百万 token)}，默认 0（只算 token 不算钱）。
 * 成本用「微美元（1e-6 USD）」整数累加，避免浮点累加误差在多次 charge 后放大。
 */
public final class TokenBudgetMeter implements UsageMeter {

    /** 每百万 token 的美元单价（{@code *} 表示默认价）。 */
    private final Map<String, Double> pricePerMillionTokens = new LinkedHashMap<>();
    private final AtomicInteger promptTokens = new AtomicInteger();
    private final AtomicInteger completionTokens = new AtomicInteger();
    private final AtomicLong microUsd = new AtomicLong();

    /** 设置某个模型的单价（美元 / 百万 token）。 */
    public TokenBudgetMeter price(String model, double usdPerMillionTokens) {
        pricePerMillionTokens.put(model, usdPerMillionTokens);
        return this;
    }

    /** 统一设置所有模型的单价。 */
    public TokenBudgetMeter defaultPrice(double usdPerMillionTokens) {
        pricePerMillionTokens.put("*", usdPerMillionTokens);
        return this;
    }

    private double priceOf(String model) {
        Double price = model == null ? null : pricePerMillionTokens.get(model);
        if (price == null) {
            price = pricePerMillionTokens.get("*");
        }
        return price == null ? 0.0 : price;
    }

    @Override
    public void charge(String model, TokenUsage usage) {
        if (usage == null) {
            return;
        }
        promptTokens.addAndGet(usage.promptTokens());
        completionTokens.addAndGet(usage.completionTokens());
        double price = priceOf(model);
        if (price > 0) {
            microUsd.addAndGet(Math.round(price * usage.totalTokens()));
        }
    }

    @Override
    public double spentUsd() {
        return microUsd.get() / 1_000_000.0;
    }

    @Override
    public TokenUsage totalTokens() {
        return TokenUsage.of(promptTokens.get(), completionTokens.get());
    }

    /** 重置计数（测试用）。 */
    public void reset() {
        promptTokens.set(0);
        completionTokens.set(0);
        microUsd.set(0);
    }
}
