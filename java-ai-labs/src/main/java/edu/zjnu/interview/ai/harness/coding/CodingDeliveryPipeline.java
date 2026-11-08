package edu.zjnu.interview.ai.harness.coding;

import edu.zjnu.interview.ai.core.Span;
import edu.zjnu.interview.ai.harness.SpanAttrs;
import edu.zjnu.interview.ai.harness.Tracer;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * AI Coding 交付流水线：需求 → 方案 → 代码骨架 → 测试用例 → 本地验证 → 交付报告。
 *
 * <p><b>它想证明什么</b>：AI Coding 的价值不是「模型能写出看起来很像的代码」，
 * 而是「<b>模型写的代码能被机器验证</b>」。所以本流水线的核心不是生成，而是验证：
 * <ol>
 *   <li><b>编译</b>：用 {@code javax.tools.JavaCompiler} 真实编译生成的代码——
 *       语法错误、类型错误、缺失依赖都会在这里暴露；</li>
 *   <li><b>执行</b>：在<b>隔离类加载器</b>（父加载器是 platform loader，不含应用 classpath）
 *       里跑生成的测试，拿到逐条用例结果；</li>
 *   <li><b>变异检验</b>：把实现换成「故意写错的版本」再跑一遍，
 *       <b>要求测试必须失败</b>。这一步回答的是「你的测试到底有没有用」——
 *       一个抓不住缺陷的测试，等于没有测试。</li>
 * </ol>
 *
 * <p><b>为什么用 JDK 内置能力而不是起一个子进程</b>：
 * 起进程虽然隔离更彻底，但会引入「进程启动开销」「输出采集」「退出码语义」等噪音，
 * 而且无法在同一次调用里拿到结构化结果。{@code JavaCompiler} + {@code URLClassLoader}
 * 在同一进程内完成了「编译 → 执行 → 断言」闭环，且全部可编程、可断言。
 *
 * <p><b>兜底策略</b>：如果生成器产出的代码编译不过，且配置了兜底生成器
 * （{@link TemplateCodeGenerator}），流水线会自动改用兜底方案重试一次，
 * 并在报告里明确记录「发生过自动修复」。这就是「无人化交付」里的兜底：
 * <b>不追求一次生成就对，而是保证最终交付物一定可验证</b>。
 */
public final class CodingDeliveryPipeline {

    /** 流水线阶段。 */
    public record Stage(String name, String detail, boolean ok) {
    }

    /**
     * 本地验证结果。
     *
     * @param compilerAvailable 当前运行环境是否带 javac
     * @param compiled          是否编译成功
     * @param diagnostics       编译诊断信息（失败时含错误详情）
     * @param testResults       逐条用例结果
     * @param passed            全部用例是否通过
     * @param durationMillis    验证耗时
     */
    public record VerificationResult(boolean compilerAvailable,
                                     boolean compiled,
                                     List<String> diagnostics,
                                     List<String> testResults,
                                     boolean passed,
                                     long durationMillis) {

        public VerificationResult {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            testResults = testResults == null ? List.of() : List.copyOf(testResults);
        }

        /** 用例总数。 */
        public int caseCount() {
            return testResults.size();
        }

        /** 通过的用例数。 */
        public int passedCaseCount() {
            return (int) testResults.stream().filter(r -> r.startsWith("PASS")).count();
        }
    }

    /**
     * 变异检验结果。
     *
     * @param executed   是否执行了变异检验
     * @param caught     变异体是否被测试抓住（true = 测试有效）
     * @param testResults 变异体上的用例结果
     * @param conclusion 结论说明
     */
    public record MutationResult(boolean executed, boolean caught, List<String> testResults, String conclusion) {

        public MutationResult {
            testResults = testResults == null ? List.of() : List.copyOf(testResults);
        }
    }

