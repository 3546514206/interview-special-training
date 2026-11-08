package edu.zjnu.interview.ai.agent.multi;

import edu.zjnu.interview.ai.agent.AgentResult;
import edu.zjnu.interview.ai.agent.SpanFactory;
import edu.zjnu.interview.ai.agent.TerminationReason;
import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.Ids;
import edu.zjnu.interview.ai.core.Span;
import edu.zjnu.interview.ai.core.TokenUsage;
import edu.zjnu.interview.ai.core.TraceSink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主管 Agent（Supervisor）：分派任务给专家、汇总结果、决定是否再委派。
 *
 * <p><b>为什么需要「主管」这种角色</b>：多 Agent 协作最常见的失败模式不是「专家不够强」，
 * 而是<b>协作没有中心</b>——多个 Agent 各自为政，重复劳动、结论互相矛盾、或者干脆互相死循环。
 * Supervisor 提供三件事：
 * <ol>
 *   <li><b>唯一入口</b>：用户只跟 Supervisor 对话，由它决定谁能接这个活；</li>
 *   <li><b>统一上下文</b>：专家的结论汇总到一处，Supervisor 负责消解冲突、给出最终答案；</li>
 *   <li><b>预算与终止</b>：委派轮数、token 成本都在 Supervisor 这一层收敛，
 *       否则 N 个专家各自跑满预算，总成本就是 N 倍。</li>
 * </ol>
 *
 * <p><b>决策层次</b>：谁来做（Planner）→ 做什么（专家的 system prompt + 工具集）→
 * 做得对不对（专家自评 + Supervisor 汇总）。三层职责分开，
 * 每层都可以独立替换和单测，这是「业务级多 Agent 工程体系」的核心结构。
 */
public final class SupervisorAgent {

    private final String name;
    private final ChatModel model;
    private final List<SpecialistAgent> specialists;
    private final Planner planner;
    private final int maxRounds;
    private final TraceSink traceSink;

    private SupervisorAgent(Builder builder) {
        this.name = builder.name;
        this.model = builder.model;
        this.specialists = List.copyOf(builder.specialists);
        this.planner = builder.planner == null ? new KeywordPlanner() : builder.planner;
        this.maxRounds = builder.maxRounds;
        this.traceSink = builder.traceSink == null ? TraceSink.noop() : builder.traceSink;
    }

    public static Builder builder(String name, ChatModel model) {
        return new Builder(name, model);
    }

    public List<SpecialistAgent> specialists() {
        return specialists;
    }

    public String name() {
        return name;
    }

    /** 执行一次任务。 */
    public SupervisorResult run(String task) {
        String traceId = "trace_" + Ids.random();
        SpanFactory spans = new SpanFactory(traceId);
        String rootSpanId = spans.reserveSpanId();
        long start = spans.monotonicMillis();

        List<Delegation> delegations = new ArrayList<>();
        TokenUsage usage = TokenUsage.ZERO;
        int rounds = 0;

        for (int round = 1; round <= maxRounds; round++) {
            Planner.Plan plan = planner.plan(new SupervisorContext(task, round, delegations, specialists));
            if (plan.assignments().isEmpty()) {
                break;
            }
            rounds = round;
            for (Planner.Assignment assignment : plan.assignments()) {
                SpecialistAgent specialist = find(assignment.specialist());
                if (specialist == null) {
                    delegations.add(new Delegation(assignment.specialist(), assignment.task(),
                            "专家不存在: " + assignment.specialist(), false, 0, TokenUsage.ZERO));
                    continue;
                }
                long delegateStart = spans.monotonicMillis();
                AgentResult result = specialist.run(assignment.task());
                long delegateEnd = spans.monotonicMillis();

                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put("supervisor", name);
                attrs.put("specialist", specialist.name());
                attrs.put("round", round);
                attrs.put("success", result.success());
                attrs.put("steps", result.stepCount());
                traceSink.emit(spans.emit(spans.reserveSpanId(), "agent.delegate." + specialist.name(),
                        Span.KIND_AGENT_STEP, rootSpanId, attrs, delegateStart,
                        delegateEnd - delegateStart, !result.success()));

                delegations.add(new Delegation(specialist.name(), assignment.task(),
                        result.finalAnswer(), result.success(), result.stepCount(), result.usage()));
                usage = usage.plus(result.usage());
            }
        }

        String synthesis = synthesize(task, delegations);
        long end = spans.monotonicMillis();

        boolean anySuccess = delegations.stream().anyMatch(Delegation::success);
        TerminationReason termination;
        String failure;
        if (anySuccess) {
            termination = TerminationReason.FINAL_ANSWER;
            failure = null;
        } else if (delegations.isEmpty()) {
            termination = TerminationReason.MAX_STEPS;
            failure = "规划器没有产出任何委派计划";
        } else {
            termination = TerminationReason.CONSECUTIVE_TOOL_FAILURES;
            failure = "所有专家委派均失败";
        }

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("supervisor", name);
        attrs.put("rounds", rounds);
        attrs.put("delegations", delegations.size());
        attrs.put("termination", termination.name());
        traceSink.emit(spans.emit(rootSpanId, "agent.supervisor", Span.KIND_AGENT_STEP, null, attrs,
                start, end - start, termination.isFailure()));

        return new SupervisorResult(task, delegations, synthesis, rounds, traceId, usage, termination, failure);
    }

