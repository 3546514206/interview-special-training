package edu.zjnu.interview.ai.agent;

import edu.zjnu.interview.ai.agent.memory.ConversationState;
import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.Ids;
import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.core.Span;
import edu.zjnu.interview.ai.core.StopReason;
import edu.zjnu.interview.ai.core.TokenUsage;
import edu.zjnu.interview.ai.core.Tool;
import edu.zjnu.interview.ai.core.ToolCall;
import edu.zjnu.interview.ai.core.ToolContext;
import edu.zjnu.interview.ai.core.ToolResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent Loop：模型与工具之间的迭代协议。
 *
 * <p>它就是「while (模型还想调工具) { 调工具 → 回灌结果 }」这个循环，
 * 但生产上能用的版本必须补齐五件事，本类逐一处理：
 *
 * <ol>
 *   <li><b>上下文组装</b>：消息来自 {@link ConversationState}，超预算时先压缩再发给模型；</li>
 *   <li><b>工具调用</b>：校验参数 → 授权 → 执行 → 把结果<b>作为 tool 消息回灌</b>；</li>
 *   <li><b>终止条件</b>：最终答案 / 最大步数 / 预算超限 / 重复调用 / 连续失败；</li>
 *   <li><b>故障隔离</b>：工具抛异常不能让整条链路挂掉，必须转成结构化错误回灌给模型；</li>
 *   <li><b>可观测</b>：每一步发 Span，spanId 向下传递到工具上下文。</li>
 * </ol>
 *
 * <p><b>为什么工具异常必须转成错误回灌，而不是抛出去</b>：
 * 工具失败是 Agent 的正常分支（文件不存在、参数非法、外部接口超时）。
 * 如果异常冒泡到模型层，Agent 就失去了「知道自己失败了」的能力，
 * 既不能重试也不能换路，只能整体崩掉——这正是生产 Agent 最常见的可用性事故。
 *
 * <p><b>为什么要有「重复调用检测」</b>：模型陷入死循环时，
 * 最典型的表现就是反复调同一个工具、同一个参数（换个参数的重复不算）。
 * 靠步数上限止损太粗（一次合法循环可能跑很多步），
 * 靠「连续失败」也抓不到「工具成功但模型不收尾」的情况。
 * 所以单独特判「同一 (工具, 参数) 出现 N 次」。
 */
public final class AgentLoop {

    /** 默认系统提示。 */
    public static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个可以使用工具的智能体。需要外部信息或精确计算时调用工具；"
                    + "拿到工具结果后给出最终答案，不要重复调用同一个工具的同一组参数。";

    private final AgentConfig config;
    private final ConversationState state;

    public AgentLoop(AgentConfig config) {
        this(config, config.state());
    }

