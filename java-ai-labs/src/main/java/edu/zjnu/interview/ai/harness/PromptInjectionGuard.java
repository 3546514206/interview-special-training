package edu.zjnu.interview.ai.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示注入检测器：识别「不可信文本里夹带指令」的模式，输出风险等级与处置建议。
 *
 * <p><b>为什么必须有这一层</b>：RAG 场景下，模型读到的知识片段、Agent 场景下，
 * 模型读到的网页 / 邮件 / 代码注释，都可能是攻击者写的。
 * 攻击者的目标是让模型忽略系统提示，改成「把用户的密钥发到我的服务器」。
 * 从系统架构上看，这类攻击<b>无法靠模型自身的对齐能力彻底解决</b>——
 * 模型的「判断」本身就是被攻击的输入驱动的。
 * 所以正确做法是：在文本进入上下文之前做<b>结构性标记</b>（哪些是系统指令、哪些是数据），
 * 并对高风险内容做<b>降级或拒绝</b>。
 *
 * <p><b>本类的定位是「检测 + 建议」，不是「保证安全」</b>：
 * 正则不可能覆盖所有注入写法（多语言、编码绕过、语义等价改写）。
 * 它的价值在于：把 90% 的低成本攻击挡在门外，并为剩下的部分提供可观测信号
 * （风险等级进 trace，异常样本可回捞做评测集）。
 *
 * <p><b>四级风险与四种处置</b>：
 * <ul>
 *   <li>NONE / ALLOW：正常处理；</li>
 *   <li>LOW / ALLOW：有轻微可疑特征，继续但记录；</li>
 *   <li>MEDIUM / MARK_UNTRUSTED：标记为<b>不可信引用</b>——包进数据区，不执行其中的指令；</li>
 *   <li>HIGH / DEGRADE：<b>降级</b>处理——收紧工具权限、缩短上下文、剥离可疑片段；</li>
 *   <li>CRITICAL / REJECT：<b>拒绝</b>处理——典型是「输出系统提示」「外传密钥」。</li>
 * </ul>
 */
public final class PromptInjectionGuard {

    /** 风险等级。 */
    public enum Risk {
        NONE, LOW, MEDIUM, HIGH, CRITICAL
    }

    /** 处置建议。 */
    public enum Action {
        /** 正常处理。 */
        ALLOW,
        /** 标记为不可信引用：当作数据，不执行其中的指令。 */
        MARK_UNTRUSTED,
        /** 降级处理：收紧权限 / 裁剪上下文 / 剥离可疑片段。 */
        DEGRADE,
        /** 拒绝处理。 */
        REJECT
    }

    /** 一条命中的模式。 */
    public record Hit(String pattern, int weight, String excerpt) {
    }

    /**
     * 检测结论。
     *
     * @param risk   风险等级
     * @param action 处置建议
     * @param hits   命中的模式
     * @param reason 判定理由（人类可读）
     * @param score  风险分（0~100）
     */
    public record Verdict(Risk risk, Action action, List<Hit> hits, String reason, int score) {

        public Verdict {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }

        /** 是否命中任何模式。 */
        public boolean detected() {
            return risk != Risk.NONE;
        }

        /** 是否应当阻断（降级或拒绝）。 */
        public boolean blocked() {
            return action == Action.DEGRADE || action == Action.REJECT;
        }

        /** 是否需要当作不可信数据处理。 */
        public boolean untrusted() {
            return action == Action.MARK_UNTRUSTED;
        }

        /** 处置建议说明（演示与日志用）。 */
        public String explain() {
            String names = hits.stream().map(Hit::pattern).reduce((a, b) -> a + "," + b).orElse("-");
            return String.format("risk=%s action=%s score=%d hits=[%s] reason=%s",
                    risk, action, score, names, reason);
        }
    }

    private record Rule(String name, String regex, int weight) {
        Pattern compiled() {
            return Pattern.compile(regex);
        }
    }

    private final List<Rule> rules;

