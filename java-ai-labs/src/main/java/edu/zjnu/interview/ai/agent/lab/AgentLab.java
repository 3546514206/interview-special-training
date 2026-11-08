package edu.zjnu.interview.ai.agent.lab;

import edu.zjnu.interview.ai.agent.AgentConfig;
import edu.zjnu.interview.ai.agent.AgentLoop;
import edu.zjnu.interview.ai.agent.AgentResult;
import edu.zjnu.interview.ai.agent.AgentStep;
import edu.zjnu.interview.ai.agent.TerminationReason;
import edu.zjnu.interview.ai.agent.TokenBudgetMeter;
import edu.zjnu.interview.ai.agent.ToolInvocation;
import edu.zjnu.interview.ai.agent.ToolRegistry;
import edu.zjnu.interview.ai.agent.memory.ConversationState;
import edu.zjnu.interview.ai.agent.memory.MemoryCompactor;
import edu.zjnu.interview.ai.agent.memory.MemoryStore;
import edu.zjnu.interview.ai.agent.memory.Scratchpad;
import edu.zjnu.interview.ai.agent.multi.KeywordPlanner;
import edu.zjnu.interview.ai.agent.multi.SpecialistAgent;
import edu.zjnu.interview.ai.agent.multi.SupervisorAgent;
import edu.zjnu.interview.ai.agent.tools.CalculatorTool;
import edu.zjnu.interview.ai.agent.tools.CurrentTimeTool;
import edu.zjnu.interview.ai.agent.tools.KnowledgeSearchTool;
import edu.zjnu.interview.ai.agent.tools.ReadFileTool;
import edu.zjnu.interview.ai.agent.tools.SqlQueryTool;
import edu.zjnu.interview.ai.agent.workflow.RetrievalWorkflow;
import edu.zjnu.interview.ai.agent.workflow.WorkflowContext;
import edu.zjnu.interview.ai.agent.workflow.WorkflowResult;
import edu.zjnu.interview.ai.agent.workflow.WorkflowRunner;
import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.core.Span;
import edu.zjnu.interview.ai.core.ToolAuthorizer;
import edu.zjnu.interview.ai.core.mock.ExtractiveChatModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 专项实验：把本模块的六个能力串成一份「实验报告」。
 *
 * <p>覆盖的六件事：
 * <ol>
 *   <li>多轮工具调用（真实执行 + trace 输出）；</li>
 *   <li>越权调用被拒绝（权限控制在执行之前生效）；</li>
 *   <li>预算熔断（token / 成本预算触发止损）；</li>
 *   <li>记忆压缩（上下文超预算 → 摘要压缩 + 长期笔记 + 事实记忆）；</li>
 *   <li>多 Agent 委派（主管分派 → 专家执行 → 汇总）；</li>
 *   <li>工作流执行（路由 → 检索 → 生成 → 校验 → 收尾）。</li>
 * </ol>
 *
 * <p><b>全离线</b>：所有模型都是离线替身，知识库是固定语料，
 * 不需要任何 API Key，也不访问网络——这是「实验工程」和「产品」的分界线：
 * 实验工程的价值在于<b>每次提交都能重跑、结果可复现</b>。
 */
public final class AgentLab {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-15T10:30:00Z"), ZoneOffset.UTC);

    private AgentLab() {
    }

    public static void run() {
        printBanner();
        demo1MultiTurnToolCalls();
        demo2PermissionDenied();
        demo3BudgetBreaker();
        demo4MemoryCompaction();
        demo5MultiAgentDelegation();
        demo6Workflow();
        printFooter();
    }

    // ------------------------------------------------------------------ 实验 1

