# RAG 面试子专题（Qdrant 落地版）

> **目标**：把 RAG 这一块**讲成自己的项目**，而不是背概念。
>
> **做法**：不讲向量数据库选型（Milvus / ES / PGVector 都不比）——线上就一套 Qdrant，所以每个问题都按「我们这套库上真实跑出来的数」来答。答卷里每个结论都带 **【我们的实践】** 段，数字全部来自自建知识库 `common_collection`（13.6 万 chunk）的实测。
>
> **配套实验**：[`lab/qdrant_lab.py`](./lab/qdrant_lab.py) —— 5 个实验，答卷里引的每个数字都能复现（只读，不写库）。

## 文件导航

| 文件 | 内容 |
|---|---|
| [interview-questions.md](./interview-questions.md) | **24 题答卷**（RAG01–RAG24）：一句话答 / 展开 / 我们的实践 / 追问 / 易错 + 面试速记 10 条 |
| [lab-guide.md](./lab-guide.md) | 实验指南：5 个实验看什么、关键观察、对应哪道题；附一次性体检脚本 |
| [lab/qdrant_lab.py](./lab/qdrant_lab.py) | 可运行脚本（只读），`python qdrant_lab.py profile\|latency\|filter\|threshold\|dedup` |
| [../diagrams/ai02-rag-pipeline.html](../diagrams/ai02-rag-pipeline.html) | 已有交互图：RAG 完整链路（离线索引 / 在线查询 / Agentic 三模式可切换） |

## 一句话记住这个专题

> **RAG 的效果瓶颈不在模型，在解析、切分、召回三处；召回的瓶颈不在向量库算力，在「过滤字段有没有建索引」和「有没有做去重」。**

## 我们的实践基线（2026-11-01 实测）

| 维度 | 实测值 |
|---|---|
| 集合 | `common_collection`，136,126 点 / 唯一正文 120,961 份 |
| 向量 | bge-m3 · 1024 维 · **Cosine** · 已归一化（本地 CPU 编码 ≈14 ms/条） |
| 索引 | HNSW（m=16 / ef_construct=100 / full_scan_threshold=10000，**全默认**）· **未量化** · status green |
| payload 索引 | 10 个 keyword 字段：`topic` `source` `quality` `difficulty` `use_case` `content_type` `family` `language` `tagging_method` `tagging_version` |
| 内容分布 | cs_books 92,347 / javabetter 41,832；主题：计算机通用 56,167、Java-JVM 17,430、数据库 7,634、AI-ML 3,135 |
| 标注成本 | 规则处理 121,160 条（89.0%），LLM 参与 14,828 条（**10.9%**） |
| 检索延迟 | top20 **p50 114 ms**；K 从 5→50 只涨 20 ms；`hnsw_ef` 32→512 只涨 11 ms |
| 过滤代价 | 有索引 115.7 ms（≈免费）· **无索引 1,456 ms（12.5x）** |
| 批量搬运 | scroll 5,000 点：带 payload 14,396 ms vs 不带 143 ms（**101x**） |
| 阈值标定 | 命中域 top1 ∈ [0.6913, 0.8052] · 落空域 top1 ∈ [0.4902, 0.6126] → **阈值 0.65** |
| 查重 | 同内容 score = **1.0000**，次相似 0.7508~0.7867 → **查重阈值 0.95** |
| 数据质量 | 重复正文 6,295 组 / 21,460 点（**15.8%**）；一次 top20 召回被重复占掉 2 个名额；PDF 乱码块会排到落空查询的 top1 |

> 复现方式见 [lab-guide.md](./lab-guide.md)。

## 一张图

```text
用户提问
   │
   ├─ ① 解析（离线） ─ 文档 → 纯文本 + 标题层级
   │      └─ 清洗：去页眉页脚 / 短块短路丢弃 / 乱码判定 → quality=good|ok|low
   │
   ├─ ② 切块（离线） ─ 按标题→段落→句子递归切，保留 10~20% 重叠
   │      └─ 每块带 h1/h2/h3 + doc_id + chunk_idx + source_url（引用坐标）
   │
   ├─ ③ 向量化（离线） ─ bge-m3 → 1024 维 → 归一化
   │
   ├─ ④ 入库（离线） ─ Qdrant upsert
   │      ├─ id = 确定性派生 → 同内容幂等
   │      ├─ payload 过滤字段 → 必须建索引（否则慢 12.5x）
   │      └─ 写前语义查重（0.95）→ 命中就跳过
   │
   └─ 在线检索 ────────────────────────────────────────────────
          问题向量化（14 ms）
              ↓
          Qdrant HNSW 召回 top20
              ├─ ① 元数据过滤（topic / difficulty / 权限）← 建了索引才不花钱
              ├─ ② 内容哈希去重（治 15.8% 历史重复）
              ├─ ③ 阈值闸门（top1 < 0.65 → 直接拒答，不进 LLM）
              └─ ④ 重排（当前：阈值+去重顶替；目标：cross-encoder）
              ↓
          拼 Prompt（K₂ = 3~5 条 + 引用编号）
              ↓
          LLM 生成 + 引用标注 + 编号合法性后置校验
```