    /** 汇总专家结论，给出最终答案。 */
    private String synthesize(String task, List<Delegation> delegations) {
        if (delegations.isEmpty()) {
            return "";
        }
        if (model != null) {
            StringBuilder prompt = new StringBuilder();
            prompt.append("【汇总】请综合下列专家结论，给出面向用户的最终答案（不要编造资料中没有的内容）。\n");
            prompt.append("任务：").append(task).append('\n');
            for (Delegation delegation : delegations) {
                prompt.append("- [").append(delegation.specialist()).append("] ")
                        .append(delegation.answer()).append('\n');
            }
            try {
                ChatResponse response = model.chat(new ChatRequest(
                        List.of(ChatMessage.system("你是汇总者，负责把专家结论合并成一段清晰的最终答案。"),
                                ChatMessage.user(prompt.toString())),
                        List.of(), 0.0, 512, model.name()));
                String content = response == null ? "" : response.content();
                if (content != null && !content.isBlank()) {
                    return content;
                }
            } catch (Exception e) {
                // 汇总失败降级为「直接拼接专家结论」，不丢结果
                return joinAnswers(delegations);
            }
        }
        return joinAnswers(delegations);
    }

    /** 降级拼接。 */
    private String joinAnswers(List<Delegation> delegations) {
        StringBuilder sb = new StringBuilder();
        for (Delegation delegation : delegations) {
            if (delegation.success() && delegation.answer() != null && !delegation.answer().isBlank()) {
                sb.append('[').append(delegation.specialist()).append("] ")
                        .append(delegation.answer()).append('\n');
            }
        }
        return sb.toString().strip();
    }

    private SpecialistAgent find(String specialistName) {
        for (SpecialistAgent specialist : specialists) {
            if (specialist.name().equals(specialistName)) {
                return specialist;
            }
        }
        return null;
    }

    /**
     * 一次委派的结果。
     *
     * @param specialist 专家名
     * @param task       子任务
     * @param answer     专家给出的结论
     * @param success    是否成功
     * @param steps      专家内部的迭代步数
     * @param usage      专家消耗的 token
     */
    public record Delegation(String specialist, String task, String answer, boolean success,
                             int steps, TokenUsage usage) {
    }

    /**
     * 主管的运行结果。
     *
     * @param task              原始任务
     * @param delegations       委派明细
     * @param synthesis         汇总后的最终答案
     * @param rounds            实际委派轮数
     * @param traceId           链路 id
     * @param usage             token 用量
     * @param terminationReason 终止原因
     * @param failureReason     失败原因
     */
    public record SupervisorResult(String task,
                                   List<Delegation> delegations,
                                   String synthesis,
                                   int rounds,
                                   String traceId,
                                   TokenUsage usage,
                                   TerminationReason terminationReason,
                                   String failureReason) {

        public SupervisorResult {
            delegations = delegations == null ? List.of() : List.copyOf(delegations);
            synthesis = synthesis == null ? "" : synthesis;
            failureReason = failureReason == null ? "" : failureReason;
        }

        public boolean success() {
            return terminationReason == TerminationReason.FINAL_ANSWER;
        }

        /** 被委派的专家名列表。 */
        public List<String> delegatedSpecialists() {
            List<String> names = new ArrayList<>();
            for (Delegation delegation : delegations) {
                names.add(delegation.specialist());
            }
            return names;
        }
    }

    /** 主管构造器。 */
    public static final class Builder {
        private final String name;
        private final ChatModel model;
        private final List<SpecialistAgent> specialists = new ArrayList<>();
        private Planner planner;
        private int maxRounds = 3;
        private TraceSink traceSink = TraceSink.noop();

        private Builder(String name, ChatModel model) {
            this.name = name;
            this.model = model;
        }

        public Builder specialist(SpecialistAgent value) {
            this.specialists.add(value);
            return this;
        }

        public Builder specialists(List<SpecialistAgent> values) {
            if (values != null) {
                this.specialists.addAll(values);
            }
            return this;
        }

        /** 分派策略。 */
        public Builder planner(Planner value) {
            this.planner = value;
            return this;
        }

        /** 最大委派轮数。 */
        public Builder maxRounds(int value) {
            this.maxRounds = Math.max(1, value);
            return this;
        }

        public Builder traceSink(TraceSink value) {
            this.traceSink = value;
            return this;
        }

        public SupervisorAgent build() {
            if (specialists.isEmpty()) {
                throw new IllegalArgumentException("主管至少需要一个专家");
            }
            return new SupervisorAgent(this);
        }
    }
}
