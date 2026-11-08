package edu.zjnu.interview.ai.agent.workflow;

import edu.zjnu.interview.ai.agent.SpanFactory;
import edu.zjnu.interview.ai.core.Span;
import edu.zjnu.interview.ai.core.TraceSink;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工作流执行器：拓扑推进 + 条件路由 + 节点级重试 + 整体 trace。
 *
 * <p><b>执行模型</b>：从入口节点开始，沿出边推进。每执行完一个节点，
 * 按声明顺序求值它的出边条件，命中第一条就走（条件边优先，无条件边作为兜底）。
 * 没有出边（或没有命中的出边）→ 工作流正常结束。
 *
 * <p><b>为什么必须有「最大节点执行次数」熔断</b>：
 * 条件回环（校验不过回到检索）本身没有次数限制时，一个永远校验不过的任务
 * 会让工作流无限执行，把预算烧光。所以除了业务层自己计数（示例工作流里的
 * {@code verifyAttempts}），执行器层面还要有一道硬熔断兜底——
 * <b>业务熔断 + 框架熔断是两层保险，缺一不可</b>。
 *
 * <p><b>超时怎么实现</b>：把节点处理函数丢到守护线程里执行，主线程用
 * {@code Future.get(timeout)} 等待。超时后 {@code cancel(true)} 中断。
 * 守护线程（daemon）保证即使有节点卡死，JVM 也能正常退出。
 */
public final class WorkflowRunner {

    /** 默认最大节点执行次数（回环熔断）。 */
    public static final int DEFAULT_MAX_NODE_EXECUTIONS = 100;

    private final TraceSink traceSink;
    private final int maxNodeExecutions;

    public WorkflowRunner(TraceSink traceSink) {
        this(traceSink, DEFAULT_MAX_NODE_EXECUTIONS);
    }

    public WorkflowRunner(TraceSink traceSink, int maxNodeExecutions) {
        this.traceSink = traceSink == null ? TraceSink.noop() : traceSink;
        this.maxNodeExecutions = Math.max(1, maxNodeExecutions);
    }

    /**
     * 执行工作流。
     *
     * @param workflow 工作流定义
     * @param ctx      上下文（调用方可预置状态）
     */
    public WorkflowResult run(Workflow workflow, WorkflowContext ctx) {
        if (workflow == null || ctx == null) {
            throw new IllegalArgumentException("workflow 与 ctx 不能为空");
        }
        SpanFactory spans = new SpanFactory(ctx.traceId());
        String rootSpanId = spans.reserveSpanId();
        long runStart = spans.monotonicMillis();

        List<WorkflowResult.NodeExecution> executions = new ArrayList<>();
        ExecutorService executor = newExecutor();
        String current = workflow.entry();
        Object output = null;
        String failure = null;
        WorkflowResult.Status status = WorkflowResult.Status.SUCCESS;
        int executed = 0;

        try {
            while (current != null) {
                if (++executed > maxNodeExecutions) {
                    status = WorkflowResult.Status.ABORTED;
                    failure = "超过最大节点执行次数 " + maxNodeExecutions + "（疑似条件回环未收敛）";
                    break;
                }
                WorkflowNode node = workflow.node(current).orElse(null);
                if (node == null) {
                    status = WorkflowResult.Status.FAILED;
                    failure = "节点不存在: " + current;
                    break;
                }
                NodeRun run = executeNode(node, ctx, spans, rootSpanId, executor);
                executions.add(run.execution());
                if (!run.output().success()) {
                    status = WorkflowResult.Status.FAILED;
                    failure = "节点 " + node.name() + " 失败: " + run.execution().error();
                    break;
                }
                output = run.output().value();
                ctx.put("node." + node.name(), output);
                for (Map.Entry<String, Object> entry : run.output().data().entrySet()) {
                    ctx.put("node." + node.name() + "." + entry.getKey(), entry.getValue());
                }

                // ---- 条件路由 ----
                String next = null;
                for (Workflow.Edge edge : workflow.outgoing(current)) {
                    if (!edge.conditional() || edge.condition().test(ctx, run.output())) {
                        next = edge.to();
                        break;
                    }
                }
                current = next;
            }
        } finally {
            executor.shutdownNow();
        }

        long runEnd = spans.monotonicMillis();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("workflow", workflow.name());
        attrs.put("nodes_executed", executions.size());
        attrs.put("status", status.name());
        traceSink.emit(spans.emit(rootSpanId, "workflow.run", Span.KIND_WORKFLOW_NODE, null, attrs,
                runStart, runEnd - runStart, status != WorkflowResult.Status.SUCCESS));

        return new WorkflowResult(workflow.name(), status, executions, output, failure,
                spans.traceId(), runEnd - runStart);
    }

    /** 执行一个节点（含重试与超时）。 */
    private NodeRun executeNode(WorkflowNode node, WorkflowContext ctx, SpanFactory spans,
                                String rootSpanId, ExecutorService executor) {
        String spanId = spans.reserveSpanId();
        long start = spans.monotonicMillis();
        int maxAttempts = node.retries() + 1;
        String lastError = null;
        NodeOutput output = null;
        int attempts = 0;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attempts = attempt;
            try {
                output = invoke(node, ctx, executor);
                if (output == null) {
                    output = NodeOutput.fail("节点返回了 null");
                }
                if (output.success()) {
                    lastError = null;
                    break;
                }
                lastError = output.errorMessage();
            } catch (TimeoutException e) {
                lastError = "节点超时（上限 " + node.timeout().toMillis() + " ms）";
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                lastError = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = "节点执行被中断";
                break;
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }

        long end = spans.monotonicMillis();
        boolean success = output != null && output.success();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("node", node.name());
        attrs.put("attempts", attempts);
        attrs.put("success", success);
        if (!success) {
            attrs.put("error", lastError);
        }
        traceSink.emit(spans.emit(spanId, "workflow.node." + node.name(), Span.KIND_WORKFLOW_NODE,
                rootSpanId, attrs, start, end - start, !success));

        WorkflowResult.NodeExecution execution = new WorkflowResult.NodeExecution(node.name(), attempts,
                success, success ? output.value() : null, success ? null : lastError, end - start);
        return new NodeRun(output == null ? NodeOutput.fail(lastError) : output, execution);
    }

    /** 调用处理函数（带超时）。 */
    private NodeOutput invoke(WorkflowNode node, WorkflowContext ctx, ExecutorService executor)
            throws Exception {
        Duration timeout = node.timeout();
        if (timeout == null) {
            return node.handler().handle(ctx);
        }
        Future<NodeOutput> future = executor.submit(() -> node.handler().handle(ctx));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    /** 守护线程池：节点卡死时不会拖住 JVM 退出。 */
    private ExecutorService newExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "workflow-node");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    /** 节点执行结果（输出 + 明细）。 */
    private record NodeRun(NodeOutput output, WorkflowResult.NodeExecution execution) {
    }
}
