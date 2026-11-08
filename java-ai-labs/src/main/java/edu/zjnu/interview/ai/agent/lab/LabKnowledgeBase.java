package edu.zjnu.interview.ai.agent.lab;

import edu.zjnu.interview.ai.core.KnowledgeBase;
import edu.zjnu.interview.ai.core.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 实验室用的<b>固定知识库桩</b>。
 *
 * <p><b>它不是检索实现</b>：真正的检索（切分 / 向量化 / 召回 / 重排）由 RAG 方向负责实现，
 * 运行时通过 {@link KnowledgeBase} 端口注入。本类只是一份<b>离线固定语料</b>，
 * 让 Agent 的多轮工具调用与工作流在没有向量库、没有外网的环境下也能完整演示与回归。
 *
 * <p>打分用的是词项覆盖率（{@link Text#coverage}），
 * 足以支撑演示，且完全确定性——同样的输入永远得到同样的排序。
 */
public final class LabKnowledgeBase implements KnowledgeBase {

    private final List<Hit> documents;

    public LabKnowledgeBase() {
        this.documents = List.of(
                new Hit("doc-fee", "基金费率说明",
                        "申购费率为 1.5%，持有超过 365 天可赎回部分减免至 0.15%。",
                        0.0, Map.of("source", "handbook")),
                new Hit("doc-risk", "风险控制规范",
                        "资金操作需经风控校验。",
                        0.0, Map.of("source", "policy")),
                new Hit("doc-account", "开户流程说明",
                        "开户需完成实名认证与风险测评。",
                        0.0, Map.of("source", "manual")),
                new Hit("doc-limit", "单日申购限额",
                        "单日申购上限为 10 万元。",
                        0.0, Map.of("source", "policy")));
    }

    @Override
    public List<Hit> search(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        List<Hit> scored = new ArrayList<>();
        for (Hit doc : documents) {
            double score = Text.coverage(query, doc.title()) * 0.6
                    + Text.coverage(query, doc.content()) * 0.4;
            scored.add(new Hit(doc.id(), doc.title(), doc.content(), score, doc.metadata()));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return List.copyOf(scored.subList(0, Math.min(topK, scored.size())));
    }
}
