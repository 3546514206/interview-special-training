package edu.zjnu.interview.ai.rag;

import edu.zjnu.interview.ai.core.Ids;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 递归切分器：段落 → 句子 → 字符 三级降级。
 *
 * <p><b>为什么是递归降级而不是固定长度硬切？</b>
 * 硬切会把「实缴资本不低于 1000 万元」劈成两半，检索时召回了前半句却答不出问题——
 * 这类「答案被切断」是 RAG 里最隐蔽的质量损失。
 * 递归降级的思路是：优先在<b>语义边界</b>（段落）切；段落太长就在<b>句子边界</b>切；
 * 单句仍然超长（例如一整段没有句号的表格行）才在字符层面硬切。
 * 降级顺序保证「能用自然边界就绝不用硬边界」。
 *
 * <p><b>为什么要有 overlap？</b>
 * 句子边界切分仍可能把一个完整的事实在边界处劈开（如「…实缴资本不低于 1000」/「万元」）。
 * overlap 让相邻片段共享一小段尾巴，被劈开的事实至少有一侧是完整的，召回后仍能还原。
 * 代价是索引体积与 BM25 的词频统计轻微膨胀，所以 overlap 取 chunkSize 的 10%~20% 是经验值。
 *
 * <p><b>为什么要有 minChunkChars？</b>
 * 过短的片段（例如切分残留的半句）向量噪声大、语义不完整，常常挤占 top-k 名额。
 * 这里的处理是<b>向前合并</b>而不是直接丢弃——丢弃会丢事实（不可接受），合并只影响边界。
 *
 * <p><b>幂等性</b>：片段 id = {@code Ids.deterministicUuid(ns, docId + "#" + ordinal)}。
 * 同样的语料 + 同样的配置 → 同样的 id → 向量库 upsert 幂等覆盖 → 评测可复现。
 */
public final class Chunker {

    /** 片段 id 的命名空间。 */
    public static final String CHUNK_NAMESPACE = "rag-chunk";

    /** 默认分隔符：段落 → 句子 → 短句（逗号/顿号级）。 */
    public static final List<String> DEFAULT_SEPARATORS =
            List.of("\\R\\s*\\R", "(?<=[。！？；])", "(?<=[，、：])");

    private final Config config;

    public Chunker(Config config) {
        this.config = config == null ? Config.defaults() : config;
    }

    public Chunker() {
        this(Config.defaults());
    }

    public Config config() {
        return config;
    }

    /**
     * 切分配置。
     *
     * @param chunkSize    单片最大字符数
     * @param overlap      相邻片重叠字符数
     * @param minChunkChars 过短片段的阈值（短于此值会被向前合并）
     * @param separators   分隔符（正则），按优先级从高到低
     */
    public record Config(int chunkSize, int overlap, int minChunkChars, List<String> separators) {

        public Config {
            if (chunkSize <= 0) {
                throw new IllegalArgumentException("chunkSize must be positive");
            }
            if (overlap < 0 || overlap >= chunkSize) {
                throw new IllegalArgumentException("overlap must be in [0, chunkSize)");
            }
            if (minChunkChars < 0) {
                throw new IllegalArgumentException("minChunkChars must be >= 0");
            }
            separators = (separators == null || separators.isEmpty())
                    ? DEFAULT_SEPARATORS : List.copyOf(separators);
        }

        /** 默认配置：400 字 / 重叠 80 字 / 最短 120 字。 */
        public static Config defaults() {
            return new Config(400, 80, 120, DEFAULT_SEPARATORS);
        }

        public Config withChunkSize(int size) {
            return new Config(size, overlap, minChunkChars, separators);
        }

        public Config withOverlap(int value) {
            return new Config(chunkSize, value, minChunkChars, separators);
        }

        public Config withMinChunkChars(int value) {
            return new Config(chunkSize, overlap, value, separators);
        }
    }

