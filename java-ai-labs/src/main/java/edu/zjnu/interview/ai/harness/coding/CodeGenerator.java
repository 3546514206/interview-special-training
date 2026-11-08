package edu.zjnu.interview.ai.harness.coding;

import java.util.List;

/**
 * 代码生成器：把「需求」变成「代码 + 测试」制品。
 *
 * <p><b>为什么要抽成接口</b>：这是 Harness 里最关键的一个可替换点。
 * <ul>
 *   <li>{@link TemplateCodeGenerator}：确定性模板生成（离线、零成本、可回归）；</li>
 *   <li>{@code ModelCodeGenerator}：真实模型生成（把「生成」交给大模型）。</li>
 * </ul>
 * 两者对流水线完全等价——流水线只关心「产物能不能编译、测试能不能抓住缺陷」，
 * <b>不关心产物是谁写的</b>。这正是「Harness 把模型关进笼子」的具体含义：
 * 换掉生成器，上层的验证、度量、报告链路一行都不用改。
 */
public interface CodeGenerator {

    /**
     * 方案要点：先想清楚怎么做，再写代码。
     *
     * <p>把「方案」作为独立阶段有两个好处：一是让生成过程可解释（能讲清楚为什么这么设计）；
     * 二是让「方案不合理」能在写代码之前就被发现，而不是在测试阶段才发现。
     */
    List<PlanPoint> plan(Requirement requirement);

    /**
     * 生成代码制品（实现 + 变异体 + 测试）。
     */
    CodeArtifact generate(Requirement requirement);

    /**
     * 方案要点。
     *
     * @param title  要点标题
     * @param detail 要点说明
     */
    record PlanPoint(String title, String detail) {
    }
}