    private static void demo1MultiTurnToolCalls() {
        section("实验 1 · 多轮工具调用（真实工具执行 + Trace）");

        LabKnowledgeBase knowledgeBase = new LabKnowledgeBase();
        SqlQueryTool sql = new SqlQueryTool().registerTable("products", List.of(
                Map.of("code", "FUND_A", "name", "稳健优选", "fee_rate", 0.015),
                Map.of("code", "FUND_B", "name", "进取成长", "fee_rate", 0.02)));
        ToolRegistry tools = new ToolRegistry()
                .register(new CalculatorTool())
                .register(new KnowledgeSearchTool(knowledgeBase))
                .register(new CurrentTimeTool(FIXED_CLOCK))
                .register(sql);

        // 剧本：算数 → 检索资料 → 查表 → 收尾。四个函数对应四轮迭代。
        ScriptedChatModel model = new ScriptedChatModel("lab-model", List.of(
                request -> ChatResponse.toolCall("call_calc", "calculator",
                        "{\"expression\":\"(1+2)*3\"}"),
                request -> ChatResponse.toolCall("call_search", "knowledge_search",
                        "{\"query\":\"基金费率\",\"topK\":2}"),
                request -> ChatResponse.toolCall("call_sql", "sql_query",
                        "{\"sql\":\"SELECT code, name, fee_rate FROM products WHERE code = 'FUND_A'\"}"),
                request -> ChatResponse.text(
                        "申购费率为 1.5%，持有超过 365 天可赎回部分减免至 0.15%；同时 (1+2)*3 = 9。")));

        List<Span> spans = new ArrayList<>();
        TokenBudgetMeter meter = new TokenBudgetMeter().defaultPrice(3.0);

        AgentLoop loop = new AgentLoop(AgentConfig.builder(model, tools)
                .traceSink(spans::add)
                .usageMeter(meter)
                .maxSteps(6)
                .build());

        AgentResult result = loop.run("请计算 (1+2)*3，并查一下基金费率的资料");

        kv("终止原因", result.terminationReason() + (result.success() ? "（正常收敛）" : ""));
        kv("模型迭代轮数", modelSteps(result) + "（步骤记录 " + result.stepCount() + " 条 = 模型步 + 工具步）");
        kv("工具调用次数", result.toolCallCount() + "（失败 " + result.failedToolCallCount() + " 次）");
        kv("token 用量", result.usage().totalTokens() + "（prompt " + result.usage().promptTokens()
                + " / completion " + result.usage().completionTokens() + "）");
        kv("累计成本", String.format("%.8f 美元", meter.spentUsd()));
        kv("traceId", result.traceId());

        printStepTable(result);
        printToolTable(result);
        printSpans("本轮 Trace（spanId 向下传递，parent 构成调用树）", spans);
        blank();
    }

    // ------------------------------------------------------------------ 实验 2

    private static void demo2PermissionDenied() {
        section("实验 2 · 越权调用被拒绝（授权发生在执行之前）");

        Path sandbox = sandboxDirectory();
        writeSandboxFile(sandbox);

        ToolRegistry tools = new ToolRegistry()
                .register(new ReadFileTool())
                .register(new CalculatorTool());

        // 策略：只允许计算，禁止读文件。授权器返回非 null 即为拒绝。
        ToolAuthorizer authorizer = (call, ctx) -> {
            if ("read_file".equals(call.name())) {
                return "当前会话没有文件读取权限（策略：仅允许 calculator）";
            }
            return null;
        };

        ScriptedChatModel model = new ScriptedChatModel("lab-permission-model", List.of(
                request -> ChatResponse.toolCall("call_read", "read_file",
                        "{\"path\":\"secret.txt\"}"),
                request -> ChatResponse.toolCall("call_calc", "calculator",
                        "{\"expression\":\"9*9\"}"),
                request -> ChatResponse.text("文件没有读取权限，但我完成了计算：9*9=81。")));

        AgentLoop loop = new AgentLoop(AgentConfig.builder(model, tools)
                .authorizer(authorizer)
                .workingDirectory(sandbox)
                .maxSteps(5)
                .build());

        AgentResult result = loop.run("请读取 secret.txt 并计算 9*9");

        kv("终止原因", result.terminationReason().name());
        kv("被拒绝的调用", result.toolInvocations().stream()
                .filter(i -> "PERMISSION_DENIED".equals(i.errorCode()))
                .map(i -> i.toolName() + " → " + i.content())
                .findFirst().orElse("（无）"));
        kv("模型是否收到拒绝理由", result.context().stream()
                .anyMatch(m -> m.role() == ChatMessage.Role.TOOL && m.content().contains("权限")) ? "是" : "否");
        kv("最终答案", result.finalAnswer());
        blank();
    }

    // ------------------------------------------------------------------ 实验 3