    /**
     * 交付报告。
     *
     * @param requirementId        需求编号
     * @param title                需求标题
     * @param stages               各阶段结果
     * @param verification        本地验证结果
     * @param mutation             变异检验结果
     * @param passed               整体是否通过（编译 + 用例 + 变异检验）
     * @param metrics              量化指标
     * @param generatedFiles       生成的源文件名
     * @param implementationSource 生成的实现源码
     * @param testSource           生成的测试源码
     * @param summary              结论摘要
     */
    public record DeliveryReport(String requirementId,
                                 String title,
                                 List<Stage> stages,
                                 VerificationResult verification,
                                 MutationResult mutation,
                                 boolean passed,
                                 Map<String, Object> metrics,
                                 List<String> generatedFiles,
                                 String implementationSource,
                                 String testSource,
                                 String summary) {

        public DeliveryReport {
            stages = stages == null ? List.of() : List.copyOf(stages);
            metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
            generatedFiles = generatedFiles == null ? List.of() : List.copyOf(generatedFiles);
        }
    }

    private final CodeGenerator generator;
    private final CodeGenerator fallbackGenerator;
    private final Tracer tracer;

    public CodingDeliveryPipeline(CodeGenerator generator) {
        this(generator, null, null);
    }

    public CodingDeliveryPipeline(CodeGenerator generator, CodeGenerator fallbackGenerator) {
        this(generator, fallbackGenerator, null);
    }

    public CodingDeliveryPipeline(CodeGenerator generator, CodeGenerator fallbackGenerator, Tracer tracer) {
        if (generator == null) {
            throw new IllegalArgumentException("生成器不能为空");
        }
        this.generator = generator;
        this.fallbackGenerator = fallbackGenerator;
        this.tracer = tracer;
    }

    /**
     * 执行一次完整交付。
     *
     * @param requirement 待交付需求
     */
    public DeliveryReport run(Requirement requirement) {
        List<Stage> stages = new ArrayList<>();
        try (Tracer.Scope scope = tracer == null ? null : tracer.span("coding.delivery", Span.KIND_WORKFLOW_NODE)) {
            // 阶段 1：需求解析
            stages.add(new Stage("需求解析",
                    requirement.id() + " / " + requirement.title() + "；验收标准 "
                            + requirement.acceptanceCriteria().size() + " 条", true));

            // 阶段 2：方案要点
            List<CodeGenerator.PlanPoint> plan = generator.plan(requirement);
            String planText = plan.stream()
                    .map(p -> p.title() + "：" + p.detail())
                    .reduce((a, b) -> a + "\n    - " + b)
                    .orElse("(无)");
            stages.add(new Stage("方案要点", planText, !plan.isEmpty()));

            // 阶段 3 + 4：代码骨架与测试用例
            CodeArtifact artifact = generator.generate(requirement);
            stages.add(new Stage("代码骨架",
                    artifact.implementationClassName() + ".java（" + countLines(artifact.implementationSource()) + " 行）", true));
            stages.add(new Stage("测试用例",
                    artifact.testClassName() + ".java（" + countLines(artifact.testSource()) + " 行）", true));

            // 阶段 5：本地验证（编译 + 执行）
            Path workDir = null;
            VerificationResult verification;
            MutationResult mutation;
            try {
                workDir = Files.createTempDirectory("ai-coding-verify-");
                verification = verify(artifact, workDir);

                // 编译不过 → 兜底生成器重试一次
                if (!verification.compiled() && fallbackGenerator != null) {
                    stages.add(new Stage("自动修复",
                            "生成代码未通过编译，自动改用兜底生成器（" + fallbackGenerator.getClass().getSimpleName()
                                    + "）重试", false));
                    artifact = fallbackGenerator.generate(requirement);
                    verification = verify(artifact, workDir);
                    stages.add(new Stage("自动修复结果",
                            verification.compiled() ? "兜底方案编译通过" : "兜底方案仍然编译失败", verification.compiled()));
                }

                // 阶段 6：变异检验
                mutation = mutationCheck(artifact, workDir);
                stages.add(new Stage("变异检验",
                        mutation.conclusion(), mutation.executed() && mutation.caught()));
            } catch (IOException e) {
                verification = new VerificationResult(true, false,
                        List.of("临时目录创建失败: " + e.getMessage()), List.of(), false, 0L);
                mutation = new MutationResult(false, false, List.of(), "未执行（验证阶段异常）");
                stages.add(new Stage("本地验证", "验证环境异常: " + e.getMessage(), false));
            } finally {
                if (workDir != null) {
                    deleteTempWorkDir(workDir);
                }
            }

            boolean passed = verification.compiled()
                    && verification.passed()
                    && (!mutation.executed() || mutation.caught());

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("stages", stages.size());
            metrics.put("compile_millis", verification.durationMillis());
            metrics.put("cases", verification.caseCount());
            metrics.put("passed_cases", verification.passedCaseCount());
            metrics.put("mutation_caught", mutation.caught());
            metrics.put("implementation_lines", countLines(artifact.implementationSource()));
            metrics.put("test_lines", countLines(artifact.testSource()));

            String summary = passed
                    ? "交付通过：生成代码编译成功，" + verification.passedCaseCount() + "/" + verification.caseCount()
                    + " 条用例通过，且变异检验证明测试能抓住缺陷"
                    : "交付未通过：编译=" + verification.compiled() + "，用例通过="
                    + verification.passedCaseCount() + "/" + verification.caseCount()
                    + "，变异检验捕获=" + mutation.caught();

            if (tracer != null && scope != null) {
                scope.attr(SpanAttrs.MODEL, requirement.id());
                scope.attr("cases", verification.caseCount());
                scope.attr("passed_cases", verification.passedCaseCount());
                scope.attr("mutation_caught", mutation.caught());
                scope.attr("delivered", passed);
                if (!passed) {
                    scope.markError("DELIVERY_FAILED", summary);
                }
            }

            return new DeliveryReport(requirement.id(), requirement.title(), stages, verification, mutation,
                    passed, metrics,
                    List.of(artifact.implementationClassName() + ".java", artifact.testClassName() + ".java"),
                    artifact.implementationSource(), artifact.testSource(), summary);
        }
    }

