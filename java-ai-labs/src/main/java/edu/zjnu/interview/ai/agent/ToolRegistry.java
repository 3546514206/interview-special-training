package edu.zjnu.interview.ai.agent;

import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.core.Tool;
import edu.zjnu.interview.ai.core.ToolCall;
import edu.zjnu.interview.ai.core.ToolResult;
import edu.zjnu.interview.ai.core.ToolSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具注册表：Agent 的「能力清单」。
 *
 * <p>它承担三件事，三件事都必须放在同一个地方，否则一定会漂移：
 * <ol>
 *   <li><b>注册 / 查找</b>：模型发起调用时按名字找到工具；</li>
 *   <li><b>入参校验</b>：用 {@link ToolSpec#parameters()} 这份声明做校验，
 *       模型少传、传错类型、传了不认识的参数，都要变成<b>结构化错误</b>而不是异常；</li>
 *   <li><b>给模型看的声明列表</b>：{@link #specs()} 直接喂给模型。</li>
 * </ol>
 *
 * <p><b>为什么「一份 schema 两处复用」很重要</b>：
 * 如果喂给模型的 schema 和代码里真正校验的 schema 是两份，
 * 迟早会出现「模型以为能传 retries、代码却把它当未知参数丢掉」这类问题，
 * 而且这类问题在离线测试里完全看不出来。
 *
 * <p>校验失败<b>不抛异常</b>，而是返回 {@link ValidationResult}：
 * 校验失败是 Agent Loop 的正常分支，模型需要读到「哪里错了、应该怎么改」才能自我纠正。
 */
public final class ToolRegistry {

    /** 工具不存在。 */
    public static final String CODE_UNKNOWN_TOOL = "UNKNOWN_TOOL";
    /** 缺少必填参数。 */
    public static final String CODE_MISSING_REQUIRED = "MISSING_REQUIRED";
    /** 参数类型不符。 */
    public static final String CODE_TYPE_MISMATCH = "TYPE_MISMATCH";
    /** 出现未知参数。 */
    public static final String CODE_UNKNOWN_PARAM = "UNKNOWN_PARAM";
    /** 参数不是合法 JSON 对象。 */
    public static final String CODE_INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    /** 工具不存在时给模型的统一提示前缀。 */
    public static final String CODE_TOOL_NOT_FOUND = CODE_UNKNOWN_TOOL;

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** 注册工具；同名工具会被覆盖（后注册者生效），便于测试里替换实现。 */
    public ToolRegistry register(Tool tool) {
        if (tool == null || tool.spec() == null || tool.spec().name() == null) {
            throw new IllegalArgumentException("工具必须有合法的 spec");
        }
        tools.put(tool.spec().name(), tool);
        return this;
    }

    public ToolRegistry registerAll(Collection<Tool> newTools) {
        if (newTools != null) {
            newTools.forEach(this::register);
        }
        return this;
    }

    /** 按名字查找。 */
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 按名字查找，找不到就抛异常（调用方已知工具一定存在时使用）。 */
    public Tool require(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未注册的工具: " + name);
        }
        return tool;
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public Set<String> names() {
        return new LinkedHashSet<>(tools.keySet());
    }

    public int size() {
        return tools.size();
    }

    /** 给模型看的工具声明列表（按注册顺序）。 */
    public List<ToolSpec> specs() {
        List<ToolSpec> specs = new ArrayList<>(tools.size());
        for (Tool tool : tools.values()) {
            specs.add(tool.spec());
        }
        return List.copyOf(specs);
    }

    /** 只暴露指定名字的子集（多 Agent 场景：专家只看得到自己的工具）。 */
    public List<ToolSpec> specsFor(Collection<String> allowedNames) {
        if (allowedNames == null) {
            return specs();
        }
        List<ToolSpec> specs = new ArrayList<>();
        for (Tool tool : tools.values()) {
            if (allowedNames.contains(tool.spec().name())) {
                specs.add(tool.spec());
            }
        }
        return List.copyOf(specs);
    }

    /** 校验一次调用。 */
    public ValidationResult validate(ToolCall call) {
        if (call == null) {
            return ValidationResult.of(new ValidationResult.Violation(CODE_INVALID_ARGUMENTS, null, "调用为空"));
        }
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return ValidationResult.of(new ValidationResult.Violation(CODE_UNKNOWN_TOOL, null,
                    "工具不存在: " + call.name() + "，可用工具: " + names()));
        }
        return validate(call, tool.spec());
    }

    /** 用给定 schema 校验一次调用（不依赖注册表，便于单测与自定义校验）。 */
    public ValidationResult validate(ToolCall call, ToolSpec spec) {
        List<ValidationResult.Violation> violations = new ArrayList<>();
        Map<String, Object> args = parseArguments(call.argumentsJson());
        if (args == null) {
            violations.add(new ValidationResult.Violation(CODE_INVALID_ARGUMENTS, null,
                    "参数不是合法的 JSON 对象: " + call.argumentsJson()));
            return new ValidationResult(false, List.copyOf(violations));
        }

        Map<String, ToolSpec.Param> declared = spec.parameters();
        // 1) 必填检查
        for (Map.Entry<String, ToolSpec.Param> entry : declared.entrySet()) {
            String paramName = entry.getKey();
            ToolSpec.Param param = entry.getValue();
            Object value = args.get(paramName);
            boolean absent = !args.containsKey(paramName) || value == null;
            if (param.required() && param.defaultValue() == null && absent) {
                violations.add(new ValidationResult.Violation(CODE_MISSING_REQUIRED, paramName,
                        "缺少必填参数 '" + paramName + "'（类型 " + param.type() + "）"));
            }
        }
        // 2) 类型检查
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            ToolSpec.Param param = declared.get(entry.getKey());
            if (param == null) {
                continue;
            }
            if (entry.getValue() == null) {
                continue;
            }
            if (!matches(param.type(), entry.getValue())) {
                violations.add(new ValidationResult.Violation(CODE_TYPE_MISMATCH, entry.getKey(),
                        "参数 '" + entry.getKey() + "' 期望类型 " + param.type()
                                + "，实际为 " + describeType(entry.getValue())));
            }
        }
        // 3) 未知参数检查
        for (String key : args.keySet()) {
            if (!declared.containsKey(key)) {
                violations.add(new ValidationResult.Violation(CODE_UNKNOWN_PARAM, key,
                        "未知参数 '" + key + "'，可用参数: " + declared.keySet()));
            }
        }
        return new ValidationResult(violations.isEmpty(), List.copyOf(violations));
    }

    /** 解析参数 JSON；不是合法对象时返回 null。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        // 这里不能用 Json.readMap：它在解析失败时返回空 Map，会把「脏 JSON」和「空对象」混为一谈。
        Object parsed = Json.read(json, Map.class);
        if (parsed == null) {
            return null;
        }
        return (Map<String, Object>) parsed;
    }

    /** 类型匹配。未知类型不做拦截（模型可能自定义类型，别把路堵死）。 */
    static boolean matches(String type, Object value) {
        if (value == null || type == null) {
            return true;
        }
        String t = type.toLowerCase(java.util.Locale.ROOT);
        return switch (t) {
            case "string", "str", "text" -> value instanceof String;
            case "number", "double", "float" -> value instanceof Number;
            case "integer", "int", "long", "short" -> value instanceof Integer || value instanceof Long
                    || value instanceof Short || value instanceof Byte;
            case "boolean", "bool" -> value instanceof Boolean;
            case "array", "list" -> value instanceof List;
            case "object", "map" -> value instanceof Map;
            default -> true;
        };
    }

    static String describeType(Object value) {
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof List) {
            return "array";
        }
        if (value instanceof Map) {
            return "object";
        }
        return value.getClass().getSimpleName();
    }

    /**
     * 校验结果。
     *
     * @param valid     是否通过
     * @param violations 违规明细（通过时为空）
     */
    public record ValidationResult(boolean valid, List<Violation> violations) {

        public ValidationResult {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }

        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult of(Violation... violations) {
            return new ValidationResult(false, List.of(violations));
        }

        /** 第一个错误码（没有违规时为 null）。 */
        public String errorCode() {
            return violations.isEmpty() ? null : violations.get(0).code();
        }

        /** 面向模型的可读错误说明。 */
        public String message() {
            if (violations.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Violation v : violations) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append(v.message());
            }
            return sb.toString();
        }

        /** 转成工具失败结果，直接回灌给模型。 */
        public ToolResult toToolResult() {
            if (valid) {
                return ToolResult.ok("参数校验通过");
            }
            return ToolResult.error(errorCode(), message());
        }

        /**
         * 一条违规。
         *
         * @param code      错误码
         * @param parameter 涉及的参数名（可空）
         * @param message   可读说明
         */
        public record Violation(String code, String parameter, String message) {
        }
    }
}
