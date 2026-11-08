package edu.zjnu.interview.ai.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Prompt 中的结构化区块约定。
 *
 * <p>上下文组装不是「把几段文字拼起来」，而是要让模型能分辨
 * <b>哪些是可信指令、哪些是不可信的资料</b>。
 * 用固定标记把检索到的资料包进 {@code <context>}，一方面方便解析与评测，
 * 二方面在安全上明确了边界：资料区里的任何「指令」都不该被当成系统指令执行。
 *
 * <p>证据（evidence）的行格式固定为：
 * <pre>
 * [1] (doc-id) 文档标题
 * 证据正文第一行
 * 证据正文第二行
 * </pre>
 * 其中 {@code ]} 与 {@code (} 之间允许有空格：{@code [1] (doc-id) 标题} 与 {@code [1](doc-id) 标题}
 * 两种写法都能解析。曾经实现只认后者、注释写的是前者，
 * 导致「按注释组装的上下文解析出 0 条证据」——引用编号全空、引用正确率恒为 0，
 * 而且不报错，属于最难发现的一类静默失效。
 */
public final class PromptBlocks {

    public static final String CONTEXT_OPEN = "<context>";
    public static final String CONTEXT_CLOSE = "</context>";
    public static final String EVIDENCE_OPEN = "<evidence>";
    public static final String EVIDENCE_CLOSE = "</evidence>";

    private PromptBlocks() {
    }

    /** 把上下文包进标记块。 */
    public static String wrapContext(String contextBody) {
        return CONTEXT_OPEN + "\n" + (contextBody == null ? "" : contextBody) + "\n" + CONTEXT_CLOSE;
    }

    /** 取出标记块内容；没有标记时返回 null。 */
    public static String extractContext(String text) {
        return extract(text, CONTEXT_OPEN, CONTEXT_CLOSE);
    }

    public static String extract(String text, String open, String close) {
        if (text == null) {
            return null;
        }
        int s = text.indexOf(open);
        if (s < 0) {
            return null;
        }
        int e = text.indexOf(close, s + open.length());
        if (e < 0) {
            return null;
        }
        return text.substring(s + open.length(), e).trim();
    }

    /**
     * 解析证据列表。
     *
     * @param contextBlock 上下文块正文（不含标记）
     * @return 按出现顺序排列的证据
     */
    public static List<Evidence> parseEvidence(String contextBlock) {
        List<Evidence> result = new ArrayList<>();
        if (contextBlock == null || contextBlock.isBlank()) {
            return result;
        }
        Evidence current = null;
        StringBuilder body = new StringBuilder();
        for (String rawLine : contextBlock.split("\n")) {
            String line = rawLine.strip();
            if (isEvidenceHeader(line)) {
                if (current != null) {
                    result.add(current.withBody(body.toString()));
                }
                body.setLength(0);
                current = parseHeader(line);
            } else if (current != null) {
                if (body.length() > 0) {
                    body.append('\n');
                }
                body.append(line);
            }
        }
        if (current != null) {
            result.add(current.withBody(body.toString()));
        }
        return result;
    }

    /**
     * 是否是证据头。
     *
     * <p>判定条件刻意放宽：只要行首是 {@code [}、含 {@code ]}，
     * 且 {@code ]} 之后（去空白）以 {@code (} 开头，或者方括号里是纯数字，就当作证据头。
     * 这样既能兼容 {@code [1] (id) 标题} 与 {@code [1](id) 标题}，
     * 又不会把正文里的 {@code [注]} 这类方括号行误判成证据头。
     */
    private static boolean isEvidenceHeader(String line) {
        if (!line.startsWith("[") || !line.contains("]")) {
            return false;
        }
        int closeBracket = line.indexOf(']');
        String inside = line.substring(1, closeBracket).strip();
        String rest = line.substring(closeBracket + 1).strip();
        return rest.startsWith("(") || inside.chars().allMatch(Character::isDigit);
    }

    private static Evidence parseHeader(String line) {
        int closeBracket = line.indexOf(']');
        int index = 0;
        try {
            index = Integer.parseInt(line.substring(1, closeBracket).trim());
        } catch (NumberFormatException ignored) {
            // 序号非法就退化为 0，解析不应因为脏数据整体失败
        }
        String rest = line.substring(closeBracket + 1).strip();
        String id = "";
        String title = rest;
        if (rest.startsWith("(")) {
            int end = rest.indexOf(')');
            if (end > 0) {
                id = rest.substring(1, end);
                title = rest.substring(end + 1).strip();
            }
        }
        return new Evidence(index, id, title, "");
    }

    /**
     * 一条证据。
     *
     * @param index   引用序号（回答里用 [1] 标注）
     * @param id      片段 id
     * @param title   来源标题
     * @param content 正文
     */
    public record Evidence(int index, String id, String title, String content) {

        /** 复制并填充正文。 */
        public Evidence withBody(String body) {
            return new Evidence(index, id, title, body);
        }
    }
}
