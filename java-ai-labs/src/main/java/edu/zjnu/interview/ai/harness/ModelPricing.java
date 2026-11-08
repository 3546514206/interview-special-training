package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.TokenUsage;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 模型价格表：模型名 → 输入 / 输出每百万 token 单价（美元）。
 *
 * <p><b>为什么价格要单独抽成一张表</b>：
 * 成本控制的本质是「用最便宜的模型干最便宜的事」。而「谁更便宜」这件事
 * 既随厂商调价而变，又随任务类型而变（嵌入模型只要输入价）。
 * 把它做成可注册的数据表（而不是散落在 if-else 里），才能：
 * <ul>
 *   <li>预算策略只依赖「相对贵贱」，不依赖具体价格；</li>
 *   <li>上线后替换价格表即可，不需要改代码；</li>
 *   <li>可以用一张表回答面试官「你怎么知道该降级到哪个模型」。</li>
 * </ul>
 *
 * <p><b>计价口径</b>：{@code usd = promptTokens/1e6*inputPrice + completionTokens/1e6*outputPrice}。
 * 注意输入与输出必须分开计价——输出 token 通常比输入贵 3~10 倍，
 * 把两者混算会让「少输出」这个优化手段失效。
 *
 * <p>内置价格是<b>演示值</b>（量级正确、绝对值以厂商价目表为准），
 * 生产环境应当把价格表放进配置中心，变更无需发版。
 */
public final class ModelPricing {

    /**
     * 单个模型的单价。
     *
     * @param model              模型标识
     * @param inputUsdPerMillion 输入 token 每百万单价（美元）
     * @param outputUsdPerMillion 输出 token 每百万单价（美元）
     */
    public record Price(String model, double inputUsdPerMillion, double outputUsdPerMillion) {

        public Price {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model 不能为空");
            }
            if (inputUsdPerMillion < 0 || outputUsdPerMillion < 0) {
                throw new IllegalArgumentException("单价不能为负");
            }
        }

        /** 按 token 用量计价（美元）。 */
        public double costUsd(TokenUsage usage) {
            if (usage == null) {
                return 0.0;
            }
            return usage.promptTokens() / 1_000_000.0 * inputUsdPerMillion
                    + usage.completionTokens() / 1_000_000.0 * outputUsdPerMillion;
        }

        /** 单次调用的预估成本（美元）。 */
        public double costUsd(int promptTokens, int completionTokens) {
            return costUsd(TokenUsage.of(promptTokens, completionTokens));
        }
    }

    /** 未知模型使用的兜底单价。 */
    public static final Price FALLBACK_PRICE = new Price("unknown", 0.50, 1.50);

    private final Map<String, Price> table;
    private final Price fallback;

    private ModelPricing(Map<String, Price> table, Price fallback) {
        this.table = Map.copyOf(table);
        this.fallback = fallback;
    }

    /**
     * 内置演示价格表。
     *
     * <p>刻意同时收录 OpenAI / Qwen / DeepSeek 三家的模型：
     * 降级链路的意义正是「主模型贵且不稳 → 备模型便宜且稳」，
     * 跨厂商降级比同厂商降级更能说明问题。
     */
    public static ModelPricing defaults() {
        Map<String, Price> m = new LinkedHashMap<>();
        // OpenAI
        m.put("gpt-4o", new Price("gpt-4o", 2.50, 10.00));
        m.put("gpt-4o-mini", new Price("gpt-4o-mini", 0.15, 0.60));
        m.put("gpt-4.1", new Price("gpt-4.1", 2.00, 8.00));
        m.put("gpt-4.1-mini", new Price("gpt-4.1-mini", 0.40, 1.60));
        // 阿里云百炼 / Qwen
        m.put("qwen-max", new Price("qwen-max", 1.60, 6.40));
        m.put("qwen-plus", new Price("qwen-plus", 0.11, 0.28));
        m.put("qwen-turbo", new Price("qwen-turbo", 0.05, 0.15));
        // DeepSeek
        m.put("deepseek-reasoner", new Price("deepseek-reasoner", 0.55, 2.19));
        m.put("deepseek-chat", new Price("deepseek-chat", 0.14, 0.28));
        // 向量模型（只有输入价）
        m.put("text-embedding-v3", new Price("text-embedding-v3", 0.02, 0.0));
        m.put("bge-m3", new Price("bge-m3", 0.02, 0.0));
        return new ModelPricing(m, FALLBACK_PRICE);
    }

    /** 空价格表（全部走兜底单价）。 */
    public static ModelPricing empty() {
        return new ModelPricing(Map.of(), FALLBACK_PRICE);
    }

    /** 复制并追加/覆盖价格。 */
    public ModelPricing with(String model, double inputUsdPerMillion, double outputUsdPerMillion) {
        Map<String, Price> copy = new LinkedHashMap<>(table);
        copy.put(model, new Price(model, inputUsdPerMillion, outputUsdPerMillion));
        return new ModelPricing(copy, fallback);
    }

    /** 设置未知模型的兜底单价。 */
    public ModelPricing withFallback(Price fallbackPrice) {
        return new ModelPricing(table, fallbackPrice == null ? FALLBACK_PRICE : fallbackPrice);
    }

    /**
     * 取某个模型的单价。
     *
     * <p>未知模型返回兜底单价而不是抛异常：模型名通常来自配置或用户输入，
     * 因为「配置里写错了一个模型名」就让整条链路挂掉是不划算的。
     * 但这里也意味着<b>拼错模型名不会报错，只会算成中间价位</b>，
     * 所以生产上应该有「模型白名单」在入口处校验。
     */
    public Price priceOf(String model) {
        if (model == null) {
            return fallback;
        }
        Price price = table.get(model);
        if (price != null) {
            return price;
        }
        // 兼容 "provider/model" 形式（网关路由常见）
        int idx = model.lastIndexOf('/');
        if (idx > 0 && idx < model.length() - 1) {
            price = table.get(model.substring(idx + 1));
            if (price != null) {
                return price;
            }
        }
        price = table.get(model.toLowerCase(Locale.ROOT));
        return price == null ? fallback : price;
    }

    /** 计价（美元）。 */
    public double costUsd(String model, TokenUsage usage) {
        return priceOf(model).costUsd(usage);
    }

    /** 该模型是否在价格表里。 */
    public boolean isKnown(String model) {
        return model != null && table.containsKey(model);
    }

    /** 价格表快照。 */
    public Map<String, Price> table() {
        return table;
    }
}
