package edu.zjnu.interview.ai.harness;

import edu.zjnu.interview.ai.core.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脱敏器：把日志与工具结果里的敏感信息替换掉。
 *
 * <p><b>为什么必须脱敏</b>：在 Agent 系统里，日志和 trace 会同时记录
 * 「模型说了什么」「工具返回了什么」。而工具返回的往往就是原始数据
 * （用户手机号、身份证、银行卡、接口返回的 token）。
 * 没有脱敏，一次 trace 落盘就等于把 PII 复制进了日志系统——而日志系统的
 * 访问权限通常远宽于业务库。
 *
 * <p><b>为什么放在「统一出口」而不是各处调用</b>：
 * 脱敏最怕「漏了一处」。所以这里的定位是<b>所有对外输出（span 属性、
 * 工具结果、异常信息）的最后一道闸</b>，而不是让每个业务代码记得调用。
 *
 * <p><b>规则顺序很重要</b>：先脱「带上下文的键值对」和「厂商专用 key 格式」，
 * 再脱身份证 / 手机号 / 银行卡。反过来会让 18 位身份证被银行卡规则先吃掉，
 * 掩盖掉「这是身份证」这个事实。
 */
public final class SecretRedactor {

    /** 敏感键名（用于结构化字段整体脱敏）。 */
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(api[-_]?key|apikey|access[-_]?key|secret|token|password|passwd|pwd|credential|authorization|cookie|session[-_]?id).*");

    private record Rule(Pattern pattern, int group) {
    }

    private final String replacement;
    private final int keepTrailing;
    private final List<Rule> rules;

    private SecretRedactor(String replacement, int keepTrailing, List<Rule> rules) {
        this.replacement = replacement;
        this.keepTrailing = keepTrailing;
        this.rules = rules;
    }

    /** 默认脱敏器（替换符 {@code [REDACTED]}）。 */
    public static SecretRedactor defaultInstance() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 脱敏一段文本。
     *
     * @param text 原始文本
     * @return 脱敏后的文本；入参为 null 时返回空串
     */
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String out = text;
        for (Rule rule : rules) {
            Matcher matcher = rule.pattern().matcher(out);
            StringBuilder sb = new StringBuilder();
            boolean found = false;
            while (matcher.find()) {
                found = true;
                String whole = matcher.group();
                String sensitive = matcher.group(rule.group());
                if (sensitive == null || sensitive.isEmpty()) {
                    // 没有敏感分组：原样保留，但必须 appendReplacement，
                    // 否则这一段文本会在 appendTail 时被丢掉。
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(whole));
                    continue;
                }
                String replacementText = whole.replace(sensitive, mask(sensitive));
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacementText));
            }
            if (found) {
                matcher.appendTail(sb);
                out = sb.toString();
            }
        }
        return out;
    }

    /** 整体替换（用于已知整段都是敏感值的场景，如 Map 的 value）。 */
    public String redactValue(String value) {
        if (value == null) {
            return "";
        }
        return mask(value);
    }

    /** 脱敏 Map：敏感键的值整体替换，其余值按文本规则脱敏。 */
    public Map<String, String> redactMap(Map<String, String> values) {
        Map<String, String> out = new LinkedHashMap<>();
        if (values == null) {
            return out;
        }
        values.forEach((key, value) -> {
            if (key != null && SENSITIVE_KEY.matcher(key).matches()) {
                out.put(key, redactValue(value));
            } else {
                out.put(key, redact(value));
            }
        });
        return out;
    }

    /** 脱敏列表。 */
    public List<String> redactAll(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            out.add(redact(value));
        }
        return out;
    }

    /**
     * 脱敏一段 JSON 文本：按键结构递归处理。
     *
     * <p>比纯文本正则更可靠——JSON 里的值是结构化的，可以精确判断「这个键是不是敏感键」。
     */
    public String redactJson(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        Map<String, Object> map = Json.readMap(json);
        if (map.isEmpty()) {
            return redact(json);
        }
        return Json.write(redactStructure(map));
    }

    private Object redactStructure(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                if (SENSITIVE_KEY.matcher(key).matches()) {
                    out.put(key, v == null ? null : redactValue(String.valueOf(v)));
                } else {
                    out.put(key, redactStructure(v));
                }
            });
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(redactStructure(item));
            }
            return out;
        }
        if (value instanceof String s) {
            return redact(s);
        }
        return value;
    }

    private String mask(String sensitive) {
        if (keepTrailing > 0 && sensitive.length() > keepTrailing) {
            return "***" + sensitive.substring(sensitive.length() - keepTrailing);
        }
        return replacement;
    }

    /** 构造器。 */
    public static final class Builder {
        private String replacement = "[REDACTED]";
        private int keepTrailing;
        private final List<Rule> rules = new ArrayList<>();

        /** 自定义替换符。 */
        public Builder replacement(String value) {
            this.replacement = value == null || value.isEmpty() ? "[REDACTED]" : value;
            return this;
        }

        /** 保留末尾 N 个字符（便于人工比对，不至于完全看不出是什么）。 */
        public Builder keepTrailing(int value) {
            this.keepTrailing = Math.max(0, value);
            return this;
        }

        public SecretRedactor build() {
            List<Rule> all = new ArrayList<>();
            // 1) 带上下文的键值对：api_key=xxx / "password": "xxx" / token: xxx
            all.add(new Rule(Pattern.compile(
                    "(?i)(\"?\\b(?:api[-_]?key|apikey|access[-_]?key|secret[-_]?key|client[-_]?secret|secret"
                            + "|access[-_]?token|refresh[-_]?token|auth[-_]?token|bearer[-_]?token|session[-_]?token"
                            + "|token|password|passwd|pwd|credential|credentials|authorization|cookie)\\b\"?\\s*[:=]\\s*\"?)"
                            + "([^\"'\\s,;&}]+)"), 2));
            // 2) Authorization: Bearer xxx
            all.add(new Rule(Pattern.compile("(?i)(\\bBearer\\s+)([A-Za-z0-9\\-._~+/]{8,}=*)"), 2));
            // 3) 厂商专用 key 形态
            all.add(new Rule(Pattern.compile(
                    "\\b(sk-[A-Za-z0-9_\\-]{12,}|gh[pousr]_[A-Za-z0-9]{16,}|AKIA[0-9A-Z]{16}"
                            + "|xox[baprs]-[A-Za-z0-9\\-]{10,}|AIza[0-9A-Za-z\\-_]{20,}|LTAI[A-Za-z0-9]{12,})"), 1));
            // 4) JWT
            all.add(new Rule(Pattern.compile(
                    "\\b(eyJ[A-Za-z0-9_\\-]{5,}\\.[A-Za-z0-9_\\-]{5,}\\.[A-Za-z0-9_\\-]{5,})"), 1));
            // 5) 中国大陆身份证号（18 位，含校验位）
            all.add(new Rule(Pattern.compile(
                    "(?<![0-9A-Za-z])(\\d{6}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx])"
                            + "(?![0-9A-Za-z])"), 1));
            // 6) 手机号（含 +86 前缀）
            all.add(new Rule(Pattern.compile("(?<!\\d)((?:\\+?86[- ]?)?1[3-9]\\d{9})(?!\\d)"), 1));
            // 7) 银行卡号（16~19 位）
            all.add(new Rule(Pattern.compile("(?<!\\d)(\\d{16,19})(?!\\d)"), 1));
            all.addAll(rules);
            return new SecretRedactor(replacement, keepTrailing, all);
        }
    }
}
