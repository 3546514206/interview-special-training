package edu.zjnu.interview.ai.agent.tools;

import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.core.Tool;
import edu.zjnu.interview.ai.core.ToolCall;
import edu.zjnu.interview.ai.core.ToolContext;
import edu.zjnu.interview.ai.core.ToolResult;
import edu.zjnu.interview.ai.core.ToolSpec;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 安全计算器工具。
 *
 * <p><b>为什么自己写递归下降解析器，而不是 eval / ScriptEngine</b>：
 * <ul>
 *   <li>{@code eval} 等价于「把模型输出当代码执行」——模型完全可能被提示注入操纵，
 *       一旦执行就是 RCE，这是 Agent 工具里最致命的一类漏洞；</li>
 *   <li>{@code ScriptEngine}（JSR-223）同样能执行任意代码，且在不同 JDK / 发行版上行为不一致，
 *       还会引入不确定的类加载问题；</li>
 *   <li>递归下降解析器是<b>白名单</b>实现：只有数字和 {@code + - * / ( )} 能被识别，
 *       其它字符一律报错。攻击面为零，且行为完全确定、可单测。</li>
 * </ul>
 *
 * <p>支持：{@code + - * /}、括号、一元正负号、优先级（{@code * /} 高于 {@code + -}）。
 * 副作用等级：{@code READ_ONLY}（纯计算，不触碰任何外部资源）。
 */
public final class CalculatorTool implements Tool {

    public static final String NAME = "calculator";

    private final int maxExpressionLength;

    public CalculatorTool() {
        this(512);
    }

    public CalculatorTool(int maxExpressionLength) {
        this.maxExpressionLength = Math.max(16, maxExpressionLength);
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder(NAME, "计算一个算术表达式的值，支持加减乘除、括号与优先级，例如 '(1+2)*3'")
                .param("expression", "string", true, "算术表达式，只允许数字与 + - * / ( )")
                .sideEffect(ToolSpec.SideEffect.READ_ONLY)
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        Map<String, Object> args = Json.readMap(call.argumentsJson());
        Object raw = args.get("expression");
        if (raw == null) {
            return ToolResult.error("MISSING_ARGUMENT", "缺少参数 expression");
        }
        String expression = String.valueOf(raw).strip();
        if (expression.isEmpty()) {
            return ToolResult.error("EMPTY_EXPRESSION", "表达式为空");
        }
        if (expression.length() > maxExpressionLength) {
            return ToolResult.error("EXPRESSION_TOO_LONG",
                    "表达式长度 " + expression.length() + " 超过上限 " + maxExpressionLength);
        }
        try {
            double value = new ExpressionParser(expression).parse();
            String formatted = format(value);
            return ToolResult.ok(formatted, Map.of(
                    "expression", expression,
                    "value", value,
                    "formatted", formatted));
        } catch (ArithmeticException e) {
            return ToolResult.error("DIVISION_BY_ZERO", "算术错误: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("INVALID_EXPRESSION", "表达式非法: " + e.getMessage());
        }
    }

    /** 结果格式化：整数去掉小数尾巴，小数去掉多余的 0。 */
    static String format(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return String.valueOf(value);
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /**
     * 递归下降表达式解析器。
     *
     * <p>文法：
     * <pre>
     * expression := term (('+' | '-') term)*
     * term       := factor (('*' | '/') factor)*
     * factor     := ('+' | '-')? primary
     * primary    := number | '(' expression ')'
     * </pre>
     */
    static final class ExpressionParser {

        private final String src;
        private int pos;

        ExpressionParser(String src) {
            this.src = src;
        }

        double parse() {
            double value = parseExpression();
            skipWhitespace();
            if (pos < src.length()) {
                throw new IllegalArgumentException("位置 " + pos + " 存在多余字符 '" + src.charAt(pos) + "'");
            }
            return value;
        }

        private double parseExpression() {
            double left = parseTerm();
            while (true) {
                skipWhitespace();
                if (eat('+')) {
                    left += parseTerm();
                } else if (eat('-')) {
                    left -= parseTerm();
                } else {
                    return left;
                }
            }
        }

        private double parseTerm() {
            double left = parseFactor();
            while (true) {
                skipWhitespace();
                if (eat('*')) {
                    left *= parseFactor();
                } else if (eat('/')) {
                    double right = parseFactor();
                    if (right == 0.0) {
                        throw new ArithmeticException("除数不能为 0");
                    }
                    left /= right;
                } else {
                    return left;
                }
            }
        }

        private double parseFactor() {
            skipWhitespace();
            boolean negate = false;
            if (eat('-')) {
                negate = true;
            } else {
                eat('+');
            }
            double value = parsePrimary();
            return negate ? -value : value;
        }

        private double parsePrimary() {
            skipWhitespace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("表达式不完整");
            }
            char c = src.charAt(pos);
            if (c == '(') {
                pos++;
                double value = parseExpression();
                skipWhitespace();
                if (!eat(')')) {
                    throw new IllegalArgumentException("括号不匹配");
                }
                return value;
            }
            if (Character.isDigit(c) || c == '.') {
                return parseNumber();
            }
            throw new IllegalArgumentException("无法识别的字符 '" + c + "'（位置 " + pos + "）");
        }

        private double parseNumber() {
            int start = pos;
            boolean seenDot = false;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isDigit(c)) {
                    pos++;
                } else if (c == '.' && !seenDot) {
                    seenDot = true;
                    pos++;
                } else {
                    break;
                }
            }
            String literal = src.substring(start, pos);
            try {
                return Double.parseDouble(literal);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("非法数字 '" + literal + "'");
            }
        }

        private boolean eat(char expected) {
            if (pos < src.length() && src.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }

    /** 便于测试：直接求值。 */
    public static double evaluate(String expression) {
        return new ExpressionParser(expression).parse();
    }

    /** 便于测试：工具声明列表（供 registry 使用）。 */
    public static List<ToolSpec> specs() {
        return List.of(new CalculatorTool().spec());
    }
}
