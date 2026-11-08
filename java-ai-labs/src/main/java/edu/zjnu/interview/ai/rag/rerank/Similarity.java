package edu.zjnu.interview.ai.rag.rerank;

import edu.zjnu.interview.ai.core.Text;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 中文场景下的相似度口径修正。
 *
 * <p><b>问题</b>：core 的 {@link Text#coverage} 用「全部词项」算覆盖率，
 * 而中文切词包含<b>单字</b>。任何一篇稍长的中文文档，几乎必然包含查询里的常用单字
 * （「的、是、不、有」），于是覆盖率在中文长文本里普遍接近 1，特征失去区分度
 * ——这就是「单字 + 二字组」切词带来的副作用。
 *
 * <p><b>修正</b>：只用<b>多字词项</b>（长度 ≥ 2 的二字组/术语）计算覆盖率与 Jaccard。
 * 「实缴资本」「准入标准」这类二字组是真正有区分度的信号；
 * 查询里没有多字词项时（例如纯数字查询）再退回 core 的全词项口径。
 *
 * <p><b>另一个已知偏差</b>：覆盖率是「查询被覆盖」的口径，对文本长度不敏感，
 * 长文档更容易覆盖全部查询词。本模块通过「BM25 负责长度归一、覆盖率只占 0.2 权重」
 * 来控制这个偏差的影响，而不是把两个口径混成一个数。
 */
public final class Similarity {

    private Similarity() {
    }

    /**
     * 多字词项覆盖率（0~1）。
     *
     * @param query 查询
     * @param text  候选文本
     */
    public static double multiCharCoverage(String query, String text) {
        Set<String> q = multiCharTerms(query);
        if (q.isEmpty()) {
            // 查询里没有多字词项（例如纯数字），退回全词项口径
            return Text.coverage(query, text);
        }
        Set<String> t = multiCharTerms(text);
        int hit = 0;
        for (String term : q) {
            if (t.contains(term)) {
                hit++;
            }
        }
        return (double) hit / q.size();
    }

    /**
     * 多字词项 Jaccard 相似度（0~1）：用于 MMR 去冗余与上下文去重。
     */
    public static double multiCharJaccard(String a, String b) {
        Set<String> sa = multiCharTerms(a);
        Set<String> sb = multiCharTerms(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return Text.jaccard(a, b);
        }
        Set<String> inter = new LinkedHashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new LinkedHashSet<>(sa);
        union.addAll(sb);
        return (double) inter.size() / union.size();
    }

    /** 只保留长度 ≥ 2 的词项（单字在中文里过于常见，区分度低）。 */
    public static Set<String> multiCharTerms(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String term : Text.tokenize(text)) {
            if (term.length() >= 2) {
                out.add(term);
            }
        }
        return out;
    }
}
