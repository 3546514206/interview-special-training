# java-ai-labs · Java AI 工程实验模块

> 目标：把「蚂蚁财富 Java AI 岗」JD 里的每一条能力要求，变成**可运行、可断言、可回归**的 Java 代码。
> JDK 17 + Maven，唯一运行时依赖是 Jackson（其余全部基于 JDK 标准库实现）。

## 为什么要这样设计

面试里最容易被追问的从来不是「你用过哪个框架」，而是**「框架底下发生了什么，你能不能自己搭出来」**。
所以这个模块刻意做了三件事：

1. **端口-适配器（Ports & Adapters）**：所有外部依赖都抽象成 `core` 包里的接口
   （`ChatModel` / `EmbeddingModel` / `VectorStore` / `KnowledgeBase` / `Tool` / `TraceSink` / `UsageMeter`）。
   业务代码只依赖接口，具体实现是适配器——换模型、换向量库、换存储都不动业务。
2. **离线优先（Offline-First）**：没有模型 API Key、没有外网，整条链路照样跑通、照样有断言。
   Mock 实现是确定性的，评测结果可复现；真实依赖（Qdrant、真实模型）作为**可选增强**接入。
3. **可观测性内置**：Trace、token 计量、成本核算不是「上线后再补」的东西，
   而是在第一次调用模型时就接进链路（`Tracer` / `CostMeter` / `Span`）。

## 目录结构

```
java-ai-labs/
├── pom.xml
├── .env / .env.example              # Qdrant 凭据等本机配置（.env 不入库）
├── src/main/java/edu/zjnu/interview/ai/
│   ├── core/                        # 公共 SPI（端口 + 工具类 + Mock 实现）
│   │   ├── ChatMessage / ChatRequest / ChatResponse / ChatModel / EmbeddingModel
│   │   ├── Tool / ToolSpec / ToolResult / ToolContext / ToolAuthorizer
│   │   ├── KnowledgeBase / VectorStore / Span / TraceSink / UsageMeter
│   │   ├── PromptBlocks / Text / TokenCounter / Ids / Json / LabEnv
│   │   ├── mock/                    # MockChatModel / HashEmbeddingModel / ExtractiveChatModel
│   │   └── model/                   # OpenAI 兼容协议的真实模型适配器（JDK HttpClient）
│   ├── rag/                         # RAG 六段流水线
│   ├── agent/                       # Agent Loop、状态与记忆、工作流编排、多 Agent
│   └── harness/                     # Harness 工程 + AI Coding 交付流水线
├── src/main/resources/
│   ├── corpus/                      # 中文知识语料
│   └── golden/                      # 黄金评测集
└── src/test/java/...                # JUnit 5 回归测试
```

## 三个实验方向

| 方向 | 包 | 对应 JD 条款 | 关键产出 |
|---|---|---|---|
| **RAG 全链路** | `rag` | 第 4 条：知识接入、索引构建、召回、重排、上下文组装、效果评测 | 六段流水线 + 可解释重排分数 + Recall@k/MRR/nDCG 评测与配置对比 |
| **Agent 运行时** | `agent` | 第 5 条：工具调用、状态与记忆、权限控制、工作流编排、Agent 运行时、多 Agent 协作 | Agent Loop 状态机 + 三类记忆 + 沙箱工具 + 工作流 + Supervisor 委派 |
| **Harness 工程** | `harness` | 第 2/6/7 条：可观测、成本、安全、稳定性、AI Coding 无人化交付 | span 树与成本报告、预算降级/熔断、权限与注入防护、代码生成→编译→断言闭环 |

## 常用命令

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home

# 跑全部单元测试（回归门禁）
mvn -f java-ai-labs/pom.xml -B clean test

# 跑全套实验报告
mvn -q -pl java-ai-labs compile exec:java -Dexec.mainClass=edu.zjnu.interview.ai.cli.AiLabCli

# 只跑某个方向
mvn -q -pl java-ai-labs compile exec:java -Dexec.mainClass=edu.zjnu.interview.ai.rag.lab.RagLab
mvn -q -pl java-ai-labs compile exec:java -Dexec.mainClass=edu.zjnu.interview.ai.agent.lab.AgentLab
mvn -q -pl java-ai-labs compile exec:java -Dexec.mainClass=edu.zjnu.interview.ai.harness.lab.HarnessLab
```

## 向量库：Qdrant

实验连接腾讯云 91 实例上的 Qdrant（1.18.1），走 **JDK HttpClient + Qdrant REST v1**，
不引入 gRPC/protobuf 依赖：

- 集合：`java_ai_labs_knowledge`（**实验专用、独立集合**，与机器上已有的 `common_collection` 完全隔离）；
- 维度：**256**，距离度量 `Cosine`，HNSW 索引，payload 索引建在 `docId` / `source` / `category` 上；
- 凭据：`.env` 里的 `QDRANT_BASE_URL` / `QDRANT_TOKEN`（`.env` 已被仓库根 `.gitignore` 忽略）；
- 维度与 `EmbeddingModel.dimensions()` **强绑定**：换 embedding 模型必须重建集合，
  否则会出现线上最难查的「维度不匹配」事故——这是 RAG 索引工程里最值得讲的一个坑。

没有配 `QDRANT_BASE_URL` 时，检索自动退回内存实现，实验照常可跑。

## 与主流框架的对照

| 本工程 | Spring AI | LangChain4j | 说明 |
|---|---|---|---|
| `ChatModel` / `core.model.OpenAiCompatibleChatModel` | `ChatModel` / `ChatClient` | `ChatModel` / `AiServices` | 模型端口 |
| `Tool` / `ToolSpec` | `ToolCallback` / `@Tool` | `@Tool` | 工具契约与参数 schema |
| `ChatRequest` / `ChatMessage` | `Prompt` / `Message` | `ChatMessage` | 对话上下文 |
| `VectorStore` / `QdrantVectorStore` | `VectorStore`（spring-ai-qdrant） | `EmbeddingStore` | 向量存储 |
| `KnowledgeBase` / `rag` 包 | `RetrievalAugmentationAdvisor` / `QuestionAnswerAdvisor` | `EmbeddingStoreContentRetriever` | 检索增强 |
| `ConversationState` / `MemoryStore` | `ChatMemory` | `ChatMemory` | 会话记忆 |
| `Span` / `TraceSink` / `Tracer` | `ObservationRegistry` | — | 可观测 |
| `BudgetPolicy` / `CostMeter` | 无内建（需自建） | 无内建（需自建） | 成本治理 |

> 结论：**框架能省掉的是胶水代码，省不掉的是工程判断**。
> 本模块的意义就是把「框架底下」那层显式写出来一遍。
