package edu.zjnu.interview.ai.rag.store;

import edu.zjnu.interview.ai.core.Text;
import edu.zjnu.interview.ai.rag.Chunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标准 BM25 倒排索引（k1=1.5, b=0.75），支持 payload 等值过滤。
 *
 * <p><b>为什么 RAG 还需要词法检索？</b>
 * 向量检索擅长「意思相近」，但对<b>专有名词、数字、编号</b>这类必须精确命中的串很弱：
 * 「实缴资本 1000 万元」「R3 产品 6% 阈值」这类事实，语义向量经常给出「相关但数值不对」的邻居。
 * 词法检索能保证「问什么就能检到什么字面」，两者互补，这是 hybrid 检索的立身之本。
 *
 * <p><b>分词口径</b>：直接使用 core 的 {@link Text#termFrequency(String)}（保留词频，BM25 需要 tf）
 * 与 {@link Text#tokenize(String)}（词表）。core 的中文切词是「单字 + 相邻二字组」，
 * 没有引入分词器依赖，对金融/术语类中文语料足够用；标点是硬边界（二字组不跨标点）。
 *
 * <p><b>过滤口径</b>：过滤先于打分生效——df 与 avgdl 都在「命中过滤条件的子集」上统计。
 * 这与「先算分再过滤」不同：后者会让过滤后的 IDF 仍然基于全量语料，
 * 出现「过滤掉了最常出现的词，剩下的词 IDF 虚高」的不一致。本实现选择前者，语义更干净。
 */
public final class Bm25Index {

    /** 词频饱和参数：控制某个词重复出现时贡献的增长速度。 */
    public static final double K1 = 1.5;

    /** 文档长度归一化参数：0 = 不考虑长度，1 = 完全按长度归一。 */
    public static final double B = 0.75;

    private final List<Entry> entries = new ArrayList<>();

    /** 索引中的一条文档（片段）。 */
    private record Entry(String businessId,
                         String docId,
                         String title,
                         String content,
                         Map<String, Object> payload,
                         Map<String, Integer> termFrequency,
                         int length) {
    }

    /** 一条检索结果。 */
    public record Scored(String businessId, String docId, double score) {
    }

    /** 加入一个片段。 */
    public void add(Chunk chunk) {
        add(chunk.id(), chunk.docId(), chunk.title(), chunk.content(), chunk.metadata());
    }

    /** 加入一条文档。 */
    public void add(String businessId, String docId, String title, String content, Map<String, Object> payload) {
        Map<String, Integer> tf = Text.termFrequency(content);
        int length = tf.values().stream().mapToInt(Integer::intValue).sum();
        entries.add(new Entry(businessId, docId, title, content,
                payload == null ? Map.of() : payload, tf, length));
    }

    /** 索引中的文档数。 */
    public int size() {
        return entries.size();
    }

    /** 清空索引。 */
    public void clear() {
        entries.clear();
    }

    /**
     * 检索。
     *
     * @param query  查询
     * @param topK   返回条数
     * @param filter payload 等值过滤（null 表示不过滤）
     */
    public List<Scored> search(String query, int topK, Map<String, Object> filter) {
        if (topK <= 0) {
            return List.of();
        }
        List<Entry> corpus = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            if (PayloadFilters.matches(entry.payload(), filter)) {
                corpus.add(entry);
            }
        }
        if (corpus.isEmpty()) {
            return List.of();
        }
        List<String> queryTerms = Text.tokenize(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        int n = corpus.size();
        int totalLength = 0;
        for (Entry entry : corpus) {
            totalLength += entry.length();
        }
        double avgdl = n == 0 ? 0.0 : (double) totalLength / n;

        // df 统计（只在过滤后的子集上）
        Map<String, Integer> documentFrequency = new LinkedHashMap<>();
        for (Entry entry : corpus) {
            for (String term : entry.termFrequency().keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        List<Scored> scored = new ArrayList<>();
        for (Entry entry : corpus) {
            double score = 0.0;
            for (String term : queryTerms) {
                Integer tf = entry.termFrequency().get(term);
                if (tf == null) {
                    continue;
                }
                int df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
                double norm = tf * (K1 + 1.0)
                        / (tf + K1 * (1.0 - B + B * (avgdl <= 0 ? 1.0 : entry.length() / avgdl)));
                score += idf * norm;
            }
            if (score > 0.0) {
                scored.add(new Scored(entry.businessId(), entry.docId(), score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(Scored::businessId));
        return scored.size() <= topK ? scored : new ArrayList<>(scored.subList(0, topK));
    }

    /** 便捷重载（不过滤）。 */
    public List<Scored> search(String query, int topK) {
        return search(query, topK, null);
    }
}
