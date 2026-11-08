# Harness Engineering：把模型关进笼子

> 面试定位：蚂蚁财富 Java AI 岗 JD 第 2/6/7 条
> ——「理解 Harness Engineering 的核心思想」「对系统质量、稳定性、成本和安全负责」
> ——「推动研发流程向智能化、自动化、无人化交付演进」
>
> 对应代码：`java-ai-labs/src/main/java/edu/zjnu/interview/ai/harness/**`
> 对应真实模型适配：`java-ai-labs/src/main/java/edu/zjnu/interview/ai/core/model/**`
>
> **全程离线可跑**：没有 API Key、没有外网也能完整执行与回归（见文末「离线可跑」）。

---

## 0. 一句话定义

**Harness Engineering = 给模型套上一层「运行时治理系统」**，让模型从一个不可控的概率函数，
变成一个**能力有边界、行为可审计、成本可约束、失败可兜底**的工程组件。

一句话讲给面试官：

> 「模型本身不可靠，但它的<b>运行环境</b>可以做到可靠。
> Harness 就是这层运行环境：把不可信输入关在外面，把成本关在笼子里，
> 把每一次调用都变成可观测、可回归、可复现的工程行为。」

和普通「调 API + 写 Prompt」的区别：

| | Demo 做法 | Harness 做法 |
|---|---|---|
| 模型失败 | 抛 500 给用户 | 分类 → 重试 → 降级 → 熔断 → 结构化错误回灌给模型 |
| 成本 | 事后看账单 | 调用路径上实时计量 + 软阈值降级 + 硬阈值拒绝 |
| 工具权限 | 模型想调啥就调啥 | 白名单 + 读写分离 + 审批 + 路径沙箱 |
| 提示注入 | 靠模型自觉 | 结构性隔离 + 风险分级 + 处置建议 |
| 出问题 | 看日志猜 | span 树 + 成本报表 + JSONL 回放 + 运行清单 |
| 代码质量 | 「看着对」 | 真实编译 + 执行断言 + **变异检验**（测试抓不住缺陷 = 测试无效） |

---

## 1. 核心思想：六个笼子

```
                       ┌──────────── 用户 / 检索 / 工具结果（不可信输入）────────────┐
                       │  PromptInjectionGuard  →  风险分级  →  标记为不可信引用 / 降级 / 拒绝 │
                       └───────────────────────────────────────────────────────────────┘
                                          │ （可信上下文）
   ┌──────────────────────────────────────▼──────────────────────────────────────┐
   │                                 HARNESS 运行时                                │
   │  ① 能力边界：ToolSpec 声明参数 Schema，模型只能通过工具触达世界                    │
   │  ② 权限    ：PermissionPolicy 白名单 / 读写分离 / 审批 / PathSandbox            │
   │  ③ 预算    ：CostMeter 实时计量 → BudgetPolicy 软阈值降级 / 硬阈值拒绝            │
   │  ④ 可观测  ：Tracer span 树 → JsonlTraceSink → TraceReport（可回放）             │
   │  ⑤ 降级    ：RetryPolicy → FallbackChatModel → CircuitBreaker                 │
   │  ⑥ 安全    ：SecretRedactor 脱敏 / PathSandbox 路径 / PromptInjectionGuard     │
   └──────────────────────────────────────┬──────────────────────────────────────┘
                                          │ （结构化结果）
                                    Agent Loop / 应用代码
```

### ① 能力边界：模型只能通过工具触达世界

模型不是「什么都干」，它只能调用**被显式声明**的工具。
`core.ToolSpec` 是模型与运行时之间的契约，它同时承担两个职责：

1. **喂给模型**：参数声明转成 JSON Schema（`core.model.OpenAiCompatibleChatModel#encodeTools`）；
2. **约束运行时**：副作用等级（`READ_ONLY / WRITE / NETWORK`）供权限策略裁决。

> **为什么要一份声明两处复用**：如果「模型以为的参数」和「代码真正接受的参数」是两套，
> 迟早漂移。漂移的后果不是编译错误，而是**模型传了个不存在的参数，代码默默用默认值继续跑**——
> 这种错误在生产上极难发现。

