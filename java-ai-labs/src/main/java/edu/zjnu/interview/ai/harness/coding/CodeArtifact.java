package edu.zjnu.interview.ai.harness.coding;

/**
 * 一次生成产出的代码制品。
 *
 * <p><b>为什么制品里带着「变异体」</b>：这是本模块最重要的一个设计。
 * <p>AI 生成代码最常见的自欺是「我给它写了测试，测试也过了」——
 * 但一个<b>永远会通过</b>的测试同样能「通过」。
 * 判断测试有没有价值，唯一可靠的办法是：<b>往实现里注入一个缺陷，看测试能不能抓住它</b>。
 * 这就是变异测试（mutation testing）的思想。
 * 所以制品里除了正常实现，还带一份「故意写错的实现」，
 * 流水线会验证：正常实现 → 测试全过；变异实现 → 测试必须挂。
 *
 * @param implementationClassName 实现类名
 * @param implementationSource     实现源码
 * @param mutantSource             变异体源码（故意引入缺陷）
 * @param testClassName            测试类名
 * @param testSource               测试源码
 */
public record CodeArtifact(String implementationClassName,
                           String implementationSource,
                           String mutantSource,
                           String testClassName,
                           String testSource) {
}
