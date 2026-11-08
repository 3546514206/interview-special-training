package edu.zjnu.interview.ai.agent;

import edu.zjnu.interview.ai.agent.lab.ScriptedChatModel;
import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.Span;
import edu.zjnu.interview.ai.core.Tool;
import edu.zjnu.interview.ai.core.ToolCall;
import edu.zjnu.interview.ai.core.ToolContext;
import edu.zjnu.interview.ai.core.ToolResult;
import edu.zjnu.interview.ai.core.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent Loop 的迭代协议：收敛、终止条件、故障隔离、权限控制、可观测。
 */
class AgentLoopTest {

    /** 一个会抛异常的工具：用来验证「工具异常不能炸掉整条链路」。 */
    private static final Tool EXPLODING = new Tool() {
        @Override
        public ToolSpec spec() {
            return ToolSpec.builder("exploding", "总是失败的工具")
                    .sideEffect(ToolSpec.SideEffect.READ_ONLY)
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext ctx) {
            throw new IllegalStateException("工具内部炸了");
        }
    };

    private static final Tool OK = new Tool() {
        @Override
        public ToolSpec spec() {
            return ToolSpec.builder("ok_tool", "正常工具")
                    .optional("n", "number", "可选的数字参数（用于制造不同的调用签名）", 0)
                    .sideEffect(ToolSpec.SideEffect.READ_ONLY)
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext ctx) {
            return ToolResult.ok("工具返回 42");
        }
    };

    private static ToolRegistry registry(Tool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    @Test
    @DisplayName("正常收敛：工具结果回灌模型后模型给出最终答案")
    void convergesAndFeedsToolResultBack() {
        AtomicReference<String> secondRoundPrompt = new AtomicReference<>("");
        ScriptedChatModel model = new ScriptedChatModel("test-model", List.of(
                request -> ChatResponse.toolCall("call_1", "ok_tool", "{}"),
                request -> {
                    ChatMessage last = request.lastMessage();
                    secondRoundPrompt.set(last == null ? "" : last.content());
                    return ChatResponse.text("最终答案：42");
                }));

        AgentLoop loop = new AgentLoop(AgentConfig.builder(model, registry(OK)).maxSteps(4).build());
        AgentResult result = loop.run("帮我算一下");

        assertEquals(TerminationReason.FINAL_ANSWER, result.terminationReason());
        assertTrue(result.success());
        assertEquals("最终答案：42", result.finalAnswer());
        assertEquals(1, result.toolCallCount());
        // 工具结果必须以 role=TOOL 回灌，且带 toolCallId
        ChatMessage toolMessage = result.context().stream()
                .filter(m -> m.role() == ChatMessage.Role.TOOL)
                .findFirst().orElseThrow();
        assertEquals("call_1", toolMessage.toolCallId());
        assertEquals("ok_tool", toolMessage.name());
        assertTrue(toolMessage.content().contains("42"));
        // 第二轮模型确实看到了工具结果
        assertTrue(secondRoundPrompt.get().contains("42"));
    }

    @Test
    @DisplayName("工具抛异常 → 转成结构化错误回灌，链路不中断")
    void toolExceptionBecomesToolResult() {
        ScriptedChatModel model = new ScriptedChatModel("test-model", List.of(
                request -> ChatResponse.toolCall("call_1", "exploding", "{}"),
                request -> ChatResponse.text("我看到工具失败了，换个说法回答")));

        AgentLoop loop = new AgentLoop(AgentConfig.builder(model, registry(EXPLODING)).maxSteps(4).build());
        AgentResult result = loop.run("调用会失败的工具");

        assertEquals(TerminationReason.FINAL_ANSWER, result.terminationReason());
        assertEquals(1, result.failedToolCallCount());
        assertEquals("TOOL_EXECUTION_FAILED", result.toolInvocations().get(0).errorCode());
        assertTrue(result.toolInvocations().get(0).content().contains("工具内部炸了"));
        // 失败原因必须回灌给模型
        assertTrue(result.context().stream()
                .anyMatch(m -> m.role() == ChatMessage.Role.TOOL && m.content().contains("炸了")));
    }

    @Test
    @DisplayName("参数非法 → 校验错误回灌模型，工具不执行")
    void invalidArgumentsAreRejectedBeforeExecution() {
        AtomicInteger executed = new AtomicInteger();
        Tool counting = new Tool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder("counting", "需要参数的工具")
                        .param("n", "number", true, "数字")
                        .sideEffect(ToolSpec.SideEffect.READ_ONLY)
                        .build();
            }

            @Override
            public ToolResult execute(ToolCall call, ToolContext ctx) {
                executed.incrementAndGet();
                return ToolResult.ok("不该被执行");
            }
        };

