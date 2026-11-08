package edu.zjnu.interview.ai.harness.coding;

import edu.zjnu.interview.ai.harness.Tracer;
import edu.zjnu.interview.ai.harness.InMemoryTraceSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI Coding 交付流水线回归：生成的代码必须真的能编译、断言能过、且测试能抓住变异。
 *
 * <p>这是整个模块最能体现「AI Coding + 判断和验证生成代码质量」的一组用例。
 */
class CodingDeliveryPipelineTest {

    private static final Requirement REQUIREMENT = new Requirement(
            "REQ-TEST-001",
            "账户日息计算器",
            "输入本金、年化利率、持有天数，输出单利与复利。",
            "interest",
            List.of("单利：1000 元 / 3.6% / 365 天 = 36.00",
                    "负数输入必须抛 IllegalArgumentException"),
            "InterestCalculator",
            "InterestCalculatorTest");

    @Test
    @DisplayName("生成的代码能被真实编译，断言全部通过")
    void generatedCodeCompilesAndPasses() {
        CodingDeliveryPipeline pipeline = new CodingDeliveryPipeline(new TemplateCodeGenerator());
        CodingDeliveryPipeline.DeliveryReport report = pipeline.run(REQUIREMENT);

        assertTrue(report.verification().compilerAvailable(), "运行环境应当带 javac");
        assertTrue(report.verification().compiled(),
                "生成的代码必须编译通过： " + report.verification().diagnostics());
        assertTrue(report.verification().passed(), "全部断言必须通过： " + report.verification().testResults());
        assertEquals(8, report.verification().caseCount());
        assertEquals(8, report.verification().passedCaseCount());
        assertTrue(report.passed());
        assertTrue(report.summary().contains("交付通过"));
    }

    @Test
    @DisplayName("变异检验：故意注入缺陷后，测试必须失败（证明测试有效）")
    void mutationIsCaughtByGeneratedTests() {
        CodingDeliveryPipeline pipeline = new CodingDeliveryPipeline(new TemplateCodeGenerator());
        CodingDeliveryPipeline.DeliveryReport report = pipeline.run(REQUIREMENT);

        assertTrue(report.mutation().executed(), "应当执行变异检验");
        assertTrue(report.mutation().caught(),
                "变异体必须被测试抓住，否则说明测试没有鉴别力： " + report.mutation().testResults());
        assertTrue(report.mutation().conclusion().contains("鉴别力"));
    }

    @Test
    @DisplayName("交付报告包含各阶段与量化指标")
    void reportContainsStagesAndMetrics() {
        CodingDeliveryPipeline pipeline = new CodingDeliveryPipeline(new TemplateCodeGenerator());
        CodingDeliveryPipeline.DeliveryReport report = pipeline.run(REQUIREMENT);

        List<String> stageNames = report.stages().stream().map(CodingDeliveryPipeline.Stage::name).toList();
        assertTrue(stageNames.contains("需求解析"));
        assertTrue(stageNames.contains("方案要点"));
        assertTrue(stageNames.contains("代码骨架"));
        assertTrue(stageNames.contains("测试用例"));
        assertTrue(stageNames.contains("变异检验"));

        assertEquals(2, report.generatedFiles().size());
        assertTrue(report.generatedFiles().contains("InterestCalculator.java"));
        assertTrue(report.metrics().containsKey("passed_cases"));
        assertEquals(Boolean.TRUE, report.metrics().get("mutation_caught"));
        assertTrue(report.implementationSource().contains("simpleInterest"));
        assertTrue(report.testSource().contains("runTests"));
    }

    @Test
    @DisplayName("生成代码编译不过时，兜底生成器自动修复，交付仍然通过")
    void brokenGeneratorFallsBackToTemplate() {
        CodingDeliveryPipeline pipeline = new CodingDeliveryPipeline(
                new BrokenGenerator(), new TemplateCodeGenerator());
        CodingDeliveryPipeline.DeliveryReport report = pipeline.run(REQUIREMENT);

        assertTrue(report.verification().compiled(), "兜底后必须编译通过");
        assertTrue(report.passed());
        boolean hasRepairStage = report.stages().stream()
                .anyMatch(stage -> stage.name().contains("自动修复"));
        assertTrue(hasRepairStage, "报告里必须记录发生过自动修复");
    }

    @Test
    @DisplayName("流水线会落 span，便于在 trace 里看到交付过程")
    void emitsTraceSpans() {
        InMemoryTraceSink sink = new InMemoryTraceSink();
        Tracer tracer = new Tracer(sink);
        CodingDeliveryPipeline pipeline = new CodingDeliveryPipeline(new TemplateCodeGenerator(), null, tracer);
        pipeline.run(REQUIREMENT);

        assertEquals(1, sink.spansOfName("coding.delivery").size());
        assertTrue(sink.errorSpans().isEmpty(), "交付成功时不应产生错误 span");
        assertEquals(Boolean.TRUE, sink.spansOfName("coding.delivery").get(0).attributes().get("delivered"));
    }

    @Test
    @DisplayName("变异器能改出「小改动、大语义」的缺陷")
    void sourceMutatorChangesBoundaryConditions() {
        String source = "if (principal < 0.0) { throw new IllegalArgumentException(); }";
        String mutant = SourceMutator.mutate(source);
        assertTrue(mutant != null && !mutant.equals(source));
        assertTrue(mutant.contains("<="), "应当把边界条件改成 <=");
    }

    /** 故意生成「编译不过」的代码，用来验证兜底策略。 */
    private static final class BrokenGenerator implements CodeGenerator {
        @Override
        public List<PlanPoint> plan(Requirement requirement) {
            return List.of(new PlanPoint("坏方案", "故意生成无法编译的代码"));
        }

        @Override
        public CodeArtifact generate(Requirement requirement) {
            return new CodeArtifact("BrokenClass", "public class BrokenClass { this is not java }",
                    null, "BrokenClassTest", "public class BrokenClassTest {}");
        }
    }
}
