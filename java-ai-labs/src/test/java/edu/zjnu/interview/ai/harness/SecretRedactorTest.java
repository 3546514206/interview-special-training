package edu.zjnu.interview.ai.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱敏回归：各类敏感信息都必须被替换，且不能误伤正常文本。
 */
class SecretRedactorTest {

    private final SecretRedactor redactor = SecretRedactor.defaultInstance();

    @Test
    @DisplayName("API Key / Token / 密码 都被替换")
    void redactsKeysAndTokens() {
        String text = "api_key=sk-live-0987654321abcdef，"
                + "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature，"
                + "password=\"hunter2\"";
        String redacted = redactor.redact(text);
        assertFalse(redacted.contains("sk-live-0987654321abcdef"));
        assertFalse(redacted.contains("hunter2"));
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiJ9"));
        assertTrue(redacted.contains("[REDACTED]"));
    }

    @Test
    @DisplayName("手机号 / 身份证 / 银行卡 都被替换")
    void redactsPii() {
        String text = "手机号 13800138000，身份证 110101199003071234，银行卡 6222021234567890123";
        String redacted = redactor.redact(text);
        assertFalse(redacted.contains("13800138000"));
        assertFalse(redacted.contains("110101199003071234"));
        assertFalse(redacted.contains("6222021234567890123"));
        assertTrue(redacted.contains("手机号"));
        assertTrue(redacted.contains("身份证"));
        assertTrue(redacted.contains("银行卡"));
    }

    @Test
    @DisplayName("正常文本不被误伤")
    void keepsHarmlessText() {
        String text = "用户问：蚂蚁财富的货币乘数怎么算？答案是 2024 年的宏观数据。";
        String redacted = redactor.redact(text);
        assertTrue(redacted.contains("货币乘数"));
        assertTrue(redacted.contains("2024"));
        assertFalse(redacted.contains("[REDACTED]"));
    }

    @Test
    @DisplayName("替换符可配置；可保留末尾若干位便于人工比对")
    void supportsCustomReplacement() {
        SecretRedactor masked = SecretRedactor.builder()
                .replacement("***")
                .keepTrailing(4)
                .build();
        String redacted = masked.redact("api_key=sk-live-0987654321abcdef");
        assertFalse(redacted.contains("0987654321abcdef"));
        assertTrue(redacted.contains("cdef"), "应保留末尾 4 位");
        assertTrue(redacted.contains("***"));
    }

    @Test
    @DisplayName("结构化 JSON：按敏感键整体替换")
    void redactsJsonByKey() {
        String json = "{\"user\":\"张三\",\"phone\":\"13800138000\","
                + "\"api_key\":\"sk-live-0987654321abcdef\",\"nested\":{\"token\":\"abc123456\"}}";
        String redacted = redactor.redactJson(json);
        assertFalse(redacted.contains("13800138000"));
        assertFalse(redacted.contains("sk-live-0987654321abcdef"));
        assertFalse(redacted.contains("abc123456"));
        assertTrue(redacted.contains("张三"), "非敏感字段要保留");
    }

    @Test
    @DisplayName("Map 脱敏：敏感键整体替换")
    void redactsMap() {
        Map<String, String> redacted = redactor.redactMap(Map.of(
                "username", "zhangsan",
                "access_token", "abcdef123456",
                "note", "联系 13800138000"));
        assertTrue(redacted.get("username").contains("zhangsan"));
        assertFalse(redacted.get("access_token").contains("abcdef123456"));
        assertFalse(redacted.get("note").contains("13800138000"));
    }

    @Test
    @DisplayName("null / 空输入安全")
    void handlesNullInput() {
        assertTrue(redactor.redact(null).isEmpty());
        assertTrue(redactor.redact("").isEmpty());
    }
}