    /**
     * 切分一篇文档。
     *
     * @param doc 文档
     * @return 片段列表（顺序稳定）
     */
    public List<Chunk> chunk(Document doc) {
        List<String> pieces = mergeShortChunks(split(doc.content(), 0));
        List<Chunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            String content = pieces.get(i).strip();
            if (content.isEmpty()) {
                continue;
            }
            String id = Ids.deterministicUuid(CHUNK_NAMESPACE, doc.id() + "#" + i);
            Map<String, Object> metadata = new LinkedHashMap<>(doc.metadata());
            metadata.put("chunkId", id);
            metadata.put("docId", doc.id());
            metadata.put("title", doc.title());
            metadata.put("source", doc.source());
            metadata.put("ordinal", i);
            metadata.put("chars", content.length());
            chunks.add(new Chunk(id, doc.id(), doc.title(), content, i, metadata));
        }
        return chunks;
    }

    /** 批量切分。 */
    public List<Chunk> chunkAll(List<Document> docs) {
        List<Chunk> out = new ArrayList<>();
        for (Document doc : docs) {
            out.addAll(chunk(doc));
        }
        return out;
    }

    // ------------------------------------------------------------------ 切分实现

    /** 递归切分：segs 为空表示已经降级到字符级。 */
    private List<String> split(String text, int sepIndex) {
        String s = text.strip();
        if (s.isEmpty()) {
            return List.of();
        }
        if (s.length() <= config.chunkSize()) {
            return List.of(s);
        }
        if (sepIndex >= config.separators().size()) {
            return hardSplit(s);
        }
        Pattern pattern = Pattern.compile(config.separators().get(sepIndex));
        List<String> segments = new ArrayList<>();
        for (String part : pattern.split(s)) {
            String t = part.strip();
            if (!t.isEmpty()) {
                segments.add(t);
            }
        }
        // 当前分隔符切不动（例如整篇没有空行），降级到下一级
        if (segments.size() <= 1) {
            return split(s, sepIndex + 1);
        }

        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String segment : segments) {
            if (segment.length() > config.chunkSize()) {
                // 单段仍然过长：先落盘已积累的内容，再对该段继续降级切分
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                out.addAll(split(segment, sepIndex + 1));
                continue;
            }
            if (current.length() + segment.length() > config.chunkSize()) {
                if (current.length() > 0) {
                    String emitted = current.toString();
                    out.add(emitted);
                    current = new StringBuilder(overlapTail(emitted));
                }
                if (current.length() + segment.length() > config.chunkSize()) {
                    // 重叠尾巴放不下就丢弃：宁可少一点重叠，也不能让片段超出 chunkSize 上限
                    current.setLength(0);
                }
            }
            current.append(segment);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /** 字符级硬切（最后一级降级），仍然保留 overlap。 */
    private List<String> hardSplit(String s) {
        List<String> out = new ArrayList<>();
        int size = config.chunkSize();
        int overlap = config.overlap();
        int start = 0;
        while (start < s.length()) {
            int end = Math.min(s.length(), start + size);
            out.add(s.substring(start, end));
            if (end >= s.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return out;
    }

    /** 取片段尾部 overlap 个字符作为下一片的开头。 */
    private String overlapTail(String chunk) {
        int overlap = config.overlap();
        if (overlap <= 0 || chunk.length() <= overlap) {
            return "";
        }
        return chunk.substring(chunk.length() - overlap);
    }

    /** 过短片段向前合并（不丢弃事实）。 */
    private List<String> mergeShortChunks(List<String> pieces) {
        int min = config.minChunkChars();
        if (min <= 0) {
            return pieces;
        }
        List<String> out = new ArrayList<>();
        for (String piece : pieces) {
            if (!out.isEmpty() && piece.length() < min) {
                String last = out.remove(out.size() - 1);
                if (last.length() + 1 + piece.length() <= config.chunkSize()) {
                    out.add(last + "\n" + piece);
                    continue;
                }
                out.add(last);
            }
            out.add(piece);
        }
        if (out.size() > 1) {
            String last = out.remove(out.size() - 1);
            if (last.length() < min) {
                String prev = out.remove(out.size() - 1);
                if (prev.length() + 1 + last.length() <= config.chunkSize()) {
                    out.add(prev + "\n" + last);
                } else {
                    out.add(prev);
                    out.add(last);
                }
            } else {
                out.add(last);
            }
        }
        return out;
    }
}
