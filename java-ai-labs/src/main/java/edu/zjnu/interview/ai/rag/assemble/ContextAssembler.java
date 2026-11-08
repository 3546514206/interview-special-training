package edu.zjnu.interview.ai.rag.assemble;

import edu.zjnu.interview.ai.core.PromptBlocks;
import edu.zjnu.interview.ai.core.TokenCounter;
import edu.zjnu.interview.ai.rag.rerank.ScoredCandidate;
import edu.zjnu.interview.ai.rag.rerank.Similarity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文组装器：把重排后的证据打包成 prompt 里的资料区。
 *
 * <p>它要同时满足四件互相拉扯的事：
 * <ol>
 *   <li><b>装得下</b>：token 预算硬约束，超了必须裁；</li>
 *   <li><b>不重复</b>：同一个意思的三段话不该挤掉另外两条有用的证据；</li>
 *   <li><b>不单调</b>：同一文档的片段不能无限霸占预算；</li>
 *   <li><b>模型看得见重点</b>：上下文不是越长越好，关键是重点排在哪。</li>
 * </ol>
 *
 * <p><b>lost-in-the-middle 排序</b>：
 * 大模型对上下文首尾的注意力高于中段（Liu et al., 2023），所以「按分数从高到低顺排」其实是浪费——
 * 排在中间的关键证据容易被忽略。本实现把<b>第 1 名放最前、第 2 名放最后</b>，
 * 其余交替填入中间（1,3,5,…,4,2），让高价值证据占据模型注意力最强的两个位置。
 *
 * <p><b>引用头格式</b>：{@code [n](docId) 标题}。
 * {@code ]} 与 {@code (} 之间带不带空格都能解析——core 的 {@code PromptBlocks.parseEvidence}
 * 曾经只认无空格格式而 javadoc 示例带空格，导致「按注释组装的上下文解析出 0 条证据」，
 * 引用正确率静默归零。core 修复后两种写法都兼容，本类保持无空格的紧凑写法。
 *
 * <p><b>裁剪策略</b>：按分数从高到低贪心装入，超预算就跳过该条<b>继续尝试后面的</b>
 * （而不是直接 break）——短片段可能仍塞得下，这样预算利用率更高。
 * 被裁掉的内容会带原因记录下来（budget / source-limit / duplicate），这是可观测性的关键：
 * 「检索到了但没装进去」和「压根没检索到」是两个完全不同的问题。
 */
public final class ContextAssembler {

    private final Config config;

    public ContextAssembler(Config config) {
        this.config = config == null ? Config.defaults() : config;
    }

    public ContextAssembler() {
        this(Config.defaults());
    }

    public Config config() {
        return config;
    }

    /**
     * 组装配置。
     *
     * @param maxTokens        上下文 token 预算
     * @param maxPerSource     单个文档最多入选的片段数
     * @param dedupe           是否去重
     * @param dedupeThreshold  去重的 Jaccard 阈值
     */
    public record Config(int maxTokens, int maxPerSource, boolean dedupe, double dedupeThreshold) {

        public Config {
            if (maxTokens <= 0) {
                throw new IllegalArgumentException("maxTokens must be positive");
            }
            if (maxPerSource <= 0) {
                throw new IllegalArgumentException("maxPerSource must be positive");
            }
        }

        /** 默认：1200 token、单来源最多 2 段、相似度 0.85 以上视为重复。 */
        public static Config defaults() {
            return new Config(1200, 2, true, 0.85);
        }
    }

    /**
     * 组装上下文。
     *
     * @param query      原查询（保留在接口里，便于未来做「查询回声」等增强）
     * @param candidates 重排后的候选（按分数降序）
     */
    public AssembledContext assemble(String query, List<ScoredCandidate> candidates) {
        List<ScoredCandidate> selected = new ArrayList<>();
        List<AssembledContext.TruncatedItem> truncated = new ArrayList<>();
        Map<String, Integer> sourceCounts = new LinkedHashMap<>();
        int used = 0;

        if (candidates != null) {
            for (ScoredCandidate candidate : candidates) {
                String docId = candidate.docId();
                int count = sourceCounts.getOrDefault(docId, 0);
                if (count >= config.maxPerSource()) {
                    truncated.add(new AssembledContext.TruncatedItem(candidate.chunkId(), docId,
                            AssembledContext.REASON_SOURCE_LIMIT, candidate.score()));
                    continue;
                }
                if (config.dedupe() && isDuplicate(candidate, selected)) {
                    truncated.add(new AssembledContext.TruncatedItem(candidate.chunkId(), docId,
                            AssembledContext.REASON_DUPLICATE, candidate.score()));
                    continue;
                }
                int tokens = estimateTokens(candidate);
                if (used + tokens > config.maxTokens()) {
                    // 跳过而不是中断：后面可能有更短的片段仍能装进预算
                    truncated.add(new AssembledContext.TruncatedItem(candidate.chunkId(), docId,
                            AssembledContext.REASON_BUDGET, candidate.score()));
                    continue;
                }
                selected.add(candidate);
                sourceCounts.merge(docId, 1, Integer::sum);
                used += tokens;
            }
        }

        List<ScoredCandidate> ordered = orderForAttention(selected);
        List<PromptBlocks.Evidence> evidences = new ArrayList<>(ordered.size());
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < ordered.size(); i++) {
            ScoredCandidate candidate = ordered.get(i);
            int number = i + 1;
            if (body.length() > 0) {
                body.append("\n\n");
            }
            body.append("[").append(number).append("](")
                    .append(candidate.docId()).append(") ")
                    .append(candidate.candidate().title())
                    .append('\n')
                    .append(candidate.candidate().content());
            evidences.add(new PromptBlocks.Evidence(number, candidate.docId(),
                    candidate.candidate().title(), candidate.candidate().content()));
        }
        String text = PromptBlocks.wrapContext(body.toString());
        return new AssembledContext(text, evidences, TokenCounter.estimate(text), truncated, sourceCounts);
    }

    /** 单条证据的 token 估算（含引用头）。 */
    private int estimateTokens(ScoredCandidate candidate) {
        String header = "[" + 1 + "](" + candidate.docId() + ") " + candidate.candidate().title();
        return TokenCounter.estimate(header) + TokenCounter.estimate(candidate.candidate().content());
    }

    /** 与已选内容是否重复。 */
    private boolean isDuplicate(ScoredCandidate candidate, List<ScoredCandidate> selected) {
        for (ScoredCandidate chosen : selected) {
            double similarity = Similarity.multiCharJaccard(
                    candidate.candidate().content(), chosen.candidate().content());
            if (similarity >= config.dedupeThreshold()) {
                return true;
            }
        }
        return false;
    }

    /**
     * lost-in-the-middle 排序：第 1 名在最前，第 2 名在最后，其余交替填入中间。
     *
     * <p>输入按分数降序，输出顺序为 {@code 1,3,5,...,4,2}。
     */
    static List<ScoredCandidate> orderForAttention(List<ScoredCandidate> sorted) {
        int size = sorted.size();
        List<ScoredCandidate> ordered = new ArrayList<>(size);
        if (size == 0) {
            return ordered;
        }
        int front = 0;
        int back = size - 1;
        for (int i = 0; i < size; i++) {
            if (i % 2 == 0) {
                ordered.add(sorted.get(front++));
            } else {
                ordered.add(sorted.get(back--));
            }
        }
        return ordered;
    }
}