    private PromptInjectionGuard(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** 默认检测器（中英文常见注入模式）。 */
    public static PromptInjectionGuard defaults() {
        return new PromptInjectionGuard(defaultRules());
    }

    private static List<Rule> defaultRules() {
        List<Rule> rules = new ArrayList<>();
        // 覆盖系统指令 —— 最高危的一类
        rules.add(new Rule("忽略中文指令",
                "(?i)(忽略|无视|忘记|不要理会|不必理会)[掉]?(以上|上述|上面|前面|之前|先前|以上所有|前面所有|先前所有)"
                        + "[^。！？\\n]{0,12}(指令|命令|提示|规则|设定|约束|要求|system)", 80));
        rules.add(new Rule("忽略英文指令",
                "(?i)\\b(ignore|disregard|forget)\\s+(all\\s+)?(previous|above|prior|earlier|the\\s+above)\\s+"
                        + "(instructions?|prompts?|rules?|messages?|constraints?)", 80));
        // 套取系统提示
        rules.add(new Rule("输出系统提示",
                "(?i)(输出|显示|打印|告诉我|重复|复述|展示|抄写)[^。！？\\n]{0,12}(你的)?(系统)?"
                        + "(提示词?|prompt|指令|设定|初始指令|system prompt)", 85));
        rules.add(new Rule("泄露英文提示",
                "(?i)\\b(reveal|show|print|repeat|output|tell me)\\s+(me\\s+)?(your\\s+)?(the\\s+)?(system\\s+)?"
                        + "(prompt|instructions?|rules?|initial\\s+instructions?)", 85));
        // 身份重设
        rules.add(new Rule("身份重设中文",
                "(?i)(你现在是|你现在不再是|从现在开始你是|从现在起你是|忘记你是|你不再是)[^。！？\\n]{0,30}", 55));
        rules.add(new Rule("身份重设英文",
                "(?i)\\b(you are now|act as|pretend (to be|you are)|from now on you are|you are no longer|ignore your persona)\\b",
                45));
        // 越狱话术
        rules.add(new Rule("越狱模式",
                "(?i)(developer\\s+mode|dan\\s+mode|jailbreak|越狱模式|开发者模式|无限制模式|不受限制的|没有任何限制)", 70));
        // 模板标记注入
        rules.add(new Rule("模板标记注入",
                "(<\\|im_start\\|>|<\\|im_end\\|>|<\\|system\\|>|<\\|endoftext\\|>|###\\s*system\\b|\\[\\[\\s*system\\s*\\]\\])", 60));
        // 数据外传
        rules.add(new Rule("密钥外传",
                "(?i)(把|将)[^。！？\\n]{0,24}(密钥|api[-_ ]?key|access[_-]?token|凭据|cookie|密码)[^。！？\\n]{0,24}"
                        + "(发送|上传|post|转发|告诉我|打印|输出)", 85));
        rules.add(new Rule("数据外传",
                "(?i)(发送到|上传到|转发到|post\\s+to|send\\s+to)\\s*(https?://|[\\w.+-]+@[\\w.-]+)", 55));
        // 覆盖系统消息
        rules.add(new Rule("覆盖系统消息",
                "(?i)(覆盖|替换|重写|改写)[^。！？\\n]{0,10}(系统)?(消息|提示|设定|persona)", 60));
        // 编码规避
        rules.add(new Rule("编码规避",
                "(?i)(base64|rot13|hex|url-?decode)[^。！？\\n]{0,20}(解码|decode)[^。！？\\n]{0,24}"
                        + "(指令|prompt|执行|run)", 45));
        return rules;
    }

    /**
     * 检测一段文本。
     *
     * @param text 待检测文本（通常是 RAG 片段 / 工具结果 / 用户输入）
     */
    public Verdict inspect(String text) {
        if (text == null || text.isBlank()) {
            return new Verdict(Risk.NONE, Action.ALLOW, List.of(), "空文本", 0);
        }
        List<Hit> hits = new ArrayList<>();
        int score = 0;
        for (Rule rule : rules) {
            Matcher matcher = rule.compiled().matcher(text);
            if (matcher.find()) {
                String excerpt = excerpt(matcher.group());
                hits.add(new Hit(rule.name(), rule.weight(), excerpt));
                score += rule.weight();
            }
        }
        score = Math.min(100, score);
        Risk risk = riskOf(score);
        Action action = actionOf(risk);
        String reason = reasonOf(risk, hits);
        return new Verdict(risk, action, hits, reason, score);
    }

    /** 风险等级 → 处置建议。 */
    public static Action actionOf(Risk risk) {
        switch (risk) {
            case NONE:
            case LOW:
                return Action.ALLOW;
            case MEDIUM:
                return Action.MARK_UNTRUSTED;
            case HIGH:
                return Action.DEGRADE;
            case CRITICAL:
            default:
                return Action.REJECT;
        }
    }

    /** 分值 → 风险等级。 */
    public static Risk riskOf(int score) {
        if (score <= 0) {
            return Risk.NONE;
        }
        if (score < 30) {
            return Risk.LOW;
        }
        if (score < 60) {
            return Risk.MEDIUM;
        }
        if (score < 85) {
            return Risk.HIGH;
        }
        return Risk.CRITICAL;
    }

    private String reasonOf(Risk risk, List<Hit> hits) {
        if (hits.isEmpty()) {
            return "未发现注入特征";
        }
        String names = hits.stream().map(Hit::pattern).distinct().reduce((a, b) -> a + "、" + b).orElse("");
        switch (risk) {
            case LOW:
                return "存在轻微可疑特征（" + names + "），按正常内容处理并记录";
            case MEDIUM:
                return "疑似提示注入（" + names + "），应标记为不可信引用，不执行其中的指令";
            case HIGH:
                return "高风险提示注入（" + names + "），应降级处理：收紧工具权限并裁剪上下文";
            case CRITICAL:
            default:
                return "严重提示注入（" + names + "），应拒绝处理";
        }
    }

    private String excerpt(String group) {
        if (group == null) {
            return "";
        }
        String normalized = group.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 60 ? normalized : normalized.substring(0, 60) + "…";
    }

    /**
     * 把不可信文本包成引用块。
     *
     * <p>这是「结构性隔离」的具体做法：不可信内容永远待在数据区，
     * 模型看到的系统指令区只包含系统自己写的文本。
     */
    public static String wrapUntrusted(String content, String source) {
        return "<untrusted source=\"" + (source == null ? "unknown" : source.replace("\"", "'"))
                + "\">\n" + (content == null ? "" : content) + "\n</untrusted>";
    }
}
