package edu.zjnu.interview.ai.rag.pipeline;

import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.PromptBlocks;
import edu.zjnu.interview.ai.core.TokenCounter;
import edu.zjnu.interview.ai.core.TokenUsage;
import edu.zjnu.interview.ai.rag.rerank.Similarity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地抽取式回答基线（离线、确定性、必带引用编号）。
 *
 * <p><b>为什么模块里还要有一个自己的抽取基线？</b>
 * core 的 {@code ExtractiveChatModel} 用「整段 prompt」当查询去算句子覆盖率，
 * 上下文一长就会偏向「词多」的句子；本模块需要一个<b>以用户问题为准</b>的抽取器，
 * 才能把「引用正确率」这个指标测准——它输出的每个 {@code [n]} 都必须指向真正支撑答案的证据。
 *
 * <p><b>它是基线不是模型</b>：只做「从证据里抽句子」，不做生成、不做改写。
 * 它的价值是<b>可复现</b>：同样的上下文永远给出同样的答案，
 * 于是评测指标的变化一定来自检索/重排/组装的变化，而不是模型输出的随机性。
 * 换成真实模型时，这个基线的分数就是「检索链路的天花板参考」。
 */
public final class LocalAnswerer implements ChatModel {

    private final String name;
    private final int maxSentences;
    private final double minCoverage;

    public LocalAnswerer() {
        this("local-extractive", 3, 0.05);
    }

    public LocalAnswerer(String name, int maxSentences, double minCoverage) {
        this.name = name;
        this.maxSentences = maxSentences;
        this.minCoverage = minCoverage;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        ChatMessage last = request.lastMessage();
        String raw = last == null ? "" : last.content();
        String contextBlock = PromptBlocks.extractContext(raw);
        String question = extractQuestion(raw, contextBlock);

        List<PromptBlocks.Evidence> evidences = PromptBlocks.parseEvidence(contextBlock);
        List<Sentence> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PromptBlocks.Evidence evidence : evidences) {
            for (String sentence : edu.zjnu.interview.ai.core.Text.splitSentences(evidence.content())) {
                if (sentence.isBlank() || !seen.add(sentence)) {
                    continue;
                }
                double coverage = Similarity.multiCharCoverage(question, sentence);
                if (coverage >= minCoverage) {
                    candidates.add(new Sentence(sentence.trim(), evidence.index(), coverage));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Sentence::coverage).reversed()
                .thenComparing(Sentence::evidenceIndex));

        StringBuilder answer = new StringBuilder();
        if (candidates.isEmpty()) {
            answer.append("资料中没有找到能够回答该问题的内容。");
        } else {
            for (int i = 0; i < candidates.size() && i < maxSentences; i++) {
                Sentence candidate = candidates.get(i);
                answer.append(candidate.text()).append(" [").append(candidate.evidenceIndex()).append("]\n");
            }
        }
        int promptTokens = TokenCounter.estimateMessages(request.messages());
        int completionTokens = TokenCounter.estimate(answer.toString());
        return ChatResponse.text(answer.toString(), TokenUsage.of(promptTokens, completionTokens));
    }

    /**
     * 从 prompt 里还原用户问题。
     *
     * <p>约定：user 消息形如 {@code 问题：<question>\n\n<context>...</context>}。
     * 解析不出来就退回「整段 prompt」，行为退化为「不依赖问题的抽取」。
     */
    static String extractQuestion(String raw, String contextBlock) {
        int contextStart = raw.indexOf(PromptBlocks.CONTEXT_OPEN);
        String head = contextStart >= 0 ? raw.substring(0, contextStart) : raw;
        int marker = head.indexOf("问题：");
        if (marker >= 0) {
            String question = head.substring(marker + "问题：".length()).strip();
            if (!question.isEmpty()) {
                return question;
            }
        }
        return contextBlock == null ? raw : raw.replace(PromptBlocks.CONTEXT_OPEN, " ")
                .replace(PromptBlocks.CONTEXT_CLOSE, " ").trim();
    }

    /** 候选句。 */
    private record Sentence(String text, int evidenceIndex, double coverage) {
    }
}