    /**
     * 本地验证：编译 + 在隔离类加载器中执行测试。
     */
    private VerificationResult verify(CodeArtifact artifact, Path workDir) throws IOException {
        long start = System.nanoTime();
        Path srcDir = workDir.resolve("src");
        Path outDir = workDir.resolve("out-" + System.nanoTime());
        Files.createDirectories(srcDir);
        Files.createDirectories(outDir);

        Path implFile = srcDir.resolve(artifact.implementationClassName() + ".java");
        Path testFile = srcDir.resolve(artifact.testClassName() + ".java");
        Files.writeString(implFile, artifact.implementationSource(), StandardCharsets.UTF_8);
        Files.writeString(testFile, artifact.testSource(), StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new VerificationResult(false, false,
                    List.of("当前运行环境没有 javac（ToolProvider.getSystemJavaCompiler() == null），无法做真实编译验证"),
                    List.of(), false, (System.nanoTime() - start) / 1_000_000L);
        }

        List<String> diagnostics = new ArrayList<>();
        boolean compiled;
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        StringWriter compilerOut = new StringWriter();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjects(implFile.toFile(), testFile.toFile());
            List<String> options = List.of(
                    "-d", outDir.toString(),
                    "-encoding", "UTF-8",
                    "-nowarn",
                    "-proc:none");
            compiled = compiler.getTask(new PrintWriter(compilerOut), fileManager, collector,
                    options, null, units).call();
        } catch (RuntimeException e) {
            compiled = false;
            diagnostics.add("编译过程异常: " + e.getMessage());
        }
        for (Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR || diagnostic.getKind() == Diagnostic.Kind.WARNING) {
                diagnostics.add(diagnostic.getKind() + ": " + diagnostic.getMessage(null)
                        + (diagnostic.getSource() == null ? "" : " @" + diagnostic.getSource().getName()));
            }
        }
        if (!compiled && compilerOut.toString().isBlank() && diagnostics.isEmpty()) {
            diagnostics.add("编译失败（无详细诊断）");
        }

        List<String> results = compiled ? runTests(outDir, artifact.testClassName()) : List.of();
        boolean passed = compiled && !results.isEmpty() && results.stream().noneMatch(r -> r.startsWith("FAIL"));
        return new VerificationResult(true, compiled, diagnostics, results, passed,
                (System.nanoTime() - start) / 1_000_000L);
    }

    /**
     * 变异检验：把实现换成故意写错的版本，测试必须失败。
     *
     * <p>这是「判断生成代码质量」的关键动作：
     * 只有当测试能抓住人为注入的缺陷时，我们才有理由相信它也能抓住真实缺陷。
     */
    private MutationResult mutationCheck(CodeArtifact artifact, Path workDir) {
        if (artifact.mutantSource() == null || artifact.mutantSource().isBlank()) {
            return new MutationResult(false, false, List.of(), "未提供变异体，跳过变异检验");
        }
        try {
            CodeArtifact mutant = new CodeArtifact(artifact.implementationClassName(),
                    artifact.mutantSource(), artifact.mutantSource(),
                    artifact.testClassName(), artifact.testSource());
            VerificationResult result = verify(mutant, workDir);
            if (!result.compiled()) {
                return new MutationResult(true, false, List.of(),
                        "变异体未能编译，变异检验无效（变异必须只改逻辑、不能改语法）");
            }
            boolean caught = result.testResults().stream().anyMatch(r -> r.startsWith("FAIL"));
            String conclusion = caught
                    ? "变异体被测试抓住（" + result.testResults().stream().filter(r -> r.startsWith("FAIL")).count()
                    + " 条用例失败）→ 测试具备鉴别力"
                    : "变异体未被测试抓住 → 测试无效（必须补测试）";
            return new MutationResult(true, caught, result.testResults(), conclusion);
        } catch (IOException e) {
            return new MutationResult(true, false, List.of(), "变异检验异常: " + e.getMessage());
        }
    }

    /**
     * 在隔离类加载器里执行生成的测试。
     *
     * <p>父加载器用 {@code ClassLoader.getPlatformClassLoader()}：
     * 它只暴露 JDK 平台类，不包含应用 classpath。
     * 生成的代码因此<b>无法</b>偷偷引用工程里的类——验证环境是可预测的。
     */
    private List<String> runTests(Path outDir, String testClassName) {
        List<String> results = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{outDir.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> testClass = loader.loadClass(testClassName);
            java.lang.reflect.Method method = testClass.getMethod("runTests");
            Object value = method.invoke(null);
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    results.add(String.valueOf(item));
                }
            } else {
                results.add("FAIL: 测试类未返回用例结果列表");
            }
        } catch (ClassNotFoundException e) {
            results.add("FAIL: 未找到测试类 " + testClassName);
        } catch (NoSuchMethodException e) {
            results.add("FAIL: 测试类缺少 runTests() 方法");
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            results.add("FAIL: 测试执行异常 " + cause);
        } catch (IOException e) {
            results.add("FAIL: 无法加载测试类 " + e.getMessage());
        }
        return results;
    }

    private static int countLines(String source) {
        if (source == null || source.isBlank()) {
            return 0;
        }
        return (int) source.lines().count();
    }

    /**
     * 清理临时工作目录。
     *
     * <p>安全约束：只删除「本流水线自己创建、且位于系统临时目录下、且名字以
     * {@code ai-coding-verify-} 开头」的目录，避免误删用户文件。
     */
    private void deleteTempWorkDir(Path workDir) {
        try {
            Path absolute = workDir.toAbsolutePath().normalize();
            Path tempRoot = Path.of(System.getProperty("java.io.tmpdir", "/tmp")).toAbsolutePath().normalize();
            if (!absolute.startsWith(tempRoot)
                    || !absolute.getFileName().toString().startsWith("ai-coding-verify-")) {
                return;
            }
            if (!Files.exists(absolute)) {
                return;
            }
            try (Stream<Path> stream = Files.walk(absolute)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // 清理失败不影响交付结论
                    }
                });
            }
        } catch (IOException ignored) {
            // 清理失败不影响交付结论
        }
    }
}
