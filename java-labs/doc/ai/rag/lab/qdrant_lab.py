#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RAG 专题实验：在真实 Qdrant 上复现答卷里引用的每一个数字。

    python qdrant_lab.py            # 跑全部 5 个实验
    python qdrant_lab.py profile    # 只跑某一个实验

环境变量（全部必填，除 TOKEN 外均可空）：
    QDRANT_BASE_URL     http://host:6333
    QDRANT_COLLECTION   集合名
    QDRANT_TOKEN        API key（空 = 不带鉴权）
    QDRANT_MODEL_PATH   本地 embedding 模型目录（默认用 sentence-transformers 自动找）

依赖：qdrant-client + sentence-transformers。
脚本只读，不写不删任何点。
"""
import os
import statistics
import sys
import time
import uuid

from qdrant_client import QdrantClient
from qdrant_client import models

BASE_URL = os.environ.get("QDRANT_BASE_URL") or ""
COLLECTION = os.environ.get("QDRANT_COLLECTION") or ""
TOKEN = os.environ.get("QDRANT_TOKEN") or ""
MODEL_PATH = os.environ.get("QDRANT_MODEL_PATH") or ""
# 拒答阈值：命中域 top1 最低 0.6913，落空域 top1 最高 0.6126，取中间偏保守
THRESHOLD = float(os.environ.get("QDRANT_THRESHOLD") or "0.65")
# 写前语义查重阈值：同内容 id 相同（score=1.0），次相似 0.7867，0.95 留足余量
DUP_THRESHOLD = float(os.environ.get("QDRANT_DUP_THRESHOLD") or "0.95")

HIT_QUERIES = [
    "RAG 检索不到相关文档怎么办",
    "HNSW 和 IVF 索引有什么区别",
    "MySQL 间隙锁在什么场景下会加锁",
    "Kafka 消费者 rebalance 是怎么触发的",
    "Spring 循环依赖怎么解决",
    "Redis 持久化 RDB 和 AOF 的区别",
    "JVM 垃圾回收 CMS 和 G1 对比",
]
MISS_QUERIES = [
    "今天天气怎么样",
    "红烧肉怎么做才好吃",
    "帮我订一张去北京的机票",
    "我家猫不吃饭怎么办",
    "推荐几部好看的电影",
    "明天股市会涨吗",
]


def die(msg):
    print("ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def build_client():
    if not BASE_URL or not COLLECTION:
        die("需要 QDRANT_BASE_URL 和 QDRANT_COLLECTION")
    kw = {"url": BASE_URL, "timeout": 120}
    if TOKEN:
        kw["api_key"] = TOKEN
    return QdrantClient(**kw)


def build_model():
    from sentence_transformers import SentenceTransformer
    if MODEL_PATH:
        return SentenceTransformer(MODEL_PATH)
    # 本地模型目录优先，避免每次跑实验都去网上拉
    for cand in (os.path.expanduser("~/models/bge-m3"),
                 "/home/setsunayang/models/bge-m3",
                 os.path.expanduser("~/.cache/huggingface/hub/models--BAAI--bge-m3")):
        if os.path.isdir(cand) and os.path.exists(os.path.join(cand, "config.json")):
            print("[model] %s" % cand)
            return SentenceTransformer(cand)
    print("[model] 未找到本地模型，回退到 BAAI/bge-m3（会触发下载）")
    return SentenceTransformer("BAAI/bge-m3")


def ms(t0):
    return (time.perf_counter() - t0) * 1000


def pstats(vals):
    vals = sorted(vals)
    return {"p50": round(statistics.median(vals), 1),
            "p95": round(vals[max(0, int(len(vals) * 0.95) - 1)], 1),
            "max": round(vals[-1], 1)}


# ---------------------------------------------------------------- 实验 1
def lab_profile(cl):
    """集合画像：点数 / 维度 / 距离 / HNSW 参数 / payload 索引。"""
    info = cl.get_collection(COLLECTION)
    c = info.config
    print("=== 实验 1 · 集合画像 ===")
    print("points            :", info.points_count)
    print("indexed_vectors   :", info.indexed_vectors_count)
    print("status            :", info.status)
    print("dim               :", c.params.vectors.size)
    print("distance          :", c.params.vectors.distance)
    print("hnsw.m            :", c.hnsw_config.m)
    print("hnsw.ef_construct :", c.hnsw_config.ef_construct)
    print("full_scan_thresh  :", c.hnsw_config.full_scan_threshold)
    print("quantization      :", c.quantization_config)
    print("optimizer_status  :", info.optimizer_status)
    print("payload 索引（只在这些字段上过滤才是 O(索引)）：")
    for name, sch in (info.payload_schema or {}).items():
        d = sch.model_dump()
        print("   %-16s %-10s points=%s" % (name, d.get("data_type"), d.get("points")))


# ---------------------------------------------------------------- 实验 2
def lab_latency(cl, model):
    """延迟分解：本地向量化 vs 服务端检索 vs payload 传输。"""
    print("\n=== 实验 2 · 延迟分解（p50/p95，ms） ===")
    v = model.encode(HIT_QUERIES, normalize_embeddings=True)

    vals = []
    for _ in range(20):
        t0 = time.perf_counter()
        model.encode(["RAG 检索不到相关文档怎么办"], normalize_embeddings=True)
        vals.append(ms(t0))
    print("本地向量化 1 条      :", pstats(vals))

    for k in (5, 10, 20, 50):
        vals = []
        for q in v:
            for _ in range(3):
                t0 = time.perf_counter()
                cl.query_points(collection_name=COLLECTION, query=q.tolist(), limit=k,
                                with_payload=True)
                vals.append(ms(t0))
        print("检索 k=%-3d 带 payload :" % k, pstats(vals))

    vals = []
    for q in v:
        for _ in range(3):
            t0 = time.perf_counter()
            cl.query_points(collection_name=COLLECTION, query=q.tolist(), limit=20,
                            with_payload=False)
            vals.append(ms(t0))
    print("检索 k=20  不带 payload :", pstats(vals))

    vals = []
    for q in v:
        for _ in range(3):
            t0 = time.perf_counter()
            cl.query_points(collection_name=COLLECTION, query=q.tolist(), limit=20,
                            with_payload=["content"])
            vals.append(ms(t0))
    print("检索 k=20  只取 content :", pstats(vals))


# ---------------------------------------------------------------- 实验 3
def lab_filter(cl, model):
    """payload 过滤：有索引 vs 无索引，差一个数量级。"""
    print("\n=== 实验 3 · payload 过滤的代价 ===")
    v = model.encode(HIT_QUERIES, normalize_embeddings=True)
    indexed = models.Filter(must=[models.FieldCondition(
        key="topic", match=models.MatchValue(value="数据库"))])
    # h2 没有建索引 -> 服务端退化成全量过滤
    not_indexed = models.Filter(must=[models.FieldCondition(
        key="h2", match=models.MatchValue(value="04、RAG知识库（40 题）"))])

    for name, flt in (("有索引 topic=数据库", indexed), ("无索引 h2=...", not_indexed)):
        vals = []
        for q in v:
            for _ in range(2):
                t0 = time.perf_counter()
                cl.query_points(collection_name=COLLECTION, query=q.tolist(), limit=20,
                                with_payload=True, query_filter=flt)
                vals.append(ms(t0))
        print("%-22s:" % name, pstats(vals))

    # 过滤会改变召回结果
    plain = cl.query_points(collection_name=COLLECTION, query=v[0].tolist(), limit=10,
                            with_payload=True).points
    filt = cl.query_points(collection_name=COLLECTION, query=v[0].tolist(), limit=10,
                            with_payload=True, query_filter=indexed).points
    same = len({p.id for p in plain} & {p.id for p in filt})
    print("过滤前后 top10 重合：%d/10  <- 过滤不是「排序后再筛」，是换了候选集" % same)


# ---------------------------------------------------------------- 实验 4
def lab_threshold(cl, model):
    """拒答阈值：命中域 vs 落空域的 top1 分数分布。"""
    print("\n=== 实验 4 · 相似度阈值怎么定 ===")

    def profile(queries):
        vs = model.encode(queries, normalize_embeddings=True)
        rows = []
        for q, vec in zip(queries, vs):
            pts = cl.query_points(collection_name=COLLECTION, query=vec.tolist(), limit=10,
                                  with_payload=True).points
            rows.append((q, pts[0].score))
        return rows

    hit = profile(HIT_QUERIES)
    miss = profile(MISS_QUERIES)
    for q, s in hit:
        print("  命中  %.4f  %s" % (s, q))
    for q, s in miss:
        print("  落空  %.4f  %s" % (s, q))
    h = [s for _, s in hit]
    m = [s for _, s in miss]
    print("命中域 top1: min=%.4f max=%.4f avg=%.4f" % (min(h), max(h), sum(h) / len(h)))
    print("落空域 top1: min=%.4f max=%.4f avg=%.4f" % (min(m), max(m), sum(m) / len(m)))
    print("建议阈值 %.2f（落在 %.4f ~ %.4f 的空隙里）" % (THRESHOLD, max(m), min(h)))


# ---------------------------------------------------------------- 实验 5
def lab_dedup(cl, model):
    """幂等 id（uuid5）+ 写前语义查重（0.95）。只读，不写库。"""
    print("\n=== 实验 5 · 幂等写入与写前查重 ===")
    seed = cl.query_points(collection_name=COLLECTION, query=model.encode(
        ["HNSW 和 IVF 索引有什么区别"], normalize_embeddings=True)[0].tolist(),
        limit=1, with_payload=True).points[0]
    content = (seed.payload or {}).get("content", "")
    text = content[:300]

    same_id = str(uuid.uuid5(uuid.NAMESPACE_URL, text))
    print("原文前 300 字         :", text.replace("\n", " ")[:80], "...")
    print("uuid5(NAMESPACE_URL, 原文) =", same_id)
    print("库里那条点的 id       =", seed.id)
    print("两者一致 -> 同内容重跑只会更新同一个点（幂等）:", same_id == str(seed.id))

    vec = model.encode([text], normalize_embeddings=True)[0]
    hits = cl.query_points(collection_name=COLLECTION, query=vec.tolist(), limit=3,
                           with_payload=True).points
    for p in hits:
        print("   近邻 score=%.4f id=%s" % (p.score, p.id))
    print("同内容自查 score 恒为 1.0（向量已归一化 + Cosine）；次相似 %.4f，" % hits[1].score)
    print("所以查重阈值取 %.2f：既能挡住重复，又不会误杀语义相近的不同段落" % DUP_THRESHOLD)


LABS = {
    "profile": lab_profile,
    "latency": lab_latency,
    "filter": lab_filter,
    "threshold": lab_threshold,
    "dedup": lab_dedup,
}


def main(argv):
    which = argv[1:] or list(LABS)
    unknown = [w for w in which if w not in LABS]
    if unknown:
        die("未知实验: %s（可选 %s）" % (", ".join(unknown), ", ".join(LABS)))

    cl = build_client()
    model = None
    for name in which:
        fn = LABS[name]
        if name == "profile":
            fn(cl)
        else:
            if model is None:
                model = build_model()
            fn(cl, model)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
