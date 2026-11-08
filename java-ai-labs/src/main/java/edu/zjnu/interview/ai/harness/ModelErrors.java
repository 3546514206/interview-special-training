package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型调用的结构化错误载荷。
 *
 * <p><b>为什么错误要结构化</b>：模型层的失败信息如果只是一句自然语言，
 * 上层就既没法统计（哪种错误最多）、也没法决策（能不能重试、要不要降级）。
 * 这里定义一个稳定的错误载荷格式：
 * <pre>
 * {"error":{"category":"timeout","status":504,"message":"..."}}
 * </pre>
 * 它同时被三处使用：
 * <ul>
 *   <li>适配器失败时返回的 {@code ChatResponse} 内容；</li>
 *   <li>{@link FailureClassifier} 据此判断「该不该重试」；</li>
 *   <li>span 属性里的错误分类（可观测、可聚合）。</li>
 * </ul>
 */
public final class ModelErrors {

    /** 错误载荷的顶层键。 */
    public static final String ERROR_KEY = "error";
    /** 分类键。 */
    public static final String CATEGORY_KEY = "category";
    /** HTTP 状态码键。 */
    public static final String STATUS_KEY = "status";
    /** 错误信息键。 */
    public static final String MESSAGE_KEY = "message";
    /** 错误码键。 */
    public static final String CODE_KEY = "code";

    private ModelErrors() {
    }

    /** 构造错误载荷（JSON 字符串）。 */
    public static String payload(FailureCategory category, int status, String message) {
        return payload(category, status, message, null);
    }

    /** 构造错误载荷（JSON 字符串，带业务错误码）。 */
    public static String payload(FailureCategory category, int status, String message, String code) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put(CATEGORY_KEY, category == null ? FailureCategory.UNKNOWN.name() : category.name());
        error.put(STATUS_KEY, status);
        error.put(MESSAGE_KEY, message == null ? "" : message);
        if (code != null) {
            error.put(CODE_KEY, code);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(ERROR_KEY, error);
        return Json.write(root);
    }

    /** 从错误载荷里取分类。 */
    public static FailureCategory categoryOf(String content) {
        Map<String, Object> error = errorSection(content);
        if (error.isEmpty()) {
            return FailureCategory.UNKNOWN;
        }
        Object category = error.get(CATEGORY_KEY);
        return category == null ? FailureCategory.UNKNOWN : FailureCategory.parse(String.valueOf(category));
    }

    /** 从错误载荷里取 HTTP 状态码（没有则 0）。 */
    public static int statusOf(String content) {
        Map<String, Object> error = errorSection(content);
        Object status = error.get(STATUS_KEY);
        return status instanceof Number n ? n.intValue() : 0;
    }

    /** 从错误载荷里取错误信息。 */
    public static String messageOf(String content) {
        Map<String, Object> error = errorSection(content);
        Object message = error.get(MESSAGE_KEY);
        return message == null ? content : String.valueOf(message);
    }

    /** 是否是本模块定义的结构化错误载荷。 */
    public static boolean isStructured(String content) {
        return !errorSection(content).isEmpty();
    }

    private static Map<String, Object> errorSection(String content) {
        if (content == null || content.isBlank()) {
            return Map.of();
        }
        Map<String, Object> root = Json.readMap(content);
        Object error = root.get(ERROR_KEY);
        if (error instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    out.put(String.valueOf(k), v);
                }
            });
            return out;
        }
        return Map.of();
    }
}