        ScriptedChatModel model = new ScriptedChatModel("test-model", List.of(
                request -> ChatResponse.toolCall("call_1", "counting", "{\"n\":\"不是数字\"}"),
                request -> ChatResponse.text("参数写错了，我换个参数")));

        AgentLoop loop = new AgentLoop(AgentConfig.builder(model, registry(counting)).maxSteps(4).build());
        AgentResult result = loop.run("调用一下");

        assertEquals(0, executed.get());
        assertEquals(ToolRegistry.CODE_TYPE_MISMATCH, result.toolInvocations().get(0).errorCode());
        assertEquals(TerminationReason.FINAL_ANSWER, result.terminationReason());
    }

    @Test
    @DisplayName("越权调用被拒绝，拒绝理由回灌模型")
    void permissionDeniedIsFedBack() {
        ScriptedChatModel model = new ScriptedChatModel("test-model", List.of(
                request -> ChatResponse.toolCall("call_1", "ok_tool", "{}"),
                request -> ChatResponse.text("没有权限，我用别的方式回答")));

        AgentLoop loop = new AgentLoop(AgentConfig.builder(model, registry(OK))
                .authorizer((call, ctx) -> "策略禁止调用 " + call.name())
                .maxSteps(4)
                .build());
        AgentResult result = loop.run("调用工具");

        assertEquals("PERMISSION_DENIED", result.toolInvocations().get(0).errorCode());
        assertTrue(result.toolInvocations().get(0).content().contains("策略禁止"));
        assertTrue(result.context().stream()
                .anyMatch(m -> m.role() == ChatMessage.Role.TOOL && m.content().contains("策略禁止")));
    }

    @Test
    @DisplayName("同一工具同一参数重复调用达阈值 → 死循环检测")
    void detectsDeadLoop() {
        ScriptedChatModel model = new ScriptedChatModel("test-model", List.of(
                request -> ChatResponse.toolCall("call_1", "ok_tool", "{}")));

        AgentLoop loop = new AgentLoop(AgentConfig.builder(model, registry(OK))
                .maxSteps(20)
                .repeatCallThreshold(3)
                .build());
        AgentResult result = loop.run("一直调用");

        assertEquals(TerminationReason.LOOP_DETECTED, result.terminationReason());
        assertTrue(result.failureReason().contains("ok_tool"));
        assertEquals(3, result.toolCallCount());
    }

    @Test
    @DisplayName("预算超限 → 熔断（在跑满步数之前止损）")
    void budgetBreakerStopsTheLoop() {
        AtomicInteger counter = new AtomicInteger();
        ScriptedChatModel model = new ScriptedChatModel("test-model", List.of(
                request -> ChatResponse.toolCall("call_" + counter.incrementAndGet(),
                        "ok_tool", "{\"n\":" + counter.get() + "}")));

        TokenBudgetMeter meter = new TokenBudgetMeter();
        AgentLoop loop = new AgentLoop(AgentConfig.builder(model, registry(OK))
                .usageMeter(meter)
                .maxSteps(50)
                .maxTotalTokens(100)
                .build());
        AgentResult result = loop.run("一直调用");

        assertEquals(TerminationReason.BUDGET_EXCEEDED, result.terminationReason());
        assertTrue(result.failureReason().contains("预算"));
        assertTrue(result.stepCount() < 50);
    }

    @Test
    @DisplayName("成本预算同样可以熔断")
    void costBudgetBreaker() {
        ScriptedChatModel model = new ScriptedChatModel("test-model", List.of(
                request -> ChatResponse.toolCall("call_1", "ok_tool", "{}")));

        TokenBudgetMeter meter = new TokenBudgetMeter().defaultPrice(1000.0);
        AgentLoop loop = new AgentLoop(AgentConfig.builder(model, registry(OK))
                .usageMeter(meter)
                .maxSteps(50)
                .maxCostUsd(0.0001)
                .repeatCallThreshold(100)
                .build());
        AgentResult result = loop.run("一直调用");

        assertEquals(TerminationReason.BUDGET_EXCEEDED, result.terminationReason());
    }

    @Test
    @DisplayName("连续工具失败达阈值 → 熔断")
    void consecutiveToolFailuresStopTheLoop() {
        ScriptedChatModel model = new ScriptedChatModel("test-model", List.of(
                request -> ChatResponse.toolCall("call_1", "exploding", "{}")));

        AgentLoop loop = new AgentLoop(AgentConfig.builder(model, registry(EXPLODING))
                .maxSteps(20)
                .maxConsecutiveToolFailures(2)
                .build());
        AgentResult result = loop.run("一直调用会失败的工具");

        assertEquals(TerminationReason.CONSECUTIVE_TOOL_FAILURES, result.terminationReason());
        assertEquals(2, result.toolCallCount());
    }

    @Test
    @DisplayName("达到最大步数仍未收敛 → MAX_STEPS")
    void stopsAtMaxSteps() {
        AtomicInteger counter = new AtomicInteger();
        ScriptedChatModel model = new ScriptedChatModel("test-model", List.of(
                request -> ChatResponse.toolCall("call_" + counter.incrementAndGet(),
                        "ok_tool", "{\"n\":" + counter.get() + "}")));

        AgentLoop loop = new AgentLoop(AgentConfig.builder(model, registry(OK))
                .maxSteps(3)
                .repeatCallThreshold(100)
                .build());
        AgentResult result = loop.run("一直调用");

        assertEquals(TerminationReason.MAX_STEPS, result.terminationReason());
        assertEquals(3, result.toolCallCount());
    }

    @Test
    @DisplayName("模型异常不会炸掉链路，转成 MODEL_ERROR")
    void modelExceptionIsContained() {
        ScriptedChatModel model = new ScriptedChatModel("test-model", List.of(
                request -> {
                    throw new IllegalStateException("模型服务不可用");
                }));

        AgentLoop loop = new AgentLoop(AgentConfig.builder(model, registry(OK)).build());
        AgentResult result = loop.run("你好");

        assertEquals(TerminationReason.MODEL_ERROR, result.terminationReason());
        assertTrue(result.failureReason().contains("模型服务不可用"));
    }

    @Test
    @DisplayName("工具未注册 → UNKNOWN_TOOL 回灌模型")
    void unknownToolIsReported() {
        ScriptedChatModel model = new ScriptedChatModel("test-model", List.of(
                request -> ChatResponse.toolCall("call_1", "not_registered", "{}"),
                request -> ChatResponse.text("这个工具不存在")));

        AgentLoop loop = new AgentLoop(AgentConfig.builder(model, registry(OK)).maxSteps(4).build());
        AgentResult result = loop.run("调用不存在的工具");

        assertEquals(ToolRegistry.CODE_UNKNOWN_TOOL, result.toolInvocations().get(0).errorCode());
        assertEquals(TerminationReason.FINAL_ANSWER, result.terminationReason());
    }

    @Test
    @DisplayName("每一步都会发 Span：llm / tool / agent_step，且 spanId 向下传递")
    void emitsTraceSpans() {
        List<Span> spans = new ArrayList<>();
        ScriptedChatModel model = new ScriptedChatModel("test-model", List.of(
                request -> ChatResponse.toolCall("call_1", "ok_tool", "{}"),
                request -> ChatResponse.text("完成")));

        AgentLoop loop = new AgentLoop(AgentConfig.builder(model, registry(OK))
                .traceSink(spans::add)
                .maxSteps(4)
                .build());
        AgentResult result = loop.run("跑一下");

        assertTrue(spans.stream().anyMatch(s -> Span.KIND_LLM.equals(s.kind())));
        assertTrue(spans.stream().anyMatch(s -> Span.KIND_TOOL.equals(s.kind())));
        assertTrue(spans.stream().anyMatch(s -> Span.KIND_AGENT_STEP.equals(s.kind())));
        // 工具 span 的父节点是 agent.step span，工具上下文里带的 spanId 就是它自己
        Span toolSpan = spans.stream().filter(s -> Span.KIND_TOOL.equals(s.kind())).findFirst().orElseThrow();
        Span stepSpan = spans.stream()
                .filter(s -> Span.KIND_AGENT_STEP.equals(s.kind()) && s.attributes().containsKey("step"))
                .findFirst().orElseThrow();
        assertEquals(stepSpan.spanId(), toolSpan.parentSpanId());
        assertEquals(toolSpan.spanId(), result.toolInvocations().get(0).spanId());
        // 根 span 的 traceId 与结果一致
        assertTrue(spans.stream().allMatch(s -> s.traceId().equals(result.traceId())));
    }

    @Test
    @DisplayName("token 用量会被计量，供预算熔断使用")
    void chargesUsageMeter() {
        ScriptedChatModel model = new ScriptedChatModel("test-model", List.of(
                request -> ChatResponse.text("答案")));

        TokenBudgetMeter meter = new TokenBudgetMeter();
        AgentLoop loop = new AgentLoop(AgentConfig.builder(model, registry(OK)).usageMeter(meter).build());
        AgentResult result = loop.run("你好");

        assertTrue(result.usage().totalTokens() > 0);
        assertEquals(result.usage().totalTokens(), meter.totalTokens().totalTokens());
    }
}