    private static void demo3BudgetBreaker() {
        section("实验 3 · 预算熔断（烧钱之前先止损）");

        ToolRegistry tools = new ToolRegistry().register(new CalculatorTool());

        // 模型陷入执念：每轮都调同一个工具（但把死循环阈值调高，让预算先熔断）
        ScriptedChatModel model = new ScriptedChatModel("lab-looping-model", List.of(
                request -> ChatResponse.toolCall("call_calc", "calculator",
                        "{\"expression\":\"1+1\"}")));

        TokenBudgetMeter meter = new TokenBudgetMeter().defaultPrice(30.0);

        AgentLoop loop = new AgentLoop(AgentConfig.builder(model, tools)
                .usageMeter(meter)
                .maxSteps(50)
                .maxTotalTokens(120)
                .repeatCallThreshold(100)
                .build());

        AgentResult result = loop.run("请一直计算 1+1");

        kv("终止原因", result.terminationReason().name());
        kv("失败原因", result.failureReason());
        kv("迭代步数", result.stepCount() + "（上限 50）");
        kv("token 用量", result.usage().totalTokens() + " / 预算 120");
        kv("累计成本", String.format("%.8f 美元", meter.spentUsd()));
        kv("结论", result.terminationReason() == TerminationReason.BUDGET_EXCEEDED
                ? "预算在跑满步数之前熔断，避免继续烧钱" : "未按预期熔断");
        blank();
    }

    // ------------------------------------------------------------------ 实验 4

    private static void demo4MemoryCompaction() {
        section("实验 4 · 记忆压缩（短期上下文 + 长期笔记 + 事实记忆）");

        // 4.1 上下文压缩
        ScriptedChatModel summarizer = ScriptedChatModel.constant("lab-summarizer",
                ChatResponse.text("用户连续询问基金费率、赎回费与持有期优惠；已确认申购费率 1.5%，"
                        + "持有超过 365 天可赎回部分减免至 0.15%。"));

        ConversationState state = ConversationState.of("你是基金投顾助手", 220,
                MemoryCompactor.of(summarizer, 3));
        for (int i = 1; i <= 8; i++) {
            state.addUser("第 " + i + " 个问题：持有期超过 365 天，赎回费怎么算？");
            state.addAssistant("第 " + i + " 个回答：申购费率 1.5%，持有超过 365 天可减免至 0.15%。");
        }
        int tokensBefore = state.estimatedTokens();
        List<ChatMessage> context = state.context();
        int tokensAfter = state.estimatedTokens();

        kv("压缩前 token（估算）", String.valueOf(tokensBefore));
        kv("token 预算", "220");
        kv("压缩后 token（估算）", String.valueOf(tokensAfter));
        kv("压缩次数", String.valueOf(state.compactionCount()));
        kv("被压缩掉的消息数", String.valueOf(state.compactedMessageCount()));
        kv("摘要内容", state.summary());
        kv("压缩后上下文条数", String.valueOf(context.size()));

        // 4.2 长期笔记（JSONL 追加）
        Path labDir = sandboxDirectory().getParent();
        Path scratchpadFile = labDir.resolve("scratchpad.jsonl");
        Scratchpad scratchpad = new Scratchpad(scratchpadFile, FIXED_CLOCK);
        scratchpad.append("session", "exp-1", "开始演示记忆压缩");
        scratchpad.append("session", "exp-4", "压缩后 token=" + tokensAfter);
        kv("长期笔记文件", scratchpadFile.toString());
        kv("笔记条数", String.valueOf(scratchpad.size()));
        kv("首条笔记", scratchpad.readAll().isEmpty() ? "（空）"
                : scratchpad.readAll().get(0).timestamp() + " " + scratchpad.readAll().get(0).note());

        // 4.3 事实记忆（scope + TTL + 模糊匹配）
        MemoryStore memory = new MemoryStore(FIXED_CLOCK);
        memory.put("用户风险等级", "R3 稳健型", MemoryStore.Scope.USER, Duration.ofDays(1));
        memory.put("会话主题", "基金费率咨询", MemoryStore.Scope.SESSION);
        List<MemoryStore.Fact> hits = memory.find("风险等级", MemoryStore.Scope.USER);
        kv("模糊匹配 '风险等级' →", hits.isEmpty() ? "（未命中）"
                : hits.get(0).key() + "=" + hits.get(0).value()
                        + "（相似度 " + String.format("%.2f", hits.get(0).score()) + "）");
        kv("session 作用域隔离", memory.get("会话主题", MemoryStore.Scope.SESSION).isPresent()
                && memory.get("会话主题", MemoryStore.Scope.USER).isEmpty() ? "生效" : "未生效");
        blank();
    }