### ② 权限：四道关卡，按「先便宜后昂贵」排序

`harness.PermissionPolicy implements core.ToolAuthorizer`，放行返回 `null`，拒绝返回**理由**：

| 顺序 | 关卡 | 规则 | 默认值 |
|---|---|---|---|
| 1 | 白名单 | 工具未注册 / 不在白名单 → 拒绝 | **拒绝**（未知 = 未授权） |
| 2 | 读写分离 | `WRITE` 必须显式授予写权限 | **拒绝** |
| 3 | 审批 | `NETWORK` 或 `requiresApproval` → 走 `ApprovalHandler` | **拒绝**（拿不到批准就不执行） |
| 4 | 路径沙箱 | 参数里的路径必须落在沙箱内 | **拒绝**（校验不了就拒绝） |

> **为什么拒绝理由必须回灌给模型**（这是 `core.ToolAuthorizer` 的 SPI 约定）：
> 模型拿到「读操作被拒」才会换一条路；拿到空指针或 500，它只会重试或胡说八道。
> 这就是把「业务约束」翻译成「模型可理解的信息」——Harness 的本质工作之一。

`PathSandbox` 有**两道防线**：

- **词法**：`base.resolve(candidate).normalize()` 后判断是否仍在 base 之下
  （必须先 resolve 再 normalize，否则 `a/../../etc` 会绕过）；
- **物理**：取目标路径的 `toRealPath()` 再判断一次，挡住 `root/link -> /etc` 这类符号链接逃逸。

### ③ 预算：软阈值降级，硬阈值拒绝

```
        预算上限 $0.01
   ├──────────────┬──────────────┤
   0%            80%            100%
   │   ALLOW      │  DEGRADE     │  REJECT
   │  正常调用     │ 换便宜模型     │ 返回结构化错误
   │              │ （业务不中断）  │ （杜绝无预算烧钱）
```

- **计量发生在调用路径上**（`harness.CostMeter implements core.UsageMeter`），
  不是事后从日志聚合——事后聚合只能「追责」，不能「拦截」。
- **按模型分组**（`harness.CostReport`）：降级链路上「先贵后便宜」两次调用都要记账，
  否则算不出「降级省了多少钱」。
- **输入/输出分开计价**：输出单价通常是输入的 3~10 倍，混算会让「少输出」这个优化手段失效。
- **只有一档阈值两头都糟**：设高了来不及降级，设低了频繁拒绝。所以分软硬两档。
- 作用域有两种：**会话级**（防 Agent 循环转 50 轮烧光预算）+ **请求级**（防单次超长上下文一击穿）。

### ④ 可观测：span 树 + 可回放

`harness.Tracer` 负责：生成 `traceId`、构造父子 span、计时、把 `spanId` 传给下游。

几个关键设计：

- **spanId 提前分配**：`core.Span` 的默认 builder 用「name + start + 属性数」算 id，
  同名同起点的兄弟 span 可能撞 id；`Tracer` 用自增序号参与哈希，保证 trace 内绝不重复。
- **异常路径也落 span**（try-with-resources）：如果只在成功路径上报，**错误率永远是 0**。
- **耗时用 `nanoTime`、落库用 `currentTimeMillis`**：前者是单调时钟（不受系统时间调整影响），
  后者便于和日志对齐。
- **`ThreadLocal` 维护父子栈**：并发扇出时各线程互不串扰。
- **JSONL 而非 JSON 数组**：写是 O(1) 追加，进程被 kill 最多丢最后一行；
  读可以流式过滤；天然兼容 Fluentd / Loki。
- **回放（replay）**：一次线上排障 = 把现场 trace 文件拷回本地离线重放、聚合、算成本，
  **不需要连数据库复现故障**。

`TraceReport` 汇总「每类 span 的调用次数 / 平均耗时 / 错误率 / token 与成本」。
注意它**按 span 名分组**而不是只按 kind：`llm.chat` 和 `llm.embed` 同属 llm，
但成本量级完全不同，只按 kind 聚合会把「嵌入调用太贵」稀释掉。

