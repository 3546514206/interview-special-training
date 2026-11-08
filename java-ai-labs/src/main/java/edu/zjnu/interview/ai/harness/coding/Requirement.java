package edu.zjnu.interview.ai.harness.coding;

import java.util.List;

/**
 * 一条待交付的需求。
 *
 * <p><b>为什么需求要结构化</b>：AI Coding 的第一步不是「写代码」，而是「把需求变成可验证的契约」。
 * 需求描述得越模糊，生成代码的自由度就越大，验证成本就越高。
 * 这里强制需求带上<b>验收标准</b>——它既是生成测试用例的输入，也是最终判定交付是否合格的依据。
 *
 * @param id                 需求编号
 * @param title              标题
 * @param description        详细描述
 * @param kind               需求类型（决定用哪个代码模板 / 生成策略）
 * @param acceptanceCriteria 验收标准（逐条可验证）
 * @param implementationClassName 生成的实现类名
 * @param testClassName      生成的测试类名
 */
public record Requirement(String id,
                          String title,
                          String description,
                          String kind,
                          List<String> acceptanceCriteria,
                          String implementationClassName,
                          String testClassName) {

    public Requirement {
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        implementationClassName = implementationClassName == null || implementationClassName.isBlank()
                ? "GeneratedSolution" : implementationClassName;
        testClassName = testClassName == null || testClassName.isBlank()
                ? implementationClassName + "Test" : testClassName;
    }

    /** 构造一条需求（自动补默认类名）。 */
    public static Requirement of(String id, String title, String description, List<String> acceptanceCriteria) {
        return new Requirement(id, title, description, "interest", acceptanceCriteria, null, null);
    }
}
