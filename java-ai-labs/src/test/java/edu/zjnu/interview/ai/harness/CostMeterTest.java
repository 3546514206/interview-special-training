package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成本计量回归：精确计价 + 按模型分组。
 */
class CostMeterTest {

    @Test
    @DisplayName("输入与输出分开计价")
    void pricingSeparatesInputAndOutput() {
        ModelPricing pricing = ModelPricing.defaults();
        ModelPricing.Price qwenPlus = pricing.priceOf("qwen-plus");
        assertEquals(0.11, qwenPlus.inputUsdPerMillion(), 1e-9);
        assertEquals(0.28, qwenPlus.outputUsdPerMillion(), 1e-9);

        double cost = pricing.costUsd("qwen-plus", TokenUsage.of(1000, 1000));
        double expected = 1000 / 1_000_000.0 * 0.11 + 1000 / 1_000_000.0 * 0.28;
        assertEquals(expected, cost, 1e-12);
    }

    @Test
    @DisplayName("未知模型落到兜底单价，不抛异常")
    void unknownModelUsesFallbackPrice() {
        ModelPricing pricing = ModelPricing.defaults();
        assertTrue(pricing.isKnown("qwen-plus"));
        assertEquals(ModelPricing.FALLBACK_PRICE, pricing.priceOf("unknown-model"));
        assertEquals(0.0, pricing.costUsd("unknown-model", TokenUsage.ZERO), 1e-12);
    }

    @Test
    @DisplayName("按模型分组统计成本与 token")
    void groupsCostsByModel() {
        CostMeter meter = new CostMeter();
        meter.charge("qwen-plus", TokenUsage.of(1000, 500));
        meter.charge("qwen-plus", TokenUsage.of(2000, 1000));
        meter.charge("qwen-turbo", TokenUsage.of(1000, 1000));

        Map<String, CostReport.ModelCost> byModel = meter.byModel();
        assertEquals(2, byModel.size());
        CostReport.ModelCost plus = byModel.get("qwen-plus");
        assertEquals(2, plus.callCount());
        assertEquals(3000, plus.promptTokens());
        assertEquals(1500, plus.completionTokens());
        assertEquals(4500, plus.totalTokens());

        double expectedPlus = 3000 / 1_000_000.0 * 0.11 + 1500 / 1_000_000.0 * 0.28;
        assertEquals(expectedPlus, plus.totalUsd(), 1e-9);
        assertEquals(meter.spentUsd(), meter.report().totalUsd(), 1e-12);
        // qwen-plus: 3000 prompt + 1500 completion；qwen-turbo: 1000 + 1000
        assertEquals(6500, meter.totalTokens().totalTokens());
    }

    @Test
    @DisplayName("成本报告可读，且总量与分组一致")
    void costReportIsConsistent() {
        CostMeter meter = new CostMeter();
        meter.charge("qwen-plus", TokenUsage.of(10000, 10000));
        CostReport report = meter.report();
        assertEquals(1, report.callCount());
        assertEquals(20000, report.totalTokens().totalTokens());
        double sum = report.byModel().values().stream().mapToDouble(CostReport.ModelCost::totalUsd).sum();
        assertEquals(report.totalUsd(), sum, 1e-12);
        assertTrue(report.toText().contains("qwen-plus"));
    }

    @Test
    @DisplayName("价格表可扩展，不影响已有条目")
    void supportsCustomPricing() {
        ModelPricing pricing = ModelPricing.defaults().with("internal-model", 0.01, 0.02);
        assertEquals(0.01, pricing.priceOf("internal-model").inputUsdPerMillion(), 1e-9);
        assertEquals(0.11, pricing.priceOf("qwen-plus").inputUsdPerMillion(), 1e-9);
    }
}