### ⑤ 降级：重试 / 降级 / 熔断三件套

这是面试最容易混淆、也最容易加分的一组：

| | 解决什么问题 | 前提 | 本工程类 |
|---|---|---|---|
| **重试 Retry** | 偶发抖动 | 同一个模型可能只是抖了一下 | `harness.RetryPolicy` + `harness.RetryingChatModel` |
| **降级 Fallback** | 模型本身出问题 | 限流 / 配额 / 区域故障，需要换一个能力不同的模型 | `harness.FallbackChatModel` |
| **熔断 CircuitBreaker** | 下游持续不可用 | 重试会放大故障，必须快速失败 | `harness.CircuitBreaker` |

> **为什么重试不够、必须加熔断**：重试能扛住偶发抖动，但扛不住「服务持续不可用」。
> 供应商挂掉时，重试策略让每个请求都重试 3 次——流量没变，**后端压力翻了 3 倍**，
> 故障被放大而不是缓解。熔断的价值是：发现某下游持续失败时**直接快速失败**，
> 把资源留给还能成功的下游。

工程细节：

- **退避**：指数退避 + **减法式抖动**（`base - jitterRatio * base * random`）。
  用减法而不是加法，是为了让最大等待时间始终有上界；抖动把重试时刻打散，避免「惊群」。
- **错误分类**：网络 / 超时 / 限流 / 5xx 可重试；**4xx 不可重试**（参数错、鉴权错，重试只是浪费配额）；
  **未知默认不重试**——「不确定是否可重试」时，保守比激进安全。
- **可测试性**：`Sleeper` 可注入 → 测试里**不 sleep**，退避序列可以被精确断言。
  熔断器的时钟也可注入 → 冷却时间能瞬间推进，状态机测试完全确定性。
- **熔断三态**：`CLOSED → OPEN → HALF_OPEN → CLOSED`。
  半开期**只放一个**试探请求（不是放量），试探失败立刻重新打开，避免状态来回抖动（flapping）。

### ⑥ 安全：脱敏 + 路径 + 注入

- **`SecretRedactor`**：api-key / token / 密码 / 身份证 / 手机号 / 银行卡 / JWT / 厂商专用 key 形态。
  **规则顺序有讲究**：先脱「带上下文的键值对」和「厂商 key 格式」，
  再脱身份证 / 手机号 / 银行卡。反过来会让 18 位身份证被银行卡规则先吃掉，掩盖「这是身份证」这个事实。
  定位是**统一出口的最后一道闸**，而不是让每个业务代码记得调用。
- **`PromptInjectionGuard`**：四级风险（NONE / LOW / MEDIUM / HIGH / CRITICAL）
  × 四种处置（ALLOW / MARK_UNTRUSTED / DEGRADE / REJECT）。
  正则**不可能覆盖所有注入写法**（多语言、编码绕过、语义改写），
  所以它的定位是「**挡住 90% 的低成本攻击 + 为剩下的部分提供可观测信号**」，
  而不是「保证安全」。真正的安全边界是**结构性隔离**：
  不可信内容永远待在数据区（`PromptInjectionGuard.wrapUntrusted`），系统指令区只放系统自己写的文本。

---

## 2. 类地图

