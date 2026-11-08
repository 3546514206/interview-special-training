package edu.zjnu.interview.ai.cli;

import edu.zjnu.interview.ai.agent.lab.AgentLab;
import edu.zjnu.interview.ai.harness.lab.HarnessLab;
import edu.zjnu.interview.ai.rag.lab.RagLab;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Java AI 实验统一入口。
 *
 * <p>一次运行产出三份实验报告，对应 JD 的三条能力主线：
 * <ol>
 *   <li>{@code rag} —— RAG 六段流水线 + 效果评测 + 配置对比；</li>
 *   <li>{@code agent} —— Agent Loop、状态与记忆、工作流编排、多 Agent 协作；</li>
 *   <li>{@code harness} —— 可观测、成本预算、权限安全、降级、AI Coding 流水线。</li>
 * </ol>
 *
 * <p>用法：
 * <pre>
 *   mvn -q -pl java-ai-labs compile exec:java -Dexec.mainClass=edu.zjnu.interview.ai.cli.AiLabCli
 *   mvn -q -pl java-ai-labs compile exec:java -Dexec.mainClass=edu.zjnu.interview.ai.cli.AiLabCli -Dexec.args="rag"
 * </pre>
 */
public final class AiLabCli {

    private AiLabCli() {
    }

    public static void main(String[] args) {
        String target = args == null || args.length == 0 ? "all" : args[0].trim().toLowerCase();
        Map<String, Supplier<Void>> labs = new LinkedHashMap<>();
        labs.put("rag", () -> {
            RagLab.run();
            return null;
        });
        labs.put("agent", () -> {
            AgentLab.run();
            return null;
        });
        labs.put("harness", () -> {
            HarnessLab.run();
            return null;
        });

        if ("all".equals(target)) {
            for (Map.Entry<String, Supplier<Void>> entry : labs.entrySet()) {
                header(entry.getKey());
                entry.getValue().get();
                System.out.println();
            }
            header("done");
            System.out.println("全部实验执行完毕。");
            return;
        }

        Supplier<Void> lab = labs.get(target);
        if (lab == null) {
            System.err.println("未知目标: " + target + "，可选: " + String.join(" / ", labs.keySet()) + " / all");
            return;
        }
        header(target);
        lab.get();
    }

    private static void header(String title) {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  Java AI 实验 · " + title);
        System.out.println("================================================================");
    }
}
