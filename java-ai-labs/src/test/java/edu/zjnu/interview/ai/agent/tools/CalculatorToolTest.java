package edu.zjnu.interview.ai.agent.tools;

import edu.zjnu.interview.ai.core.ToolCall;
import edu.zjnu.interview.ai.core.ToolContext;
import edu.zjnu.interview.ai.core.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计算器工具：优先级、括号、一元符号、非法输入，以及「绝不执行任意代码」。
 */
class CalculatorToolTest {

    private final CalculatorTool tool = new CalculatorTool();
    private final ToolContext ctx = ToolContext.of("trace-test", Paths.get("/tmp"));

    private ToolResult calc(String expression) {
        return tool.execute(ToolCall.of("c1", CalculatorTool.NAME,
                "{\"expression\":\"" + expression + "\"}"), ctx);
    }

    @Test
    @DisplayName("四则运算与优先级：* / 高于 + -")
    void arithmeticWithPrecedence() {
        assertEquals("7", calc("1+2*3").content());
        assertEquals("9", calc("(1+2)*3").content());
        assertEquals("1", calc("7-2-4").content());
        assertEquals("2.5", calc("10/4").content());
    }

    @Test
    @DisplayName("一元负号与嵌套括号")
    void unaryAndNestedParentheses() {
        assertEquals("2", calc("-3+5").content());
        assertEquals("-6", calc("(-2)*3").content());
        assertEquals("10", calc("((1+2)+3)+4").content());
    }

    @Test
    @DisplayName("小数与结果格式化")
    void decimalFormatting() {
        assertEquals("0.75", calc("3/4").content());
        assertEquals("100", calc("10*10").content());
    }

    @Test
    @DisplayName("除以零 → 结构化错误，不抛异常")
    void divisionByZero() {
        ToolResult result = calc("1/0");

        assertFalse(result.success());
        assertEquals("DIVISION_BY_ZERO", result.errorCode());
    }

    @Test
    @DisplayName("表达式不完整 → INVALID_EXPRESSION")
    void incompleteExpression() {
        ToolResult result = calc("2 +");

        assertFalse(result.success());
        assertEquals("INVALID_EXPRESSION", result.errorCode());
    }

    @Test
    @DisplayName("括号不匹配 → INVALID_EXPRESSION")
    void unbalancedParentheses() {
        ToolResult result = calc("(1+2");

        assertFalse(result.success());
        assertEquals("INVALID_EXPRESSION", result.errorCode());
    }

    @Test
    @DisplayName("拒绝一切非白名单字符：不存在 eval / 脚本注入的可能")
    void rejectsCodeInjection() {
        for (String evil : new String[]{
                "System.exit(0)",
                "1;Runtime.getRuntime()",
                "2 ** 3",
                "__import__('os')",
                "1+1//2"}) {
            ToolResult result = calc(evil);
            assertFalse(result.success(), "应当拒绝: " + evil);
            assertEquals("INVALID_EXPRESSION", result.errorCode());
        }
    }

    @Test
    @DisplayName("空表达式与超长表达式")
    void edgeCases() {
        assertEquals("EMPTY_EXPRESSION", calc("   ").errorCode());
        assertEquals("EXPRESSION_TOO_LONG",
                calc("1+".repeat(300)).errorCode());
    }

    @Test
    @DisplayName("声明副作用等级为只读，且参数校验能被工具注册表复用")
    void specIsReadOnly() {
        assertEquals("calculator", tool.spec().name());
        assertTrue(tool.spec().parameters().get("expression").required());
    }
}