```
edu.zjnu.interview.ai.harness
├── 可观测
│   ├── Tracer                 span 树 / 计时 / 当前 spanId 下传
│   ├── JsonlTraceSink         JSONL 落盘 + replay
│   ├── InMemoryTraceSink      内存 sink（测试断言）
│   ├── TraceReport            次数 / 平均耗时 / 错误率 / token / 成本
│   └── SpanAttrs              span 属性键常量（聚合的前提）
├── 成本与预算
│   ├── ModelPricing           模型名 → 输入/输出每百万 token 单价
│   ├── CostMeter              implements UsageMeter（精确计费 + 按模型分组）
│   ├── CostReport             成本明细表
│   ├── BudgetPolicy           软阈值降级 / 硬阈值拒绝（会话级 + 请求级）
│   ├── BudgetDecision         三态判定结果 + 结构化错误载荷
│   ├── BudgetAwareChatModel   把预算插到调用路径上
│   └── DegradationEvent       降级事件（可统计、可回灌）
├── 权限与安全
│   ├── PermissionPolicy       implements ToolAuthorizer（白名单/读写/审批/沙箱）
│   ├── ApprovalHandler        审批回调（默认拒绝）
│   ├── PathSandbox            canonical path + 符号链接防逃逸
│   ├── SecretRedactor         日志与工具结果脱敏
│   └── PromptInjectionGuard   注入检测 + 风险分级 + 处置建议
├── 稳定性
│   ├── RetryPolicy            错误分类 + 指数退避 + 抖动
│   ├── Sleeper                可注入等待（测试不 sleep）
│   ├── FailureClassifier      异常/响应 → 失败分类
│   ├── ModelCallException     带失败分类的异常
│   ├── RetryingChatModel      对错误响应按策略重试
│   ├── FallbackChatModel      主模型失败 → 备选模型（降级事件落 span）
│   └── CircuitBreaker         CLOSED / OPEN / HALF_OPEN
├── 可复现
│   └── RunManifest            模型 / 配置 / 指纹 / 随机种子 → JSON
├── coding（AI Coding 交付）
│   ├── CodingDeliveryPipeline 需求→方案→代码→测试→编译执行→变异检验→报告
│   ├── CodeGenerator          可替换的生成器接口
│   ├── TemplateCodeGenerator  确定性模板（离线 / 兜底）
│   ├── ModelCodeGenerator     真实模型生成（模型只写，验证交给流水线）
│   ├── SourceMutator          自动变异算子（边界条件右移/左移）
│   ├── Requirement / CodeArtifact
└── lab
    └── HarnessLab             一次跑通全部能力的可执行说明书

edu.zjnu.interview.ai.core.model
├── OpenAiCompatibleChatModel  JDK HttpClient / OpenAI 兼容协议 / tool calling
├── StubChatModelServer        本地 stub 网关（离线集成测试）
└── EmbeddingClient            /embeddings 端点（L2 归一化）
```

---

## 3. 一次请求的完整生命周期

```java
// 1) 装配：所有「关笼子」的组件都是装饰器，可任意组合
Tracer tracer      = new Tracer(jsonlTraceSink);
CostMeter meter    = new CostMeter(pricing);
BudgetPolicy budget= BudgetPolicy.builder()
        .scope(SESSION).usdLimit(0.01).softRatio(0.8)
        .degradedModel("qwen-turbo").sessionMeter(meter).build();

ChatModel model = new BudgetAwareChatModel(      // 预算闸门
        new RetryingChatModel(                   // 重试
            new FallbackChatModel(               // 降级
                openAiCompatibleChatModel,        // 真实模型
                fallbackModel),
            retryPolicy),
        cheapModel, budget, meter, tracer);

// 2) 业务代码只管调用
ChatResponse response = model.chat(request);

// 3) 事后：可观测 + 可回放 + 可复现
TraceReport report = TraceReport.fromJsonl(traceFile);
RunManifest manifest = RunManifest.builder().model(...).fingerprintOf("code", codeDir).build();
```

**注意装饰器的顺序**：重试在降级**之内**——先在主模型上做有限重试，
重试仍失败才降级到备模型。顺序反了会让「主模型只是抖一下」也付出「换模型」的质量代价。

---

## 4. AI Coding 交付流水线：怎么「判断和验证生成代码质量」

这是 JD 第 2/7 条（「借助 AI Coding 快速上手」「具备判断和验证生成代码质量的能力」）
最直接的落地。`harness.coding.CodingDeliveryPipeline` 的七个阶段：

```
需求解析 → 方案要点 → 代码骨架 → 测试用例 → 本地验证 → 变异检验 → 交付报告
```

**「本地验证」是真编译、真执行**：

- 用 `javax.tools.JavaCompiler` **真实编译**生成的 `.java` 文件（语法错、类型错、缺依赖都会暴露）；
- 在**隔离类加载器**里执行生成的测试（父加载器是 `ClassLoader.getPlatformClassLoader()`，
  不含应用 classpath —— 生成的代码无法偷偷引用工程里的类，验证环境可预测）；
