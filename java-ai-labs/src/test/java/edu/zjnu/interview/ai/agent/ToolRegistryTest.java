package edu.zjnu.interview.ai.agent;

import edu.zjnu.interview.ai.core.Tool;
import edu.zjnu.interview.ai.core.ToolCall;
import edu.zjnu.interview.ai.core.ToolContext;
import edu.zjnu.interview.ai.core.ToolResult;
import edu.zjnu.interview.ai.core.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具注册表与入参校验。
 *
 * <p>这组测试守护的是一条底线：<b>模型给的参数永远是不可信输入</b>。
 * 缺参数、类型错、多传参数，都必须变成结构化错误回灌给模型，而不是抛异常打断链路。
 */
class ToolRegistryTest {

    private static final ToolSpec SPEC = ToolSpec.builder("demo", "演示工具")
            .param("name", "string", true, "名称")
            .optional("count", "number", "次数", 1)
            .optional("flag", "boolean", "开关", false)
            .optional("tags", "array", "标签", null)
            .sideEffect(ToolSpec.SideEffect.READ_ONLY)
            .build();

    private static Tool tool() {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return SPEC;
            }

            @Override
            public ToolResult execute(ToolCall call, ToolContext ctx) {
                return ToolResult.ok("ok");
            }
        };
    }

    @Test
    @DisplayName("注册后可按名字查找，并对外暴露工具声明")
    void registersAndExposesSpecs() {
        ToolRegistry registry = new ToolRegistry().register(tool());

        assertTrue(registry.contains("demo"));
        assertEquals(1, registry.size());
        assertEquals(List.of("demo"), List.copyOf(registry.names()));
        assertEquals(1, registry.specs().size());
        assertEquals("demo", registry.specs().get(0).name());
        assertEquals(ToolSpec.SideEffect.READ_ONLY, registry.specs().get(0).sideEffect());
    }

    @Test
    @DisplayName("缺少必填参数 → MISSING_REQUIRED")
    void missingRequiredParameter() {
        ToolRegistry registry = new ToolRegistry().register(tool());

        ToolRegistry.ValidationResult result = registry.validate(
                ToolCall.of("c1", "demo", "{\"count\":3}"));

        assertFalse(result.valid());
        assertEquals(ToolRegistry.CODE_MISSING_REQUIRED, result.errorCode());
        assertTrue(result.message().contains("name"));
    }

    @Test
    @DisplayName("参数类型不符 → TYPE_MISMATCH")
    void typeMismatch() {
        ToolRegistry registry = new ToolRegistry().register(tool());

        ToolRegistry.ValidationResult result = registry.validate(
                ToolCall.of("c1", "demo", "{\"name\":\"abc\",\"count\":\"很多\"}"));

        assertFalse(result.valid());
        assertEquals(ToolRegistry.CODE_TYPE_MISMATCH, result.errorCode());
        assertTrue(result.message().contains("count"));
    }

    @Test
    @DisplayName("未知参数 → UNKNOWN_PARAM")
    void unknownParameter() {
        ToolRegistry registry = new ToolRegistry().register(tool());

        ToolRegistry.ValidationResult result = registry.validate(
                ToolCall.of("c1", "demo", "{\"name\":\"abc\",\"retries\":5}"));

        assertFalse(result.valid());
        assertEquals(ToolRegistry.CODE_UNKNOWN_PARAM, result.errorCode());
    }

    @Test
    @DisplayName("工具不存在 → UNKNOWN_TOOL")
    void unknownTool() {
        ToolRegistry registry = new ToolRegistry().register(tool());

        ToolRegistry.ValidationResult result = registry.validate(
                ToolCall.of("c1", "not_exist", "{}"));

        assertFalse(result.valid());
        assertEquals(ToolRegistry.CODE_UNKNOWN_TOOL, result.errorCode());
        assertTrue(result.message().contains("not_exist"));
    }

    @Test
    @DisplayName("参数不是合法 JSON 对象 → INVALID_ARGUMENTS")
    void invalidArgumentsJson() {
        ToolRegistry registry = new ToolRegistry().register(tool());

        ToolRegistry.ValidationResult result = registry.validate(
                ToolCall.of("c1", "demo", "not-json"));

        assertFalse(result.valid());
        assertEquals(ToolRegistry.CODE_INVALID_ARGUMENTS, result.errorCode());
    }

    @Test
    @DisplayName("合法参数 → 校验通过；类型兼容的数字视为合法")
    void validArguments() {
        ToolRegistry registry = new ToolRegistry().register(tool());

        assertTrue(registry.validate(ToolCall.of("c1", "demo",
                "{\"name\":\"abc\",\"count\":3,\"flag\":true,\"tags\":[\"a\"]}")).valid());
        // 整数在 JSON 里解析成 Integer，仍属于 number
        assertTrue(registry.validate(ToolCall.of("c2", "demo",
                "{\"name\":\"abc\",\"count\":3.5}")).valid());
    }

    @Test
    @DisplayName("子集工具声明：只暴露被授权的工具")
    void specsForSubset() {
        ToolRegistry registry = new ToolRegistry().register(tool());

        assertEquals(1, registry.specsFor(List.of("demo")).size());
        assertEquals(0, registry.specsFor(List.of("other")).size());
    }

    @Test
    @DisplayName("校验失败可以转成工具失败结果回灌模型")
    void convertsToToolResult() {
        ToolRegistry registry = new ToolRegistry().register(tool());

        ToolResult result = registry.validate(ToolCall.of("c1", "demo", "{}")).toToolResult();

        assertFalse(result.success());
        assertEquals(ToolRegistry.CODE_MISSING_REQUIRED, result.errorCode());
        assertFalse(result.content().isBlank());
    }

    @Test
    @DisplayName("未注册的工具查找返回空，require 抛异常")
    void lookupBehaviour() {
        ToolRegistry registry = new ToolRegistry();

        assertTrue(registry.find("nope").isEmpty());
        try {
            registry.require("nope");
            assertFalse(true, "应当抛出异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("nope"));
        }
    }

    @Test
    @DisplayName("工作目录默认值不依赖静态配置（沙箱上下文由调用方注入）")
    void workingDirectoryIsInjected() {
        ToolContext ctx = ToolContext.of("trace", Paths.get("/tmp"));
        assertEquals(Paths.get("/tmp"), ctx.workingDirectory());
    }
}
