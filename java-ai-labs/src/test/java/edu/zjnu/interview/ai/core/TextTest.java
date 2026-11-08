package edu.zjnu.interview.ai.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Text} 的行为约定测试。
 *
 * <p>其中 {@code 中文串遇到拉丁字母或标点必须结算而不是丢弃} 这条，
 * 是踩过一次坑之后加上的回归锁：早期版本用 {@code cjk.clear()}，
 * 导致「私募资产…1000万元。」只切出 {@code ["1000"]}，
 * 中文覆盖率恒为 0，召回质量无声塌掉却不报错。
 */
class TextTest {

    @Test
    @DisplayName("中英混排：中文串不能因为后面出现拉丁字母而丢失")
    void chineseSurvivesLatinBoundary() {
        List<String> tokens = Text.tokenize("私募资产 1000 万元");

        assertTrue(tokens.contains("私募"), "应保留中文词项，实际: " + tokens);
        assertTrue(tokens.contains("资产"), "应保留中文词项，实际: " + tokens);
        assertTrue(tokens.contains("私募资产".substring(0, 2)), "应保留二字组，实际: " + tokens);
        assertTrue(tokens.contains("1000"), "应保留数字词项，实际: " + tokens);
        assertTrue(tokens.contains("万元"), "应保留中文词项，实际: " + tokens);
    }

    @Test
    @DisplayName("标点是边界：结算前段中文，不与后段拼二字组")
    void punctuationIsHardBoundary() {
        List<String> tokens = Text.tokenize("私募。资产");

        assertTrue(tokens.contains("私募"), "实际: " + tokens);
        assertTrue(tokens.contains("资产"), "实际: " + tokens);
        assertFalse(tokens.contains("募资"), "标点两侧不应拼接二字组，实际: " + tokens);
    }

    @Test
    @DisplayName("中文字符会同时产出单字与相邻二字组")
    void chineseProducesUnigramAndBigram() {
        List<String> tokens = Text.tokenize("私募资产接入");

        assertTrue(tokens.contains("私"));
        assertTrue(tokens.contains("私募"));
        assertTrue(tokens.contains("募资"));
        assertTrue(tokens.contains("资产"));
        assertTrue(tokens.contains("接入"));
    }

    @Test
    @DisplayName("词频统计保留重复次数，供 BM25 使用")
    void termFrequencyCountsRepetitions() {
        Map<String, Integer> tf = Text.termFrequency("私募资产 私募 私募");

        assertEquals(3, tf.get("私募"));
        assertEquals(1, tf.get("资产"));
    }

    @Test
    @DisplayName("中文覆盖率不再恒为 0")
    void coverageWorksForChinese() {
        double coverage = Text.coverage("私募准入", "私募资产接入的准入标准要求 1000 万元起投");

        assertTrue(coverage > 0.5, "中文覆盖率应显著大于 0，实际: " + coverage);
    }

    @Test
    @DisplayName("全角字符归一化为半角")
    void normalizeConvertsFullWidth() {
        assertEquals("私募ABC", Text.normalize("私募ＡＢＣ"));
        assertEquals("a b", Text.normalize("a  b"));
    }

    @Test
    @DisplayName("句子切分兼容中英文标点")
    void splitSentencesHandlesBothScripts() {
        List<String> sentences = Text.splitSentences("第一句。第二句！Third one? End.");

        assertEquals(4, sentences.size());
        assertEquals("第一句。", sentences.get(0));
    }

    @Test
    @DisplayName("余弦相似度：同向量为 1，正交为 0")
    void cosineBehaves() {
        float[] a = {1f, 0f, 0f};
        float[] b = {1f, 0f, 0f};
        float[] c = {0f, 1f, 0f};

        assertEquals(1.0, Text.cosine(a, b), 1e-9);
        assertEquals(0.0, Text.cosine(a, c), 1e-9);
    }
}