- 逐条拿到用例结果（PASS / FAIL）。

**「变异检验」回答的是「你的测试到底有没有用」**：

> AI 生成代码最常见的自欺是「我给它写了测试，测试也过了」——
> 但一个**永远会通过**的测试同样能「通过」。
>
> 唯一可靠的判断办法：**往实现里注入一个缺陷，看测试能不能抓住它**。
> 这就是变异测试（mutation testing）的思想。

本工程的做法是：生成器同时产出「正常实现」和「变异体」，
变异算子刻意选择**人眼很难发现**的改动（`<` → `<=`、`>=` → `>`，即**边界右移/左移**），
而不是把 `+` 写成 `-` 那种一眼可见的错误。
流水线断言：**正常实现 → 测试全过；变异实现 → 测试必须挂**。

**兜底策略（无人化交付的关键）**：如果生成器产出的代码编译不过，
且配置了兜底生成器，流水线自动改用兜底方案重试一次，并在报告里明确记录「发生过自动修复」。

> 无人化交付的核心不是「一次生成就对」，而是
> **「生成 → 验证 → 失败 → 自动兜底 → 再验证」这条闭环永远能收敛**。

---

## 5. 与 Spring AI / LangChain4j 的对照

> 面试用法：先讲框架（我用过 Spring AI / LangChain4j），再讲框架底下发生了什么
> （Advisor 其实就是一组装饰器，ObservationRegistry 其实就是 span 树，框架没做的是**预算与权限**）。

| 能力 | Spring AI | LangChain4j | 本工程（java-ai-labs） |
|---|---|---|---|
| 模型调用入口 | `ChatClient` / `ChatModel` | `AiServices` / `ChatModel` | `core.ChatModel`（端口）+ `core.model.OpenAiCompatibleChatModel`（适配器） |
| 多厂商接入 | `OpenAiApi` / `AnthropicApi` 等 starter | `ChatModel` 适配器包 | 一个 `OpenAiCompatibleChatModel` 覆盖 Qwen / DeepSeek / OpenAI / 内部网关（协议兼容） |
| 工具声明 | `@Tool` / `ToolCallback` / `MethodToolCallback` | `ToolSpecification` | `core.ToolSpec`（一份声明两处复用：喂模型 + 做入参校验） |
| 工具执行 / 结果回灌 | `ToolCallingChatManager` | `AiServices` 内部循环 | `core.ChatResponse.toolCalls` + `core.ToolResult` → `ChatMessage.toolResult(...)` |
| 记忆 / 上下文 | `ChatMemory` / `MessageWindowChatMemory` | `ChatMemory` | `core.ChatMessage` 列表 + `core.PromptBlocks`（可信指令区 / 不可信资料区分离） |
| **拦截器 / 切面** | **`Advisor`（`CallAdvisor` / `StreamAdvisor`）** | 无统一等价物（多为 `AiServices` 内部钩子） | **装饰器链**：`Tracer` / `BudgetAwareChatModel` / `RetryingChatModel` / `FallbackChatModel` / `CircuitBreaker` |
| **可观测** | **`ObservationRegistry` / `ChatObservationContext` / Micrometer metrics** | LangChain4j Observability（实验性） | `harness.Tracer` + `core.Span` + `JsonlTraceSink` + `TraceReport`（**可回放**） |
| **成本计量** | Observation 里能拿到 usage，但**没有预算闸门** | 有 token usage 统计，无预算闸门 | `CostMeter`（`implements UsageMeter`）+ `BudgetPolicy`（**软降级 / 硬拒绝**） |
| **权限 / 安全** | 需自行实现 | 需自行实现 | `PermissionPolicy`（`implements ToolAuthorizer`）+ `PathSandbox` + `SecretRedactor` + `PromptInjectionGuard` |
| **故障治理** | 需自行实现（可借 Resilience4j） | 需自行实现 | `RetryPolicy` / `FallbackChatModel` / `CircuitBreaker`（**错误分类内置**） |
| **提示注入防护** | 需自行实现 | 需自行实现 | `PromptInjectionGuard`（风险分级 + 处置建议） |
| **可复现** | 需自行实现 | 需自行实现 | `RunManifest`（模型 / 配置 / 指纹 / 随机种子） |
| **AI Coding 闭环** | 无 | 无 | `harness.coding.CodingDeliveryPipeline`（真实编译 + 执行 + 变异检验） |

