package edu.zjnu.interview.ai.rag;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 语料加载器：从 classpath 目录读取 .md / .txt 文档。
 *
 * <p><b>为什么不直接用 {@code new File(path)}？</b>
 * 语料是<b>资源</b>不是<b>文件</b>：打成 jar 之后 {@code resources/corpus} 根本不是一个文件系统路径，
 * 只有 {@code ClassLoader.getResourceAsStream} 能稳定读到内容。
 * 所以本类所有正文读取一律走流（{@code getResourceAsStream}），只有在<b>枚举文件名</b>时才用文件系统，
 * 并且对「枚举不到」准备了 manifest 兜底（见 {@link #listResourceNames(String)}）。
 *
 * <p><b>枚举策略</b>（两条路，必须都有）：
 * <ol>
 *   <li>开发/测试环境：classpath 是展开目录（{@code target/classes}），可以用 {@code file:} 协议列出目录；</li>
 *   <li>打包环境：classpath 在 jar 内，目录不可枚举 → 退回到 {@code <dir>/manifest.txt} 清单文件。</li>
 * </ol>
 * 只做一条都会在某种环境下「静默加载到 0 篇文档」，而 0 篇文档会让后续所有指标变成 0，
 * 这种失败必须被显式处理：找不到任何资源时直接抛异常，而不是返回一个空列表。
 *
 * <p>文档解析约定：
 * <ul>
 *   <li>可选 front matter：文件首行 {@code ---} 开始、下一行 {@code ---} 结束，{@code key: value} 形式；</li>
 *   <li>标题：正文里第一个 {@code # xxx} 标题行；</li>
 *   <li>文档 id：文件名主干（如 {@code private-asset-access-standard}），保证跨机器稳定、可被黄金集直接引用。</li>
 * </ul>
 */
public final class CorpusLoader {

    /** 支持的文档后缀。 */
    private static final List<String> EXTENSIONS = List.of(".md", ".txt");

    /** 清单文件名（jar 内枚举目录的兜底方案）。 */
    public static final String MANIFEST = "manifest.txt";

    private CorpusLoader() {
    }

    /**
     * 从 classpath 目录加载全部文档。
     *
     * @param dir classpath 目录，例如 {@code "corpus"}
     * @return 按文件名排序的文档列表（排序保证索引构建顺序稳定 → id 与评测可复现）
     */
    public static List<Document> loadFromClasspath(String dir) {
        List<String> names = listResourceNames(dir);
        if (names.isEmpty()) {
            throw new IllegalStateException("classpath 目录 " + dir + " 下没有找到任何 .md/.txt 资源"
                    + "（若在 jar 中运行，请提供 " + dir + "/" + MANIFEST + " 清单文件）");
        }
        return loadFromClasspath(dir, names);
    }

    /**
     * 从 classpath 目录加载指定文件名的文档。
     *
     * @param dir   classpath 目录
     * @param names 文件名（相对目录）
     */
    public static List<Document> loadFromClasspath(String dir, List<String> names) {
        List<Document> docs = new ArrayList<>();
        for (String name : names) {
            String text = readResource(dir + "/" + name);
            if (text == null) {
                continue;
            }
            docs.add(parse(name, text));
        }
        return docs;
    }

    /**
     * 列出 classpath 目录下的资源文件名（.md / .txt）。
     *
     * @param dir classpath 目录
     * @return 文件名列表（不含目录，不含 {@code manifest.txt}），可能为空
     */
    public static List<String> listResourceNames(String dir) {
        return listResourceNames(dir, EXTENSIONS);
    }

    /**
     * 列出 classpath 目录下的资源文件名（指定后缀）。
     *
     * @param dir        classpath 目录
     * @param extensions 允许的后缀（小写，含点）
     * @return 文件名列表，可能为空
     */
    public static List<String> listResourceNames(String dir, List<String> extensions) {
        List<String> names = new ArrayList<>();
        ClassLoader loader = CorpusLoader.class.getClassLoader();

        // 路径一：classpath 是展开目录，可以直接列目录
        URL dirUrl = loader.getResource(dir);
        if (dirUrl != null && "file".equals(dirUrl.getProtocol())) {
            try {
                Path path = Paths.get(dirUrl.toURI());
                if (Files.isDirectory(path)) {
                    try (Stream<Path> stream = Files.list(path)) {
                        stream.filter(Files::isRegularFile)
                                .map(p -> p.getFileName().toString())
                                .filter(name -> isSupported(name, extensions))
                                .sorted()
                                .forEach(names::add);
                    }
                }
            } catch (URISyntaxException | IOException e) {
                // 目录不可枚举不是致命错误，继续走 manifest 兜底
            }
        }
        if (!names.isEmpty()) {
            return names;
        }

        // 路径二：jar 内目录不可枚举，读 manifest 清单
        String manifest = readResource(dir + "/" + MANIFEST);
        if (manifest != null) {
            for (String line : manifest.split("\\R")) {
                String name = line.strip();
                if (!name.isEmpty() && !name.startsWith("#") && isSupported(name, extensions)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static boolean isSupported(String name) {
        return isSupported(name, EXTENSIONS);
    }

    private static boolean isSupported(String name, List<String> extensions) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (MANIFEST.equals(lower)) {
            return false;
        }
        return extensions.stream().anyMatch(lower::endsWith);
    }

    /** 用流读取 classpath 资源全文（UTF-8）。 */
    private static String readResource(String resource) {
        ClassLoader loader = CorpusLoader.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("读取 classpath 资源失败: " + resource, e);
        }
    }

    /**
     * 解析一篇文档。
     *
     * @param name 文件名（相对目录）
     * @param text 文件全文
     */
    public static Document parse(String name, String text) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String body = text;

        // 1) front matter
        String stripped = body.stripLeading();
        if (stripped.startsWith("---")) {
            int end = stripped.indexOf("\n---", 3);
            if (end > 0) {
                String front = stripped.substring(3, end);
                for (String line : front.split("\\R")) {
                    int colon = line.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    String key = line.substring(0, colon).strip();
                    String value = line.substring(colon + 1).strip();
                    if (!key.isEmpty()) {
                        metadata.put(key, value);
                    }
                }
                body = stripped.substring(end + 4);
            }
        }

        // 2) 标题：第一个 "# xxx"
        String title = "";
        StringBuilder content = new StringBuilder();
        boolean titleTaken = false;
        for (String line : body.split("\\R", -1)) {
            if (!titleTaken && line.startsWith("# ")) {
                title = line.substring(2).strip();
                titleTaken = true;
                continue;
            }
            content.append(line).append('\n');
        }
        if (title.isEmpty()) {
            title = stripExtension(name);
        }

        String id = stripExtension(name);
        metadata.putIfAbsent("source", name);
        metadata.putIfAbsent("docId", id);
        metadata.putIfAbsent("title", title);
        return new Document(id, title, content.toString().strip(), name, metadata);
    }

    private static String stripExtension(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String file = slash >= 0 ? name.substring(slash + 1) : name;
        for (String ext : EXTENSIONS) {
            if (file.toLowerCase(Locale.ROOT).endsWith(ext)) {
                return file.substring(0, file.length() - ext.length());
            }
        }
        return file;
    }

    /** 按文件名排序，保证加载顺序稳定。 */
    static void sortByName(List<Document> docs) {
        docs.sort(Comparator.comparing(Document::source));
    }
}