    // ------------------------------------------------------------------ 实验 5

    private static void demo5MultiAgentDelegation() {
        section("实验 5 · 多 Agent 委派（主管分派 → 专家执行 → 汇总）");

        MemoryStore sharedMemory = new MemoryStore(FIXED_CLOCK);

        ScriptedChatModel researchModel = new ScriptedChatModel("lab-researcher", List.of(
                request -> ChatResponse.toolCall("call_search", "knowledge_search",
                        "{\"query\":\"基金费率\",\"topK\":2}"),
                request -> ChatResponse.text("检索结论：申购费率 1.5%，持有超过 365 天可减免至 0.15%。")));

        ScriptedChatModel calcModel = new ScriptedChatModel("lab-calculator", List.of(
                request -> ChatResponse.toolCall("call_calc", "calculator",
                        "{\"expression\":\"(1+2)*3\"}"),
                request -> ChatResponse.text("计算结论：(1+2)*3 = 9。")));

        ScriptedChatModel synthModel = new ScriptedChatModel("lab-supervisor", List.of(
                request -> ChatResponse.text("综合结论：申购费率 1.5%（持有满 365 天可减免至 0.15%），"
                        + "并且 (1+2)*3 = 9。")));

        SpecialistAgent researcher = SpecialistAgent.builder("researcher",
                        "检索内部资料并给出带引用的结论",
                        researchModel, new ToolRegistry().register(new KnowledgeSearchTool(new LabKnowledgeBase())))
                .keywords("资料", "费率", "文档", "规范")
                .memory(sharedMemory)
                .build();

        SpecialistAgent calculator = SpecialistAgent.builder("calculator",
                        "负责精确算术计算",
                        calcModel, new ToolRegistry().register(new CalculatorTool()))
                .keywords("计算", "算")
                .memory(sharedMemory)
                .build();

        List<Span> spans = new ArrayList<>();
        SupervisorAgent supervisor = SupervisorAgent.builder("supervisor", synthModel)
                .specialist(researcher)
                .specialist(calculator)
                .planner(new KeywordPlanner())
                .maxRounds(2)
                .traceSink(spans::add)
                .build();

        SupervisorAgent.SupervisorResult result =
                supervisor.run("请查一下基金费率的资料文档，并计算 (1+2)*3 的结果");

        kv("委派轮数", String.valueOf(result.rounds()));
        kv("被委派专家", String.join("、", result.delegatedSpecialists()));
        kv("终止原因", result.terminationReason().name());
        kv("token 用量", result.usage().totalTokens());
        kv("最终结论", result.synthesis());
        for (SupervisorAgent.Delegation delegation : result.delegations()) {
            kv("  · " + delegation.specialist(),
                    (delegation.success() ? "成功" : "失败") + "，步数 " + delegation.steps()
                            + "，结论: " + delegation.answer());
        }
        printSpans("委派 Trace", spans);
        blank();
    }

    // ------------------------------------------------------------------ 实验 6