**一句话总结差异**：

> Spring AI 的 `Advisor` 就是「装饰器链」这个模式的框架化表达；
> `ObservationRegistry` 就是「span 树」的框架化表达。
> 但框架解决的是「**怎么把模型接进来**」，
> 而 Harness 解决的是「**怎么让模型不敢乱来、出错不炸、花钱有上限**」。
> 后者恰恰是生产环境真正会出事的地方，也是这块工程最值得讲的部分。

---

## 6. 离线可跑

| 能力 | 有 API Key | 无 API Key（默认） |
|---|---|---|
| 对话链路 | `OpenAiCompatibleChatModel` | `core.mock.MockChatModel` / `ExtractiveChatModel` |
| 向量链路 | `core.model.EmbeddingClient` | `core.mock.HashEmbeddingModel` |
| 协议适配器集成测试 | — | `core.model.StubChatModelServer`（本地起 HTTP 服务，断言请求体 + 响应解析） |
| 代码生成 | `ModelCodeGenerator` | `TemplateCodeGenerator`（确定性模板） |

配置来源统一是 `core.LabEnv`：`OPENAI_BASE_URL` / `OPENAI_API_KEY` / `OPENAI_MODEL` / `OPENAI_TIMEOUT_MS`。
**没有这些配置时整条链路照常运行**——真实模型是「可选增强」，不是「运行前提」。

运行演示：

```bash
mvn -q -pl java-ai-labs compile exec:java \
    -Dexec.mainClass=edu.zjnu.interview.ai.harness.lab.HarnessLab
```

`HarnessLab` 会依次跑完 9 个场景：trace + 成本 → 预算降级 → 预算拒绝 → 工具越权拒绝 →
脱敏 → 注入检测 → 重试 / 熔断 → AI Coding 交付 → 运行清单。

---

## 7. 面试问答准备

**Q：这套 Harness 里最容易被追问的设计点是什么？**
→ 见交付说明里的「最容易被追问的设计点」一节（软阈值 vs 硬阈值的取舍、
以及「降级要降到什么程度」的权衡）。

**Q：为什么不用现成的 Resilience4j / Sentinel？**
→ Resilience4j 解决「重试 + 熔断」，但**不解决预算与权限**；
> 而预算恰恰是 AI 系统特有的（token 成本可量化、会爆炸式增长）。
> 本工程的价值是把「重试/熔断」和「预算/权限/脱敏/注入」放在同一套可观测体系里——
> 所有降级、拒绝、越权都会落成同一种 span，能用同一张报表回答「稳定性、成本、安全」三类问题。

**Q：PromptInjectionGuard 用正则，能防住注入吗？**
→ 防不住全部，也不敢这么宣称。它的定位是「**挡住 90% 的低成本攻击 + 为剩下的部分提供信号**」：
> 命中会落 span，可以把异常样本回捞成评测集。
> 真正的安全边界是**结构性隔离**（系统指令区 vs 不可信数据区），
> 以及**权限层兜底**（即使模型被说服了，路径沙箱和网络审批也会挡住它）。

**Q：怎么证明生成的代码质量是可靠的？**
→ 三个证据：① 真实编译（`javax.tools.JavaCompiler`）；② 在隔离类加载器里执行断言；
> ③ **变异检验**——注入缺陷后测试必须失败。
> 只有 ①② 的证据链是不完整的：一个永远会通过的测试也能通过。

**Q：成本怎么算得准？**
→ 输入/输出分开计价（`ModelPricing`），按模型分组（`CostReport`），
> 在调用路径上计量（`CostMeter`）而不是事后聚合；
> 同时承认 `TokenCounter` 是**估算**，所以预算层留了余量而不是把估算当精确值用。