    public AgentLoop(AgentConfig config, ConversationState state) {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为空");
        }
        this.config = config;
        this.state = state != null ? state : ConversationState.of(DEFAULT_SYSTEM_PROMPT);
    }

    public AgentConfig config() {
        return config;
    }

    /** 当前会话状态（多轮对话时复用同一个 Loop 即可保持上下文）。 */
    public ConversationState state() {
        return state;
    }

    /** 执行一次对话（自动生成 traceId）。 */
    public AgentResult run(String userMessage) {
        return run(userMessage, "trace_" + Ids.random());
    }

    /**
     * 执行一次对话。
     *
     * @param userMessage 用户输入
     * @param traceId     链路 id（可由外部传入，用于把多条链路串起来）
     */
    public AgentResult run(String userMessage, String traceId) {
        SpanFactory spans = new SpanFactory(traceId);
        String rootSpanId = spans.reserveSpanId();
        long runStart = spans.monotonicMillis();

        if (userMessage != null && !userMessage.isBlank()) {
            state.addUser(userMessage);
        }

        RunState rs = new RunState();
        for (int index = 0; index < config.maxSteps(); index++) {
            BudgetVerdict verdict = budgetVerdict();
            if (!verdict.permitted()) {
                rs.termination = TerminationReason.BUDGET_EXCEEDED;
                rs.failure = verdict.reason();
                break;
            }
            step(index, rs, spans, rootSpanId);
            if (rs.termination != null) {
                break;
            }
        }
        if (rs.termination == null) {
            rs.termination = TerminationReason.MAX_STEPS;
            rs.failure = "达到最大步数 " + config.maxSteps() + " 仍未收敛";
        }

        long runEnd = spans.monotonicMillis();
        Map<String, Object> runAttrs = new LinkedHashMap<>();
        runAttrs.put("steps", rs.steps.size());
        runAttrs.put("tool_calls", rs.invocations.size());
        runAttrs.put("termination", rs.termination.name());
        runAttrs.put("total_tokens", rs.usage.totalTokens());
        config.traceSink().emit(spans.emit(rootSpanId, "agent.run", Span.KIND_AGENT_STEP, null,
                runAttrs, runStart, runEnd - runStart, rs.termination.isFailure()));

        return new AgentResult(rs.finalAnswer, rs.steps, rs.invocations, spans.traceId(),
                rs.usage, rs.termination, rs.failure, state.rawContext());
    }

    /** 单次迭代。 */
    private void step(int index, RunState rs, SpanFactory spans, String rootSpanId) {
        // ---------- 1) 组装上下文 ----------
        List<ChatMessage> messages = state.context();
        ChatRequest request = new ChatRequest(messages, config.tools().specs(),
                config.temperature(), config.maxOutputTokens(),
                config.model() == null ? "unknown" : config.model().name());

        // ---------- 2) 调模型 ----------
        String stepSpanId = spans.reserveSpanId();
        long llmStart = spans.monotonicMillis();
        ChatResponse response;
        String modelError = null;
        try {
            response = config.model().chat(request);
        } catch (Exception e) {
            // 模型异常同样不能炸掉链路：转成 ERROR 响应，走统一终止路径。
            modelError = e.getClass().getSimpleName() + ": " + e.getMessage();
            response = new ChatResponse("", List.of(), TokenUsage.ZERO, StopReason.ERROR, 0L);
        }
        long llmEnd = spans.monotonicMillis();

        TokenUsage usage = response.usage();
        if (config.usageMeter() != null) {
            config.usageMeter().charge(config.model() == null ? "unknown" : config.model().name(), usage);
        }
        rs.usage = rs.usage.plus(usage);

        Map<String, Object> llmAttrs = new LinkedHashMap<>();
        llmAttrs.put("model", request.model());
        llmAttrs.put("prompt_tokens", usage.promptTokens());
        llmAttrs.put("completion_tokens", usage.completionTokens());
        llmAttrs.put("tool_calls", response.toolCalls().size());
        llmAttrs.put("stop_reason", response.stopReason().name());
        llmAttrs.put("context_messages", messages.size());
        String llmSpanId = spans.reserveSpanId();
        config.traceSink().emit(spans.emit(llmSpanId, "llm.chat", Span.KIND_LLM, stepSpanId, llmAttrs,
                llmStart, llmEnd - llmStart, modelError != null));

        List<String> callNames = response.toolCalls().stream().map(ToolCall::name).toList();
        String llmSummary = response.hasToolCalls()
                ? "模型发起工具调用: " + String.join(", ", callNames)
                : "模型给出最终答案";

        // ---------- 3) 模型级错误终止 ----------
        if (response.stopReason() == StopReason.ERROR) {
            rs.steps.add(AgentStep.model(index, llmEnd - llmStart, usage.promptTokens(),
                    usage.completionTokens(), callNames, "模型调用失败: " + (modelError == null ? "ERROR" : modelError), true));
            emitStepSpan(spans, stepSpanId, rootSpanId, index, llmSummary, true, llmStart, llmEnd);
            rs.termination = TerminationReason.MODEL_ERROR;
            rs.failure = modelError == null ? "模型返回 ERROR" : modelError;
            return;
        }
        if (response.stopReason() == StopReason.CONTENT_FILTER) {
            rs.steps.add(AgentStep.model(index, llmEnd - llmStart, usage.promptTokens(),
                    usage.completionTokens(), callNames, "模型输出被内容安全过滤", true));
            emitStepSpan(spans, stepSpanId, rootSpanId, index, "内容安全过滤", true, llmStart, llmEnd);
            rs.termination = TerminationReason.CONTENT_FILTERED;
            rs.failure = "模型输出被内容安全过滤";
            return;
        }

        // 记录 assistant 消息（带工具调用时用 assistantToolCalls，保留原始调用信息）
        if (response.hasToolCalls()) {
            state.add(ChatMessage.assistantToolCalls(response.toolCalls()));
        } else {
            state.add(ChatMessage.assistant(response.content()));
        }
        rs.steps.add(AgentStep.model(index, llmEnd - llmStart, usage.promptTokens(),
                usage.completionTokens(), callNames, llmSummary, false));

        // ---------- 4) 收尾：模型给出最终答案 ----------
        if (!response.hasToolCalls()) {
            rs.finalAnswer = response.content();
            if (response.stopReason() == StopReason.LENGTH) {
                rs.termination = TerminationReason.MODEL_TRUNCATED;
                rs.failure = "模型输出被长度限制截断，答案可能不完整";
            } else {
                rs.termination = TerminationReason.FINAL_ANSWER;
            }
            emitStepSpan(spans, stepSpanId, rootSpanId, index, llmSummary, false, llmStart, llmEnd);
            return;
        }

        // ---------- 5) 工具执行 + 结果回灌 ----------
        boolean loopDetected = false;
        String loopSignature = null;
        for (ToolCall call : response.toolCalls()) {
            String toolSpanId = spans.reserveSpanId();
            long toolStart = spans.monotonicMillis();

            ToolResult result = executeToolCall(call, toolSpanId, spans.traceId(), index);

            long toolEnd = spans.monotonicMillis();
            Map<String, Object> toolAttrs = new LinkedHashMap<>();
            toolAttrs.put("tool", call.name());
            toolAttrs.put("call_id", call.id());
            toolAttrs.put("success", result.success());
            toolAttrs.put("error_code", result.errorCode());
            toolAttrs.put("arguments", call.argumentsJson());
            config.traceSink().emit(spans.emit(toolSpanId, "tool.exec." + call.name(), Span.KIND_TOOL,
                    stepSpanId, toolAttrs, toolStart, toolEnd - toolStart, !result.success()));

            // 回灌给模型：工具结果必须是 role=TOOL 且带 toolCallId，否则模型无法对齐调用
            state.add(ChatMessage.toolResult(call.id(), call.name(), result.content()));
            rs.invocations.add(new ToolInvocation(call.id(), call.name(), call.argumentsJson(),
                    result.success(), result.errorCode(), truncate(result.content()),
                    toolEnd - toolStart, toolSpanId));
            rs.steps.add(AgentStep.tool(index, toolEnd - toolStart, call.name(),
                    (result.success() ? "工具成功" : "工具失败[" + result.errorCode() + "]"), !result.success()));

            if (result.success()) {
                rs.consecutiveFailures = 0;
            } else {
                rs.consecutiveFailures++;
            }

            // 死循环检测：同一 (工具, 参数) 重复出现
            String signature = signature(call);
            int count = rs.signatureCounts.merge(signature, 1, Integer::sum);
            if (count >= config.repeatCallThreshold()) {
                loopDetected = true;
                loopSignature = signature;
            }
        }

        emitStepSpan(spans, stepSpanId, rootSpanId, index, llmSummary, false, llmStart, llmEnd);

        // ---------- 6) 终止条件 ----------
        if (loopDetected) {
            rs.termination = TerminationReason.LOOP_DETECTED;
            rs.failure = "同一工具与参数重复调用达到 " + config.repeatCallThreshold() + " 次: " + loopSignature;
            return;
        }
        if (rs.consecutiveFailures >= config.maxConsecutiveToolFailures()) {
            rs.termination = TerminationReason.CONSECUTIVE_TOOL_FAILURES;
            rs.failure = "连续 " + rs.consecutiveFailures + " 次工具调用失败";
        }
    }

    /**
     * 执行一次工具调用：<b>校验 → 授权 → 执行</b>，任何异常都转成结构化错误。
     */
    private ToolResult executeToolCall(ToolCall call, String spanId, String traceId, int stepIndex) {
        // 1) 参数校验
        ToolRegistry.ValidationResult validation = config.tools().validate(call);
        if (!validation.valid()) {
            return validation.toToolResult();
        }
        Tool tool = config.tools().require(call.name());

        // 2) 权限控制（授权发生在执行之前；拒绝理由回灌给模型）
        ToolContext ctx = new ToolContext(traceId, spanId, config.workingDirectory(),
                deadline(), Map.of("step", stepIndex));
        String denial;
        try {
            denial = config.authorizer() == null ? null : config.authorizer().authorize(call, ctx);
        } catch (Exception e) {
            denial = "授权器异常，按拒绝处理: " + e.getMessage();
        }
        if (denial != null) {
            return ToolResult.error("PERMISSION_DENIED", denial);
        }

        // 3) 执行（异常隔离）
        try {
            ToolResult result = tool.execute(call, ctx);
            return result == null
                    ? ToolResult.error("NULL_RESULT", "工具 " + call.name() + " 返回了 null")
                    : result;
        } catch (Throwable t) {
            return ToolResult.error("TOOL_EXECUTION_FAILED",
                    t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        }
    }

    private Instant deadline() {
        Duration timeout = config.toolTimeout();
        return timeout == null ? null : Instant.now().plus(timeout);
    }

    /** 预算预检：在调用模型之前问「还能再花吗」。 */
    private BudgetVerdict budgetVerdict() {
        if (config.maxTotalTokens() > 0 && config.usageMeter() != null) {
            int used = config.usageMeter().totalTokens().totalTokens();
            if (used >= config.maxTotalTokens()) {
                return BudgetVerdict.denied("token 预算超限：已用 " + used
                        + "，上限 " + config.maxTotalTokens());
            }
        }
        if (config.maxCostUsd() < Double.MAX_VALUE && config.usageMeter() != null) {
            double spent = config.usageMeter().spentUsd();
            if (spent >= config.maxCostUsd()) {
                return BudgetVerdict.denied(String.format("成本预算超限：已花 %.6f 美元，上限 %.6f 美元",
                        spent, config.maxCostUsd()));
            }
        }
        return BudgetVerdict.ok();
    }

    private void emitStepSpan(SpanFactory spans, String stepSpanId, String rootSpanId, int index,
                              String summary, boolean error, long startMillis, long endMillis) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("step", index);
        attrs.put("summary", summary);
        config.traceSink().emit(spans.emit(stepSpanId, "agent.step", Span.KIND_AGENT_STEP, rootSpanId, attrs,
                startMillis, Math.max(0, endMillis - startMillis), error));
    }

    /** 调用签名：工具名 + 规范化参数（参数顺序不影响判定）。 */
    static String signature(ToolCall call) {
        Map<String, Object> args = Json.readMap(call.argumentsJson());
        return call.name() + "|" + canonical(args);
    }

    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(Comparator.comparing(e -> String.valueOf(e.getKey())))
                    .map(e -> String.valueOf(e.getKey()) + "=" + canonical(e.getValue()))
                    .collect(Collectors.joining(","));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AgentLoop::canonical).collect(Collectors.joining(",", "[", "]"));
        }
        return String.valueOf(value);
    }

    private static String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 500 ? content : content.substring(0, 500) + "…";
    }

    /** 预算判定结果。 */
    private record BudgetVerdict(boolean permitted, String reason) {
        static BudgetVerdict ok() {
            return new BudgetVerdict(true, null);
        }

        static BudgetVerdict denied(String reason) {
            return new BudgetVerdict(false, reason);
        }
    }

    /** 一次运行过程中的可变状态。 */
    private static final class RunState {
        final List<AgentStep> steps = new ArrayList<>();
        final List<ToolInvocation> invocations = new ArrayList<>();
        final Map<String, Integer> signatureCounts = new LinkedHashMap<>();
        TokenUsage usage = TokenUsage.ZERO;
        int consecutiveFailures;
        String finalAnswer = "";
        TerminationReason termination;
        String failure;
    }
}
