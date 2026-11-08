package edu.zjnu.interview.ai.rag.eval;

import edu.zjnu.interview.ai.core.Json;
import edu.zjnu.interview.ai.rag.CorpusLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 黄金集加载器：读取 {@code resources/golden/*.json}。
 *
 * <p><b>黄金集是 RAG 质量的唯一客观锚点</b>。没有黄金集，就只能靠「看起来还行」来评估，
 * 一旦换 chunk 大小、换模型、换权重，全靠人肉对比，迭代速度直接归零。
 *
 * <p><b>为什么相关性标注在「文档」粒度而不是「片段」粒度？</b>
 * 因为业务问题关心的是「答案有没有依据」，而依据最终要落到文档上（引用编号指向文档）。
 * 片段粒度的标注会随切分配置漂移——chunk 一改，标注全废，标注成本无法沉淀。
 * 文档粒度的标注是<b>跨配置稳定</b>的，这正是它能做回归门禁的前提。
 */
public final class GoldenDataset {

    /** classpath 目录。 */
    public static final String DEFAULT_DIR = "golden";

    private GoldenDataset() {
    }

    /** 加载默认目录下的全部黄金集。 */
    public static List<GoldenCase> load() {
        return load(DEFAULT_DIR);
    }

    /**
     * 加载 classpath 目录下的全部黄金集。
     *
     * @param dir classpath 目录
     */
    public static List<GoldenCase> load(String dir) {
        List<GoldenCase> cases = new ArrayList<>();
        for (String name : CorpusLoader.listResourceNames(dir, List.of(".json"))) {
            String text = readResource(dir + "/" + name);
            if (text == null) {
                continue;
            }
            cases.addAll(parse(text));
        }
        if (cases.isEmpty()) {
            throw new IllegalStateException("黄金集目录 " + dir + " 下没有解析到任何用例");
        }
        return cases;
    }

    /** 解析一个黄金集文件（支持顶层数组，或 {"cases":[...]} 两种格式）。 */
    static List<GoldenCase> parse(String json) {
        List<GoldenCase> cases = new ArrayList<>();
        Object root = Json.read(json, Object.class);
        if (root instanceof List<?> list) {
            for (Object item : list) {
                GoldenCase parsed = toCase(item);
                if (parsed != null) {
                    cases.add(parsed);
                }
            }
            return cases;
        }
        if (root instanceof Map<?, ?> map) {
            Object array = map.get("cases");
            if (array instanceof List<?> list) {
                for (Object item : list) {
                    GoldenCase parsed = toCase(item);
                    if (parsed != null) {
                        cases.add(parsed);
                    }
                }
            }
        }
        return cases;
    }

    private static GoldenCase toCase(Object item) {
        if (!(item instanceof Map<?, ?> map)) {
            return null;
        }
        String question = string(map.get("question"));
        if (question == null || question.isBlank()) {
            return null;
        }
        return new GoldenCase(
                orDefault(string(map.get("id")), "case-" + Math.abs(question.hashCode())),
                question,
                stringList(map.get("expectedDocIds")),
                stringList(map.get("expectedKeywords")),
                orDefault(string(map.get("referenceAnswer")), ""),
                orDefault(string(map.get("type")), GoldenCase.TYPE_SINGLE_HOP),
                orDefault(string(map.get("difficulty")), GoldenCase.DIFFICULTY_EASY),
                bool(map.get("answerable"), !stringList(map.get("expectedDocIds")).isEmpty()));
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        } else if (value instanceof String s && !s.isBlank()) {
            out.add(s);
        }
        return out;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    private static String readResource(String resource) {
        ClassLoader loader = GoldenDataset.class.getClassLoader();
        try (java.io.InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("读取黄金集失败: " + resource, e);
        }
    }
}
