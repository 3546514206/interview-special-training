package edu.zjnu.interview.ai.agent.workflow;

import edu.zjnu.interview.ai.core.Span;
import edu.zjnu.interview.ai.core.mock.ExtractiveChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流编排：条件路由、节点级重试、失败中止、回环熔断、可观测。
 */
class WorkflowTest {

    private static WorkflowContext ctx() {
        WorkflowContext context = new WorkflowContext("trace-wf-test");
        context.put("question", "申购费率是多少？");
        return context;
    }

    @Test
    @DisplayName("条件边：按条件走不同分支")
    void routesByCondition() {
        Workflow workflow = Workflow.builder("branch")
                .node("start", c -> NodeOutput.ok("开始"))
                .node("left", c -> NodeOutput.ok("走左分支"))
                .node("right", c -> NodeOutput.ok("走右分支"))
                .edge("start", "left", (c, o) -> Boolean.TRUE.equals(c.get("goLeft")), "条件满足")
                .edge("start", "right", (c, o) -> !Boolean.TRUE.equals(c.get("goLeft")), "兜底")
                .entry("start")
                .build();

        WorkflowContext context = ctx();
        context.put("goLeft", true);
        WorkflowResult result = new WorkflowRunner(null).run(workflow, context);

        assertTrue(result.success());
        assertEquals("走左分支", result.output());
        assertEquals(List.of("start", "left"), result.executions().stream()
                .map(WorkflowResult.NodeExecution::node).toList());
    }

    @Test
    @DisplayName("节点级重试：失败两次后第三次成功，工作流继续")
    void retriesFailedNode() {
        AtomicInteger attempts = new AtomicInteger();
        Workflow workflow = Workflow.builder("retry")
                .node(WorkflowNode.builder("flaky")
                        .handler(c -> {
                            if (attempts.incrementAndGet() < 3) {
                                throw new IllegalStateException("暂时失败");
                            }
                            return NodeOutput.ok("终于成功");
                        })
                        .retries(2)
                        .build())
                .node("after", c -> NodeOutput.ok("后续节点"))
                .edge("flaky", "after")
                .entry("flaky")
                .build();

        WorkflowResult result = new WorkflowRunner(null).run(workflow, ctx());

        assertTrue(result.success());
        assertEquals(3, result.executions().get(0).attempts());
        assertEquals("终于成功", result.executions().get(0).value());
        assertEquals("后续节点", result.output());
    }

    @Test
    @DisplayName("重试耗尽 → 工作流失败，并给出失败原因")
    void failsWhenRetriesExhausted() {
        Workflow workflow = Workflow.builder("always-fail")
                .node(WorkflowNode.builder("boom")
                        .handler(c -> {
                            throw new IllegalStateException("一直失败");
                        })
                        .retries(1)
                        .build())
                .entry("boom")
                .build();

        WorkflowResult result = new WorkflowRunner(null).run(workflow, ctx());

        assertEquals(WorkflowResult.Status.FAILED, result.status());
        assertFalse(result.success());
        assertTrue(result.failureReason().contains("一直失败"));
        assertEquals(2, result.executions().get(0).attempts());
    }

    @Test
    @DisplayName("节点超时 → 记为失败并可重试")
    void enforcesNodeTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Workflow workflow = Workflow.builder("timeout")
                .node(WorkflowNode.builder("slow")
                        .handler(c -> {
                            release.await(300, TimeUnit.MILLISECONDS);
                            return NodeOutput.ok("太慢了");
                        })
                        .retries(0)
                        .timeout(Duration.ofMillis(20))
                        .build())
                .entry("slow")
                .build();

        WorkflowResult result = new WorkflowRunner(null).run(workflow, ctx());
        release.countDown();

