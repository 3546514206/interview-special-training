package edu.zjnu.interview.ai.rag.eval;

import java.util.List;

/**
 * 黄金集里的一条用例。
 *
 * @param id              用例 id
 * @param question        用户问题
 * @param expectedDocIds  期望命中的文档 id（相关性的唯一判定依据）
 * @param expectedKeywords 期望答案中出现的关键词（用于人工核对与答案质量检查）
 * @param referenceAnswer 参考答案
 * @param type            用例类型：SINGLE_HOP / MULTI_HOP / UNANSWERABLE
 * @param difficulty      难度：EASY（词面高度重合）/ HARD（刻意低词面重叠）
 * @param answerable      是否可回答（不可回答的用例必须拒答）
 */
public record GoldenCase(String id,
                         String question,
                         List<String> expectedDocIds,
                         List<String> expectedKeywords,
                         String referenceAnswer,
                         String type,
                         String difficulty,
                         boolean answerable) {

    public static final String TYPE_SINGLE_HOP = "SINGLE_HOP";
    public static final String TYPE_MULTI_HOP = "MULTI_HOP";
    public static final String TYPE_UNANSWERABLE = "UNANSWERABLE";

    /** 难度：易例（问题与语料词面高度重合）。 */
    public static final String DIFFICULTY_EASY = "EASY";
    /** 难度：难例（刻意改写/换词/跨文档组合，低词面重叠）。 */
    public static final String DIFFICULTY_HARD = "HARD";

    public GoldenCase {
        expectedDocIds = expectedDocIds == null ? List.of() : List.copyOf(expectedDocIds);
        expectedKeywords = expectedKeywords == null ? List.of() : List.copyOf(expectedKeywords);
        type = type == null || type.isBlank() ? TYPE_SINGLE_HOP : type;
        difficulty = difficulty == null || difficulty.isBlank() ? DIFFICULTY_EASY : difficulty;
    }

    /** 是否需要跨文档作答。 */
    public boolean multiHop() {
        return TYPE_MULTI_HOP.equals(type) || expectedDocIds.size() > 1;
    }

    /** 是否为难例。 */
    public boolean hard() {
        return DIFFICULTY_HARD.equals(difficulty);
    }
}
