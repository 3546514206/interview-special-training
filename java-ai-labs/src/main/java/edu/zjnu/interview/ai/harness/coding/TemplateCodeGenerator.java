package edu.zjnu.interview.ai.harness.coding;

import java.util.List;

/**
 * 确定性模板生成器：离线、零成本、可回归的「代码生成」实现。
 *
 * <p><b>它的意义不是「假装 AI 在写代码」</b>，而是给整条流水线一个<b>可信的对照组</b>：
 * <ul>
 *   <li>流水线本身（编译 → 执行 → 变异检验 → 报告）是否正确，用它来验证；</li>
 *   <li>当真实模型生成的代码编译不过时，可以用它兜底，让交付链路不中断；</li>
 *   <li>CI 里每次都跑它，保证「验证基础设施」本身不会腐烂。</li>
 * </ul>
 *
 * <p>模板实现的是「按日计息的账户利息计算」——一个足够真实、但又足够小的业务切片：
 * 有浮点精度问题（必须四舍五入）、有边界校验（负数必须拒绝）、有清晰的可测断言。
 */
public final class TemplateCodeGenerator implements CodeGenerator {

    /** 正常实现的年度基准天数。 */
    public static final int DAYS_PER_YEAR = 365;
    /** 变异体的年度基准天数（故意写错，用于验证测试的有效性）。 */
    public static final int MUTANT_DAYS_PER_YEAR = 360;

    private static final String IMPLEMENTATION_TEMPLATE = """
            public final class __CLASS__ {

                /** 年度基准天数。 */
                public static final int DAYS_PER_YEAR = __DAYS__;

                private __CLASS__() {
                }

                /**
                 * 单利：本金 * 年化利率 * 天数 / 年度基准天数，保留两位小数。
                 */
                public static double simpleInterest(double principal, double annualRate, int days) {
                    validate(principal, annualRate, days);
                    return round2(principal * annualRate * days / (double) DAYS_PER_YEAR);
                }

                /**
                 * 复利：本金 * (1 + 年化利率)^(天数 / 年度基准天数) - 本金。
                 */
                public static double compoundInterest(double principal, double annualRate, int days) {
                    validate(principal, annualRate, days);
                    double factor = Math.pow(1.0 + annualRate, (double) days / (double) DAYS_PER_YEAR);
                    return round2(principal * factor - principal);
                }

                private static void validate(double principal, double annualRate, int days) {
                    if (principal < 0.0) {
                        throw new IllegalArgumentException("principal must not be negative");
                    }
                    if (annualRate < 0.0) {
                        throw new IllegalArgumentException("annualRate must not be negative");
                    }
                    if (days < 0) {
                        throw new IllegalArgumentException("days must not be negative");
                    }
                }

                private static double round2(double value) {
                    return Math.round(value * 100.0) / 100.0;
                }
            }
            """;

    private static final String TEST_TEMPLATE = """
            public final class __TEST_CLASS__ {

                private __TEST_CLASS__() {
                }

                /**
                 * 执行全部用例，返回每条用例的结果（PASS: xxx / FAIL: xxx）。
                 */
                public static java.util.List<String> runTests() {
                    java.util.List<String> results = new java.util.ArrayList<>();
                    results.add(equals("单利 1000 元 / 年化 3.6% / 365 天 = 36.00",
                            36.00, __CLASS__.simpleInterest(1000.0, 0.036, 365)));
                    results.add(equals("单利 0 天 = 0.00",
                            0.00, __CLASS__.simpleInterest(1000.0, 0.036, 0)));
                    results.add(equals("单利 182 天 = 17.95",
                            17.95, __CLASS__.simpleInterest(1000.0, 0.036, 182)));
                    results.add(equals("本金为 0 时利息为 0",
                            0.00, __CLASS__.simpleInterest(0.0, 0.036, 365)));
                    results.add(equals("复利 365 天 = 36.00",
                            36.00, __CLASS__.compoundInterest(1000.0, 0.036, 365)));
                    results.add(throwsIllegalArgument("负本金被拒绝",
                            () -> __CLASS__.simpleInterest(-1.0, 0.036, 10)));
                    results.add(throwsIllegalArgument("负利率被拒绝",
                            () -> __CLASS__.simpleInterest(1000.0, -0.01, 10)));
                    results.add(throwsIllegalArgument("负天数被拒绝",
                            () -> __CLASS__.simpleInterest(1000.0, 0.036, -1)));
                    return results;
                }

                private static String equals(String name, double expected, double actual) {
                    if (Math.abs(expected - actual) < 1.0E-6) {
                        return "PASS: " + name;
                    }
                    return "FAIL: " + name + " expected=" + expected + " actual=" + actual;
                }

                private static String throwsIllegalArgument(String name, Runnable action) {
                    try {
                        action.run();
                        return "FAIL: " + name + " (expected IllegalArgumentException)";
                    } catch (IllegalArgumentException expected) {
                        return "PASS: " + name;
                    }
                }

                public static void main(String[] args) {
                    for (String line : runTests()) {
                        System.out.println(line);
                    }
                }
            }
            """;

    @Override
    public List<PlanPoint> plan(Requirement requirement) {
        return List.of(
                new PlanPoint("输入校验优先",
                        "本金 / 利率 / 天数任一为负都直接抛 IllegalArgumentException——"
                                + "边界错误必须在函数入口拦住，而不是让它污染后续计算。"),
                new PlanPoint("金额统一保留两位小数",
                        "所有对外返回值经过 round2（先乘 100 四舍五入再除），"
                                + "避免调用方各自处理精度导致的对不齐。"),
                new PlanPoint("单利与复利分离",
                        "两个方法各自只做一件事：单利是线性公式，复利是幂公式；"
                                + "混在一个方法里用 if 分支会让测试难以定位失败原因。"),
                new PlanPoint("测试覆盖正常值、边界值与异常路径",
                        "正常值验证公式正确性；0 天 / 0 本金验证边界；"
                                + "负数验证异常契约——三者缺一，测试就抓不住「基准天数写错」这类缺陷。"));
    }

    @Override
    public CodeArtifact generate(Requirement requirement) {
        String className = requirement.implementationClassName();
        String testClassName = requirement.testClassName();
        return new CodeArtifact(
                className,
                implementation(className, DAYS_PER_YEAR),
                implementation(className, MUTANT_DAYS_PER_YEAR),
                testClassName,
                TEST_TEMPLATE.replace("__TEST_CLASS__", testClassName).replace("__CLASS__", className));
    }

    private String implementation(String className, int daysPerYear) {
        return IMPLEMENTATION_TEMPLATE
                .replace("__CLASS__", className)
                .replace("__DAYS__", String.valueOf(daysPerYear));
    }
}
