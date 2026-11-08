package edu.zjnu.interview.ai.agent.memory;

import edu.zjnu.interview.ai.core.Text;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 事实记忆库（key-value）：按 scope 隔离、带 TTL、支持模糊查找。
 *
 * <p><b>三种记忆形态</b>（这是 Agent 记忆设计的核心区分）：
 * <ol>
 *   <li><b>短期 / 工作记忆</b>：当前会话的消息列表（{@link ConversationState}），
 *       生命周期 = 一次会话，容量受 token 预算约束；</li>
 *   <li><b>长期 / 情景记忆</b>：{@link Scratchpad} 的 JSONL 笔记，
 *       生命周期 = 进程/文件，容量几乎无限，适合记录「做过什么」；</li>
 *   <li><b>长期 / 语义记忆</b>：本类存的结构化事实，
 *       生命周期 = 业务定义，适合记录「是什么」（如「用户偏好简短回答」）。</li>
 * </ol>
 *
 * <p><b>scope 的意义</b>：{@link Scope#SESSION} 的事实只活在当前会话里，
 * {@link Scope#USER} 的事实跨会话共享。多 Agent 场景里，
 * 专家之间必须通过 scope 隔离自己的记忆，否则「检索专家」记的东西会污染「写作专家」。
 *
 * <p><b>为什么查找是模糊的</b>：模型提问的措辞和当初写入事实时的措辞几乎不会完全一致
 * （「用户叫什么」vs「客户姓名」）。用 {@link Text#jaccard(String, String)} 做词项重合度匹配，
 * 在没有 embedding 的离线环境里是一个足够好用的折中；生产环境应换成向量检索。
 */
public final class MemoryStore {

    /** 记忆作用域。 */
    public enum Scope {
        /** 会话级：随会话结束失效。 */
        SESSION,
        /** 用户级：跨会话共享。 */
        USER
    }

    /** 默认模糊匹配阈值。 */
    public static final double DEFAULT_MIN_SIMILARITY = 0.3;

    private final Map<String, Map<Scope, Map<String, Fact>>> store = new LinkedHashMap<>();
    private final Clock clock;

    public MemoryStore() {
        this(Clock.systemUTC());
    }

    public MemoryStore(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** 写入事实（无 TTL）。 */
    public void put(String key, String value, Scope scope) {
        put(key, value, scope, null);
    }

    /**
     * 写入事实。
     *
     * @param key   事实名
     * @param value 事实内容
     * @param scope 作用域
     * @param ttl   存活时长；null 表示永不过期
     */
    public void put(String key, String value, Scope scope, Duration ttl) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("记忆键不能为空");
        }
        Scope s = scope == null ? Scope.SESSION : scope;
        Instant now = clock.instant();
        Instant expiresAt = ttl == null ? null : now.plus(ttl);
        store.computeIfAbsent(key, k -> new LinkedHashMap<>())
                .computeIfAbsent(s, x -> new LinkedHashMap<>())
                .put(key, new Fact(key, value, s, now, expiresAt));
    }

    /** 精确查找（会先剔除过期事实）。 */
    public Optional<Fact> get(String key, Scope scope) {
        Scope s = scope == null ? Scope.SESSION : scope;
        Map<Scope, Map<String, Fact>> byScope = store.get(key);
        if (byScope == null) {
            return Optional.empty();
        }
        Map<String, Fact> facts = byScope.get(s);
        if (facts == null) {
            return Optional.empty();
        }
        Fact fact = facts.get(key);
        if (fact == null) {
            return Optional.empty();
        }
        if (fact.expired(clock.instant())) {
            facts.remove(key);
            return Optional.empty();
        }
        return Optional.of(fact);
    }

    /** 某个作用域下的全部（未过期）事实。 */
    public List<Fact> all(Scope scope) {
        Scope s = scope == null ? Scope.SESSION : scope;
        Instant now = clock.instant();
        List<Fact> out = new ArrayList<>();
        for (Map<Scope, Map<String, Fact>> byScope : store.values()) {
            Map<String, Fact> facts = byScope.get(s);
            if (facts == null) {
                continue;
            }
            for (Fact fact : facts.values()) {
                if (!fact.expired(now)) {
                    out.add(fact);
                }
            }
        }
        return out;
    }

    /**
     * 模糊查找：按 {@link Text#jaccard(String, String)} 与 key/value 的重合度排序。
     *
     * @param query          查询串
     * @param scope          作用域
     * @param minSimilarity  相似度阈值
     */
    public List<Fact> find(String query, Scope scope, double minSimilarity) {
        Instant now = clock.instant();
        List<Fact> candidates = new ArrayList<>();
        for (Fact fact : all(scope)) {
            double keyScore = Text.jaccard(query, fact.key());
            // value 的权重低一些：匹配到「键」比匹配到「值」更能说明是同一条事实
            double valueScore = Text.jaccard(query, fact.value()) * 0.5;
            double score = Math.max(keyScore, valueScore);
            if (score >= minSimilarity) {
                candidates.add(fact.withScore(score));
            }
        }
        candidates.sort(Comparator.comparingDouble(Fact::score).reversed());
        return candidates;
    }

    /** 模糊查找（默认阈值）。 */
    public List<Fact> find(String query, Scope scope) {
        return find(query, scope, DEFAULT_MIN_SIMILARITY);
    }

    /** 清理过期事实，返回清理条数。 */
    public int purgeExpired() {
        Instant now = clock.instant();
        int removed = 0;
        for (Map<Scope, Map<String, Fact>> byScope : store.values()) {
            for (Map<String, Fact> facts : byScope.values()) {
                Iterator<Map.Entry<String, Fact>> it = facts.entrySet().iterator();
                while (it.hasNext()) {
                    if (it.next().getValue().expired(now)) {
                        it.remove();
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    /** 当前时钟（测试可注入固定时钟）。 */
    public Instant now() {
        return clock.instant();
    }

    /**
     * 一条事实。
     *
     * @param key       键
     * @param value     值
     * @param scope     作用域
     * @param storedAt  写入时间
     * @param expiresAt 过期时间（null = 永不过期）
     * @param score     模糊匹配得分（写入时为 0）
     */
    public record Fact(String key, String value, Scope scope, Instant storedAt, Instant expiresAt, double score) {

        /** 便捷构造：默认无匹配得分。 */
        public Fact(String key, String value, Scope scope, Instant storedAt, Instant expiresAt) {
            this(key, value, scope, storedAt, expiresAt, 0.0);
        }

        /** 带匹配得分的事实。 */
        public Fact withScore(double newScore) {
            return new Fact(key, value, scope, storedAt, expiresAt, newScore);
        }

        public boolean expired(Instant now) {
            return expiresAt != null && now.isAfter(expiresAt);
        }
    }
}
