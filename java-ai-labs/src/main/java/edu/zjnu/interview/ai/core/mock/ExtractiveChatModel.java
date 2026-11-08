package edu.zjnu.interview.ai.core.mock;

import edu.zjnu.interview.ai.core.ChatMessage;
import edu.zjnu.interview.ai.core.ChatModel;
import edu.zjnu.interview.ai.core.ChatRequest;
import edu.zjnu.interview.ai.core.ChatResponse;
import edu.zjnu.interview.ai.core.PromptBlocks;
import edu.zjnu.interview.ai.core.Text;
import edu.zjnu.interview.ai.core.TokenCounter;
import edu.zjnu.interview.ai.core.TokenUsage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 抽取式 Mock 模型：只根据检索到的证据作答，并给出引用编号。
 *
 * <p>它不是「假装很聪明的模型」，而是<b>可评测的参照系</b>：
 * 因为答案句子直接来自证据，评测里就能算出
 * 「答案是否被证据支撑（faithfulness）」「引用是否正确」。
 * 用它当基线，才能在换成真实模型后量化地说明「换模型带来了多少提升」。
 */
public final class ExtractiveChatModel implements ChatModel {

    private final String name;
    private final int maxSentences;
    private final double minCoverage;

    public ExtractiveChatModel() {
        this("extractive-mock", 3, 0.05);
    }

    public ExtractiveChatModel(String name, int maxSentences, double minCoverage) {
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
        String question = contextBlock == null ? raw : raw.replace(PromptBlocks.CONTEXT_OPEN, " ")
                .replace(PromptBlocks.CONTEXT_CLOSE, " ").trim();

        List<PromptBlocks.Evidence> evidences = PromptBlocks.parseEvidence(contextBlock);
        List<Candidate> candidates = new ArrayList<>();
        for (PromptBlocks.Evidence evidence : evidences) {
            for (String sentence : Text.splitSentences(evidence.content())) {
                double coverage = Text.coverage(question, sentence);
                if (coverage >= minCoverage && candidates.stream().noneMatch(c -> c.sentence().equals(sentence))) {
                    candidates.add(new Candidate(sentence, evidence.index(), coverage));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::coverage).reversed());

        StringBuilder answer = new StringBuilder();
        if (candidates.isEmpty()) {
            answer.append("检索到的资料不足以回答该问题。");
        } else {
            for (int i = 0; i < candidates.size() && i < maxSentences; i++) {
                Candidate candidate = candidates.get(i);
                answer.append(candidate.sentence());
                answer.append(" [").append(candidate.evidenceIndex()).append("]\n");
            }
        }
        int promptTokens = TokenCounter.estimateMessages(request.messages());
        int completionTokens = TokenCounter.estimate(answer.toString());
        return ChatResponse.text(answer.toString(), TokenUsage.of(promptTokens, completionTokens));
    }

    /** 候选句：句子 + 出处证据序号 + 与问题的重合度。 */
    private record Candidate(String sentence, int evidenceIndex, double coverage) {
    }
}
