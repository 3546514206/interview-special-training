package edu.zjnu.interview.ai.harness.coding;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 源码变异器：自动注入一个「人眼很难发现」的缺陷。
 *
 * <p><b>为什么需要自动变异</b>：手工写一份「错误实现」很容易，但那样的变异往往太明显
 * （比如把 {@code +} 写成 {@code -}），测试当然能抓住，结论没有说服力。
 * 真正有价值的变异是「改动很小、语义却变了」的：边界条件从 {@code <} 变成 {@code <=}，
 * 或者把 {@code >=} 写成 {@code >}。这类缺陷恰恰是 AI 生成代码里最常见的。
 *
 * <p>变异算子（按顺序尝试，只改第一处命中）：
 * <ol>
 *   <li>{@code >=} → {@code >}：边界右移；</li>
 *   <li>{@code <=} → {@code <}：边界左移；</li>
 *   <li>{@code < 0} → {@code <= 0}：零值边界被误判为非法；</li>
 *   <li>{@code > 0} → {@code >= 0}：零值边界被误判为合法。</li>
 * </ol>
 *
 * <p>找不到可变异点时返回 null——流水线会如实记录「未提供变异体」，
 * 而不是假装做过变异检验。
 */
public final class SourceMutator {

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile(">=\\s*"), "> "),
            new Rule(Pattern.compile("<=\\s*"), "< "),
            new Rule(Pattern.compile("(?<![<>=!])<\\s*0"), "<= 0"),
            new Rule(Pattern.compile("(?<![<>=!])>\\s*0"), ">= 0"));

    private SourceMutator() {
    }

    /**
     * 生成变异体。
     *
     * @return 变异后的源码；没有可变异点时返回 null
     */
    public static String mutate(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(source);
            if (matcher.find()) {
                String mutated = matcher.replaceFirst(rule.replacement());
                return mutated.equals(source) ? null : mutated;
            }
        }
        return null;
    }

    private record Rule(Pattern pattern, String replacement) {
    }
}