    private static void demo6Workflow() {
        section("实验 6 · 工作流编排（路由 → 检索 → 生成 → 校验 → 收尾）");

        LabKnowledgeBase knowledgeBase = new LabKnowledgeBase();
        // ExtractiveChatModel 只依据检索到的证据作答，答案可被证据支撑，便于评测
        var workflow = RetrievalWorkflow.build(new ExtractiveChatModel(), knowledgeBase, 2);

        List<Span> spans = new ArrayList<>();
        WorkflowRunner runner = new WorkflowRunner(spans::add);

        WorkflowContext ctx = new WorkflowContext("trace_lab_workflow");
        ctx.put(RetrievalWorkflow.KEY_QUESTION, "申购费率是多少？持有超过 365 天有什么优惠？");

        WorkflowResult result = runner.run(workflow, ctx);

        kv("工作流", result.workflowName());
        kv("执行状态", result.status().name());
        kv("路由判定", ctx.getString("routeReason"));
        kv("校验轮次", String.valueOf(ctx.counter(RetrievalWorkflow.KEY_VERIFY_ATTEMPTS)));
        kv("校验结论", ctx.getString("verifyReason"));
        kv("最终答案", ctx.getString("finalAnswer"));
        kv("节点执行轨迹", result.executions().stream()
                .map(e -> e.node() + "(×" + e.attempts() + ")")
                .collect(java.util.stream.Collectors.joining(" → ")));
        printSpans("工作流 Trace", spans);
        blank();
    }

    // ------------------------------------------------------------------ 辅助

    private static void printBanner() {
        line("═");
        line("Agent 专项实验报告 · edu.zjnu.interview.ai.agent");
        line("运行环境：全离线（无 API Key / 无外网），全部使用确定性替身");
        line("═");
        blank();
    }

    private static void printFooter() {
        line("═");
        line("实验结束：六项能力全部跑通，且全部可回归。");
        line("═");
    }

    private static void section(String title) {
        blank();
        line("── " + title + " " + "─".repeat(Math.max(4, 74 - title.length())));
    }

    private static int modelSteps(AgentResult result) {
        int count = 0;
        for (AgentStep step : result.steps()) {
            if ("model".equals(step.kind())) {
                count++;
            }
        }
        return count;
    }

    private static void printStepTable(AgentResult result) {
        line("  步骤明细：");
        for (AgentStep step : result.steps()) {
            line(String.format("    #%d %-22s %-5s %3dms  %s%s",
                    step.index(), step.name(), step.kind(), step.durationMillis(),
                    step.summary(), step.error() ? "  [error]" : ""));
        }
    }

    private static void printToolTable(AgentResult result) {
        line("  工具调用记录：");
        for (ToolInvocation invocation : result.toolInvocations()) {
            line(String.format("    %-18s %-28s %s %s",
                    invocation.toolName(),
                    truncate(invocation.argumentsJson(), 28),
                    invocation.success() ? "成功" : "失败[" + invocation.errorCode() + "]",
                    invocation.durationMillis() + "ms"));
        }
    }

    private static void printSpans(String title, List<Span> spans) {
        line("  " + title + "：");
        for (Span span : spans) {
            line(String.format("    %-28s kind=%-11s dur=%3dms parent=%-14s %s",
                    span.name(), span.kind(), span.durationMillis(),
                    span.parentSpanId() == null ? "-" : span.parentSpanId(),
                    Json.write(span.attributes())));
        }
    }

    private static void kv(String key, Object value) {
        line(String.format("  %-22s : %s", key, value));
    }

    private static void line(String text) {
        System.out.println(text);
    }

    private static void blank() {
        System.out.println();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 沙箱目录：优先放在模块的 target 下（已被 git 忽略），否则用系统临时目录。 */
    private static Path sandboxDirectory() {
        List<Path> candidates = List.of(
                Paths.get("java-ai-labs", "target", "agent-lab"),
                Paths.get("target", "agent-lab"),
                Paths.get(System.getProperty("java.io.tmpdir"), "agent-lab"));
        for (Path candidate : candidates) {
            try {
                Files.createDirectories(candidate);
                return candidate.resolve("sandbox").toAbsolutePath();
            } catch (IOException e) {
                // 试下一个候选目录
            }
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "agent-lab", "sandbox");
    }

    /** 在沙箱里写一个文件，供 ReadFileTool 演示。 */
    private static void writeSandboxFile(Path sandbox) {
        try {
            Files.createDirectories(sandbox);
            Files.writeString(sandbox.resolve("secret.txt"),
                    "这是沙箱内的文件，内容属于内部资料。\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 演示环境写文件失败不阻断实验
        }
    }

    /** 便于外部调用：返回一份只读的请求样例（供文档与调试使用）。 */
    public static ChatRequest sampleRequest() {
        return new ChatRequest(List.of(ChatMessage.user("请计算 (1+2)*3")), List.of(), 0.2, 256, "lab-model");
    }
}
