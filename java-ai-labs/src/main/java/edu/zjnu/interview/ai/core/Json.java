package edu.zjnu.interview.ai.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 工具。模型返回的参数、Trace 事件、评测报告都走这里。
 *
 * <p>注意 {@link #readMap(String)} 的容错语义：解析失败返回空 Map 而不是抛异常。
 * 面对模型产出的脏 JSON，运行时必须能降级继续，而不是整条链路挂掉。
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private Json() {
    }

    /** 序列化为 JSON 字符串。 */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"_error\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    /** 序列化为美化 JSON（写文件时用）。 */
    public static String writePretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 解析为 Map；失败返回空 Map。 */
    public static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** 解析为 List；失败返回空 List。 */
    public static List<Object> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Object>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 解析为指定类型；失败返回 null。 */
    public static <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    /** 对象转 Map。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Object value) {
        try {
            return MAPPER.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
