package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.TokenUsage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 成本明细表。
 *
 * <p>为什么要「按模型分组」而不是只给一个总数：
 * <ul>
 *   <li>总数只能告诉你「今天花了 100 块」，分组才能告诉你「其中 80 块来自某个降级没生效的调用」；</li>
 *   <li>成本优化的抓手是「哪个模型、哪类任务」，没有分组就没有抓手。</li>
 * </ul>
 *
 * <p>另外把 input/output 的成本分开列，是为了暴露一个常见误区：
 * 「模型太贵」很多时候其实是「输出太长」——输出单价通常是输入的 3~10 倍。
 */
public record CostReport(Map<String, ModelCost> byModel,
                         double totalUsd,
                         TokenUsage totalTokens,
                         long callCount) {

    /**
     * 单个模型的成本明细。
     *
     * @param model           模型名
     * @param callCount       调用次数
     * @param promptTokens    输入 token 合计
     * @param completionTokens 输出 token 合计
     * @param totalTokens     token 合计
     * @param inputUsd        输入成本
     * @param outputUsd       输出成本
     * @param totalUsd        总成本
     */
    public record ModelCost(String model,
                            long callCount,
                            long promptTokens,
                            long completionTokens,
                            long totalTokens,
                            double inputUsd,
                            double outputUsd,
                            double totalUsd) {
    }

    public CostReport {
        byModel = byModel == null ? Map.of() : Map.copyOf(byModel);
    }

    /** 取某个模型的成本明细。 */
    public ModelCost model(String model) {
        return byModel.get(model);
    }

    /** 文本明细表。 */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 成本明细 ===\n");
        sb.append(String.format("%-20s %6s %12s %12s %12s %14s%n",
                "MODEL", "次数", "输入token", "输出token", "输入$", "输出$"));
        for (ModelCost cost : byModel.values()) {
            sb.append(String.format("%-20s %6d %12d %12d %12.6f %14.6f%n",
                    cost.model(), cost.callCount(), cost.promptTokens(), cost.completionTokens(),
                    cost.inputUsd(), cost.outputUsd()));
        }
        sb.append(String.format("%-20s %6d %12d %12d %12.6f %14.6f%n",
                "TOTAL", callCount, totalTokens.promptTokens(), totalTokens.completionTokens(),
                byModel.values().stream().mapToDouble(ModelCost::inputUsd).sum(),
                byModel.values().stream().mapToDouble(ModelCost::outputUsd).sum()));
        sb.append(String.format("总计: $%.6f, token 合计: %d%n", totalUsd, totalTokens.totalTokens()));
        return sb.toString();
    }

    /** 按模型名排序的明细（便于稳定输出）。 */
    public Map<String, ModelCost> sortedByModel() {
        Map<String, ModelCost> sorted = new LinkedHashMap<>();
        byModel.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }
}