        assertEquals(WorkflowResult.Status.FAILED, result.status());
        assertTrue(result.failureReason().contains("超时"));
    }

    @Test
    @DisplayName("条件回环：校验不过回到检索，次数用尽后收尾")
    void loopsBackUntilLimit() {
        AtomicInteger retrieveCount = new AtomicInteger();
        Workflow workflow = Workflow.builder("loop")
                .node("retrieve", c -> {
                    retrieveCount.incrementAndGet();
                    return NodeOutput.ok("证据");
                })
                .node("verify", c -> {
                    int attempt = c.increment("verifyAttempts");
                    return NodeOutput.ok(attempt >= 2);
                })
                .node("finalize", c -> NodeOutput.ok("收尾"))
                .edge("retrieve", "verify")
                .edge("verify", "retrieve",
                        (c, o) -> c.counter("verifyAttempts") < 2, "不通过就重来")
                .edge("verify", "finalize",
                        (c, o) -> c.counter("verifyAttempts") >= 2, "通过就收尾")
                .entry("retrieve")
                .build();

        WorkflowResult result = new WorkflowRunner(null).run(workflow, ctx());

        assertTrue(result.success());
        assertEquals(2, retrieveCount.get());
        assertEquals("收尾", result.output());
        assertEquals(List.of("retrieve", "verify", "retrieve", "verify", "finalize"),
                result.executions().stream().map(WorkflowResult.NodeExecution::node).toList());
    }

    @Test
    @DisplayName("回环没有收敛时被熔断（ABORTED），不会无限执行")
    void abortsOnRunawayLoop() {
        Workflow workflow = Workflow.builder("runaway")
                .node("a", c -> NodeOutput.ok("a"))
                .node("b", c -> NodeOutput.ok("b"))
                .edge("a", "b")
                .edge("b", "a")
                .entry("a")
                .build();

        WorkflowResult result = new WorkflowRunner(null, 6).run(workflow, ctx());

        assertEquals(WorkflowResult.Status.ABORTED, result.status());
        assertTrue(result.failureReason().contains("最大节点执行次数"));
    }

    @Test
    @DisplayName("工作流整体发 Trace，节点 span 的父节点是根 span")
    void emitsWorkflowSpans() {
        List<Span> spans = new ArrayList<>();
        Workflow workflow = Workflow.builder("traced")
                .node("a", c -> NodeOutput.ok("a"))
                .node("b", c -> NodeOutput.ok("b"))
                .edge("a", "b")
                .entry("a")
                .build();

        new WorkflowRunner(spans::add).run(workflow, ctx());

        assertTrue(spans.stream().anyMatch(s -> s.name().equals("workflow.node.a")));
        assertTrue(spans.stream().anyMatch(s -> s.name().equals("workflow.run")));
        Span root = spans.stream().filter(s -> s.name().equals("workflow.run")).findFirst().orElseThrow();
        assertTrue(spans.stream().filter(s -> s.name().startsWith("workflow.node"))
                .allMatch(s -> root.spanId().equals(s.parentSpanId())));
    }

    @Test
    @DisplayName("拓扑序包含全部节点（容忍条件回环带来的环）")
    void topologicalOrderCoversAllNodes() {
        Workflow workflow = Workflow.builder("topo")
                .node("a", c -> NodeOutput.ok("a"))
                .node("b", c -> NodeOutput.ok("b"))
                .node("c", c -> NodeOutput.ok("c"))
                .edge("a", "b")
                .edge("b", "c")
                .entry("a")
                .build();

        List<String> order = workflow.topologicalOrder();

        assertEquals(3, order.size());
        assertTrue(order.containsAll(List.of("a", "b", "c")));
        assertTrue(order.indexOf("a") < order.indexOf("b"));
    }

    @Test
    @DisplayName("构建期校验：边指向不存在的节点会立即失败")
    void validatesGraphOnBuild() {
        try {
            Workflow.builder("bad")
                    .node("a", c -> NodeOutput.ok("a"))
                    .edge("a", "missing")
                    .build();
            assertFalse(true, "应当抛出异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("missing"));
        }
    }

    @Test
    @DisplayName("示例工作流：路由 → 检索 → 生成 → 校验 → 收尾，答案带引用")
    void runsRetrievalWorkflow() {
        edu.zjnu.interview.ai.core.KnowledgeBase knowledgeBase = new edu.zjnu.interview.ai.core.KnowledgeBase() {
            @Override
            public List<Hit> search(String query, int topK) {
                return List.of(new Hit("doc-1", "费率说明",
                        "申购费率为 1.5%，持有超过 365 天可赎回部分减免至 0.15%。", 0.9, java.util.Map.of()));
            }
        };

        Workflow workflow = RetrievalWorkflow.build(new ExtractiveChatModel(), knowledgeBase, 2);
        WorkflowContext context = new WorkflowContext("trace-retrieval");
        context.put(RetrievalWorkflow.KEY_QUESTION, "申购费率是多少？持有超过 365 天有什么优惠？");

        WorkflowResult result = new WorkflowRunner(null).run(workflow, context);

        assertTrue(result.success());
        assertTrue(context.getBoolean(RetrievalWorkflow.KEY_NEED_RETRIEVAL, false));
        assertTrue(context.getBoolean(RetrievalWorkflow.KEY_VERIFIED, false));
        assertTrue(context.getString("finalAnswer").contains("[1]"));
        // 一次通过，不需要回环
        assertEquals(1, context.counter(RetrievalWorkflow.KEY_VERIFY_ATTEMPTS));
    }

    @Test
    @DisplayName("示例工作流：检索不到证据时回环重试，次数用尽后收尾")
    void loopsWhenEvidenceIsMissing() {
        Workflow workflow = RetrievalWorkflow.build(new ExtractiveChatModel(),
                (query, topK) -> List.of(), 2);
        WorkflowContext context = new WorkflowContext("trace-empty");
        context.put(RetrievalWorkflow.KEY_QUESTION, "手续费怎么算？");

        WorkflowResult result = new WorkflowRunner(null).run(workflow, context);

        assertTrue(result.success());
        assertEquals(2, context.counter(RetrievalWorkflow.KEY_VERIFY_ATTEMPTS));
        assertFalse(context.getBoolean(RetrievalWorkflow.KEY_VERIFIED, true));
    }

    @Test
    @DisplayName("节点输出：success 表示「跑完了」，不表示「业务达标」")
    void nodeOutputSemantics() {
        NodeOutput ok = NodeOutput.ok("业务上不达标但节点跑完了");
        NodeOutput fail = NodeOutput.fail("节点执行失败");

        assertTrue(ok.success());
        assertFalse(fail.success());
        assertEquals("节点执行失败", fail.errorMessage());
        assertEquals("v", ok.withData("k", "v").data().get("k"));
    }

    @Test
    @DisplayName("上下文：计数器与类型安全取值")
    void contextHelpers() {
        WorkflowContext context = new WorkflowContext("t");
        context.put("n", 3);
        context.put("flag", true);

        assertEquals(3, context.getInt("n", 0));
        assertEquals(7, context.getInt("missing", 7));
        assertTrue(context.getBoolean("flag", false));
        assertEquals(1, context.increment("count"));
        assertEquals(2, context.increment("count"));
        assertEquals(2, context.counter("count"));
    }

    @Test
    @DisplayName("工作流上下文可以预置状态，供节点读取")
    void contextCanBePrefilled() {
        WorkflowContext context = ctx();
        assertEquals("申购费率是多少？", context.getString("question"));

        Workflow workflow = Workflow.builder("prefill")
                .node("read", c -> NodeOutput.ok(c.getString("question")))
                .entry("read")
                .build();

        WorkflowResult result = new WorkflowRunner(null).run(workflow, context);
        assertEquals("申购费率是多少？", result.output());
    }
}
