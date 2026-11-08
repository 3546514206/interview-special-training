package edu.zjnu.interview.ai.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文本处理工具集：归一化、分词、相似度、句子切分。
 *
 * <p>这些是 RAG 里最容易被忽略、却直接决定召回质量的底层能力：
 * 全角半角、标点、大小写不统一，会让词法检索直接失效；
 * 中文没有空格，必须自己切词（这里用「字 + 相邻二字组」，
 * 对金融/技术术语的召回效果接近 bigram，是没有引入分词器依赖时的稳妥选择）。
 */
public final class Text {

    private Text() {
    }

    /** 全角转半角、去除零宽字符、压缩空白。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\u3000') {
                sb.append(' ');
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - '\uFF01' + '!'));
            } else if (c == '\u200B' || c == '\uFEFF') {
                continue;
            } else if (Character.isWhitespace(c)) {
                sb.append(c == '\n' ? '\n' : ' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().replaceAll("[ \t]{2,}", " ").trim();
    }

    /**
     * 切词：英文/数字按词切，中文按「单字 + 相邻二字组」切。
     *
     * <p>中英混排与标点是关键边界：中文串遇到拉丁字母或标点时必须<b>结算</b>而不是丢弃。
     * 早期版本在这里直接 {@code clear()}，导致「私募资产…1000万元。」只切出 {@code ["1000"]}，
     * 中文覆盖率恒为 0——这类 bug 不会报错，只会让召回质量无声地塌掉。
     *
     * @return 去重后的词项（保持出现顺序）
     */
    public static List<String> tokenize(String raw) {
        String text = normalize(raw).toLowerCase(Locale.ROOT);
        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder latin = new StringBuilder();
        List<Character> cjk = new ArrayList<>();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushLatin(tokens, latin);
                cjk.add(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(tokens, cjk);
                latin.append(c);
            } else {
                flushLatin(tokens, latin);
                flushCjk(tokens, cjk);
            }
        }
        flushLatin(tokens, latin);
        flushCjk(tokens, cjk);
        return new ArrayList<>(tokens);
    }

    /**
     * 切词并统计词频（BM25 需要 tf）。
     *
     * @return 词项 → 出现次数
     */
    public static Map<String, Integer> termFrequency(String raw) {
        String text = normalize(raw).toLowerCase(Locale.ROOT);
        Map<String, Integer> frequency = new LinkedHashMap<>();
        StringBuilder latin = new StringBuilder();
        List<Character> cjk = new ArrayList<>();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushLatin(frequency, latin);
                cjk.add(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(frequency, cjk);
                latin.append(c);
            } else {
                flushLatin(frequency, latin);
                flushCjk(frequency, cjk);
            }
        }
        flushLatin(frequency, latin);
        flushCjk(frequency, cjk);
        return frequency;
    }

    private static void flushLatin(Set<String> tokens, StringBuilder latin) {
        if (latin.length() > 0) {
            tokens.add(latin.toString());
            latin.setLength(0);
        }
    }

    private static void flushCjk(Set<String> tokens, List<Character> cjk) {
        if (cjk.isEmpty()) {
            return;
        }
        for (int i = 0; i < cjk.size(); i++) {
            tokens.add(String.valueOf(cjk.get(i)));
            if (i + 1 < cjk.size()) {
                tokens.add("" + cjk.get(i) + cjk.get(i + 1));
            }
        }
        cjk.clear();
    }

    private static void flushLatin(Map<String, Integer> frequency, StringBuilder latin) {
        if (latin.length() > 0) {
            frequency.merge(latin.toString(), 1, Integer::sum);
            latin.setLength(0);
        }
    }

    private static void flushCjk(Map<String, Integer> frequency, List<Character> cjk) {
        if (cjk.isEmpty()) {
            return;
        }
        for (int i = 0; i < cjk.size(); i++) {
            frequency.merge(String.valueOf(cjk.get(i)), 1, Integer::sum);
            if (i + 1 < cjk.size()) {
                frequency.merge("" + cjk.get(i) + cjk.get(i + 1), 1, Integer::sum);
            }
        }
        cjk.clear();
    }

    /** 是否为中日韩统一表意文字。 */
    public static boolean isCjk(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF') || (c >= '\u3400' && c <= '\u4DBF');
    }

    /** 词项集合。 */
    public static Set<String> termSet(String raw) {
        return new LinkedHashSet<>(tokenize(raw));
    }

    /** Jaccard 相似度。 */
    public static double jaccard(String a, String b) {
        Set<String> sa = termSet(a);
        Set<String> sb = termSet(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new LinkedHashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new LinkedHashSet<>(sa);
        union.addAll(sb);
        return (double) inter.size() / union.size();
    }

    /** 查询词在文本中的覆盖率（0~1），用于「查询意图被覆盖程度」类重排特征。 */
    public static double coverage(String query, String text) {
        Set<String> q = termSet(query);
        if (q.isEmpty()) {
            return 0.0;
        }
        Set<String> t = termSet(text);
        int hit = 0;
        for (String term : q) {
            if (t.contains(term)) {
                hit++;
            }
        }
        return (double) hit / q.size();
    }

    /** 余弦相似度。 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 中文/英文混排的句子切分。 */
    public static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n'
                    || (c == '.' && i + 1 < text.length() && text.charAt(i + 1) == ' ')
                    || (c == '!' && i + 1 < text.length() && text.charAt(i + 1) == ' ')
                    || (c == '?' && i + 1 < text.length() && text.charAt(i + 1) == ' ')) {
                String s = current.toString().trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }

    /** 截断到指定长度（按 token 估算）。 */
    public static String truncate(String text, int maxTokens) {
        if (text == null) {
            return "";
        }
        int limit = Math.max(1, maxTokens) * 4;
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    /** 是否包含任意一个关键词（大小写不敏感）。 */
    public static boolean containsAny(String text, List<String> keywords) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            if (kw != null && lower.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