## 五层速查矩阵

| 层 | 关键决策 | 我们的选择 | 依据 |
|---|---|---|---|
| **解析** | 规则优先还是 LLM 全量 | 规则 89.0%，LLM 10.9% | 成本 + 确定性 |
| **切块** | 块大小 / 重叠 / 层级 | 结构优先切 + 10~20% 重叠 + 存 h1/h2/h3 | 引用可溯源、召回可过滤 |
| **向量化** | 模型 / 维度 / 通道 | bge-m3 · 1024 维 · dense（**sparse 通道待接**） | 中文效果 + 留混合检索口子 |
| **检索** | 索引 / 过滤 / 阈值 / 去重 | HNSW 默认参数 + payload 索引 + 阈值 0.65 + 召回后去重 | 12.5x 与 15.8% 两组实测 |
| **生成** | K₂ / 引用 / 拒答 | K₂=3~5 + 编号引用 + 低于阈值直接拒答 | 幻觉靠闸门不靠 Prompt |

## 学习路线

| 阶段 | 主题 | 题号 | 实验 |
|---|---|---|---|
| 1 | 链路与切分：为什么要 RAG、怎么切、怎么选 embedding | RAG01–RAG05 | — |
| 2 | 检索底座：ANN / HNSW / payload 索引 | RAG06–RAG10 | Lab 1、Lab 3 |
| 3 | 召回质量：K、rerank、混合检索、阈值、去重、评测 | RAG11–RAG16 | Lab 4、Lab 5 |
| 4 | 生产工程：增量更新、解析质量、延迟、成本、引用、幻觉、Agentic、GraphRAG | RAG17–RAG24 | Lab 2 |

**阶段说明**：
1. 阶段 1 是地基：切分和 embedding 选型错了，后面全是白干。
2. 阶段 2 是本专题最值钱的一段——**payload 索引那 12.5 倍**是大多数团队没量过、也不知道的数字。
3. 阶段 3 的阈值标定（0.65）和去重（15.8%）是两件「自己动手才知道」的事，面试时最有说服力。
4. 阶段 4 的 GraphRAG 只在最后一题出现，因为**大多数场景不需要它**——知道什么时候不需要，比知道怎么做更重要。

## 短板与下一步（面试可以主动讲，显得真实）

| 现状 | 下一步 |
|---|---|
| 没有成体系的评测集（Recall@K / MRR 无基线） | 先人工标 100 条「问题 → 期望命中的 h2/h3」，建基线 |
| 纯 dense，没接 bge-m3 的 sparse 通道 | 换 FlagEmbedding 加载 `sparse_linear.pt`，做 dense+sparse 融合 |
| 没有独立 cross-encoder 重排 | 对 top50 做 cross-encoder，量化精度收益 |
| 批量导入没有全局去重（15.8% 重复） | 召回后按 content 哈希去重（止血）→ 导入侧幂等 id（治本） |
| 全量重建没有 alias 原子切换 | 影子集合 + alias 切换 |
| PDF 乱码块会污染落空查询的 top1 | 解析阶段加乱码检测（可打印字符比例）+ 召回后剔 low |
| 没有 query 向量缓存 | 高频 query 缓存向量与结果（带权限维度 key） |

## 相关文档

- 主专题：[AI 面试真题答卷](../AI面试真题答卷-v1.md)（Q21–Q26 的 RAG 概览视角）
- AI 专题总览：[../README.md](../README.md)
- 交互图：[RAG 完整链路](../diagrams/ai02-rag-pipeline.html) · [MCP 架构](../diagrams/ai04-mcp-architecture.html)
- 原始素材来源（知识库内）：`h1 = AI Agent 面试 288 题合集` → `h2 = 04、RAG知识库（40 题）`（40 道题，问法与本专题互补）
