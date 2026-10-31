# RAG 专题实验指南（Qdrant 实测）

> **目标**：把答卷里引用的每一个数字都**跑出来**。答卷里写「过滤慢 12.5 倍」「阈值 0.65」「重复正文 15.8%」，这里就有一条能复现它的命令。
>
> **环境**：Python 3.11 + `qdrant-client` + `sentence-transformers`（本机 conda 环境 `py311` 已装好：qdrant-client 1.19.0 / sentence-transformers 6.0.1）。
>
> **脚本**：[`lab/qdrant_lab.py`](./lab/qdrant_lab.py) —— 5 个实验，**只读，不写不删任何点**。
>
> **⚠️ 数字会变**：脚本读的是线上真实集合，数据更新后延迟和分布都会变。**结论会复现，具体数值请以你跑出来的为准**。

## 跑法

```bash
cd doc/ai/rag/lab

export QDRANT_BASE_URL=http://<host>:6333
export QDRANT_COLLECTION=<集合名>
export QDRANT_TOKEN=<api key>          # 没有鉴权就不设
export QDRANT_MODEL_PATH=/path/to/bge-m3 # 不设则自动找本地目录，再退回 BAAI/bge-m3 下载

# 跑全部 5 个实验
python qdrant_lab.py

# 只跑某一个
python qdrant_lab.py profile
python qdrant_lab.py latency
python qdrant_lab.py filter
python qdrant_lab.py threshold
python qdrant_lab.py dedup
```

---

## Lab 1 · 集合画像（`profile`）

**看什么**：你的集合到底长什么样——维度、距离、HNSW 参数、有没有量化、**哪些字段建了 payload 索引、每个索引覆盖多少点**。

**关键观察**：
- `hnsw.m` / `hnsw.ef_construct` / `full_scan_threshold` 是 Qdrant 默认值（16 / 100 / 10000）说明你没调过。
- `quantization` 为 `None` 说明没开量化，内存是满血的 float32。
- **每个 payload 索引后面的 `points=` 是覆盖数**：如果某个字段的覆盖数远小于总点数，说明有一半的点没这个字段——**用这个字段过滤会把它们全滤掉**（答卷 RAG09 的坑）。

**对应答卷**：RAG04、RAG05、RAG08、RAG09。

---

## Lab 2 · 延迟分解（`latency`）

**看什么**：把一次 RAG 请求拆成「本地向量化」和「服务端检索」两段，分别量；再量 K 值、payload、hnsw_ef 对延迟的影响。

**关键观察**：
- 单条向量化是毫秒级，检索是百毫秒级 → **别把优化预算花在向量化上**。
- K 从 5 到 50，延迟只涨 20 ms 左右 → **topK 不是延迟瓶颈**。
- `hnsw_ef` 从 32 到 512 只差 11 ms → **这个规模下调 ef 没意义**。

**⚠️ 一个反直觉的观测**：带 payload 反而比不带 payload **快**（114 ms vs 162 ms，p50）。这不是我们的结论，是远端部署下不同返回路径的调度差异；能确定的是**单次 top20 检索的 payload 体积不是瓶颈**（真正的大头见 Lab 3 的 scroll）。

**对应答卷**：RAG07、RAG19。

---

## Lab 3 · payload 过滤的代价（`filter`）

**看什么**：同样的查询，只改过滤条件，延迟差多少。

**关键观察**：
- 过滤字段**建了索引** → 与不过滤基本持平（115 ms vs 117 ms），过滤不花钱。
- 过滤字段**没建索引** → 1,456 ms，**12.5 倍**。服务端被迫扫全量点做过滤，HNSW 完全失效。
- 脚本最后会打印「过滤前后 top10 重合：5/10」——**过滤不是排序完再筛，是换了候选集**，过滤条件写错会直接改变结果而不是少几条。

**对应答卷**：RAG08、RAG09、RAG10。

---

## Lab 4 · 相似度阈值标定（`threshold`）

**看什么**：命中域（库里肯定有）和落空域（库里肯定没有）两类查询的 top1 分数分布。

**关键观察**：
- 命中域 top1 落在 0.6913 ~ 0.8052，落空域落在 0.4902 ~ 0.6126，**两组不重叠**。
- 阈值取两组之间的空隙，我们定 **0.65**。
- 最有意思的一条：落空域的「明天股市会涨吗」拿到 0.6126——因为库里真的有「预测股票市场」的章节。**语料里有的东西模型不作弊**，这说明阈值必须跟着语料走。
- 落空域的 top1 经常是**乱码块**（PDF 抽取失败的那种），这印证了 RAG18 的观点：脏数据在向量空间里不是噪声。

**⚠️ 这只是 13 条样本的工程起点**，正式上线要用几百条标注样本扫 ROC。

**对应答卷**：RAG14、RAG18、RAG22。

---

## Lab 5 · 幂等写入与写前查重（`dedup`）

**看什么**：确定性 id 怎么保证幂等，以及 0.95 的查重阈值凭什么安全。

**关键观察**：
- 同一段原文重新向量化再检索，**score 恰好 1.0000**——这只有在「向量归一化 + Cosine」下才成立（内积会因为范数变化而 ≠1）。
- **次相似只有 0.7508 ~ 0.7867**，离 0.95 差得远 → 0.95 既能挡住重复，又不会误杀语义相近的不同段落。

**配套要记住的现实**：我们的批量导入管道**没有**全局去重——全库 136,126 点只有 120,961 份唯一正文，6,295 组重复、21,460 个点（15.8%）落在重复组里，一次 top20 召回能被重复内容占掉 2 个名额。**写入层幂等只能防新增，防不了历史脏数据**，召回后还得按 content 哈希去重。

**对应答卷**：RAG15、RAG17。

---

## 体检脚本（不进答卷，运维自查用）

下面这段是一次性体检，用来查「库里有多少重复正文、召回里被占了多少名额」：

```python
import hashlib, collections
from qdrant_client import QdrantClient

cl = QdrantClient(url=HOST, api_key=TOKEN)
cnt = collections.Counter()
off = None
while True:
    pts, off = cl.scroll(COLLECTION, limit=8192, offset=off,
                          with_payload=True, with_vectors=False)
    for p in pts:
        cnt[hashlib.md5(p.payload.get("content", "").encode()).hexdigest()] += 1
    if off is None or not pts:
        break
dup = [v for v in cnt.values() if v > 1]
print("唯一正文 %d / 重复组 %d / 落在重复组的点 %d (%.1f%%)"
      % (len(cnt), len(dup), sum(dup), 100 * sum(dup) / sum(cnt.values())))
```

---

## 相关文档

- 答卷：[interview-questions.md](./interview-questions.md)（24 题，四段式 + 我们的实践）
- 专题总览：[README.md](./README.md)
- 主专题：[AI 面试真题答卷](../AI面试真题答卷-v1.md)
- 交互图：[RAG 完整链路](../diagrams/ai02-rag-pipeline.html)
