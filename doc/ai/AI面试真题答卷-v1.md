# AI 面试真题答卷（v1）

> **目标岗位**：大厂 AI 应用开发 / AI Agent 开发 / 大模型应用工程师（P6/P7）
> **数据来源**：牛客「AI Agent 面试 Top50 必刷题」 + 八股精约 2828 条 2025-2026 年真实面试记录 + GitHub `agent-interview-hub` 高频新题
> **风格**：速记版——一句话答 + 标准答（3-6 个 bullet）+ 易错点。**不写追问**（冲刺背诵用）
> **配套交互图**：[diagrams/](./diagrams/) 下 4 张（ReAct / RAG / Multi-Agent / MCP），点开看动画
> **质量承诺**：每条事实点均能追溯到 Anthropic / OpenAI / LangChain / LangGraph 官方文档或源码；不确定的会标注"⚠️"

---

## 目录速查

| 章节 | 专题 | 题号 | 频次 |
|---|---|---|---|
| 一 | Agent 核心循环 | Q1-Q5 | ⭐⭐⭐⭐⭐ |
| 二 | Agent 记忆 / 上下文 | Q6-Q10 | ⭐⭐⭐⭐ |
| 三 | Tool 与 Function Calling | Q11-Q15 | ⭐⭐⭐⭐⭐ |
| 四 | Multi-Agent 协作 | Q16-Q20 | ⭐⭐⭐⭐ |
| 五 | RAG 完整链路 | Q21-Q26 | ⭐⭐⭐⭐⭐ |
| 六 | LLM 训练与对齐 | Q27-Q31 | ⭐⭐⭐ |
| 七 | MCP（Model Context Protocol） | Q32-Q34 | ⭐⭐⭐⭐ |
| 八 | 评估、安全与性能 | Q35-Q40 | ⭐⭐⭐⭐ |
| 九 | 工程落地与流式输出 | Q41-Q45 | ⭐⭐⭐ |
| 十 | Prompt 与实战 | Q46-Q50 | ⭐⭐⭐ |

---

## 一、Agent 核心循环（Q1-Q5）

### Q1 · 什么是 ReAct？（⭐⭐⭐⭐⭐）

**【一句话答】** ReAct = Reason + Act，LLM 在 Thought（思考）→ Action（结构化 JSON 工具调用）→ Observation（工具返回）三步之间循环，直到信息充足输出 Final Answer。

**【标准答】**
- **Thought**：LLM 内部推理，决定「要不要查 / 查什么 / 参数怎么填」，**不下发**给外部
- **Action**：受 JSON Schema 约束的**结构化工具调用**，不是自然语言
- **Observation**：工具真实返回结果，注入回 Prompt，作为下一轮 Thought 的输入
- **终止条件**：LLM 输出不含 Action（即决定不再调工具），输出 Final Answer
- **关键配对**：`Thought → Action` 显式配对（区别于 Chain-of-Thought），所以工具调用是有意识的、不是乱跳
- **Prompt 模板**：通常为 `Question: ...\nThought: ...\nAction: ...\nObservation: ...\n... (loop)\nThought: I now know the answer\nAction: Finish`

**【易错点】**
- ❌ "ReAct = CoT + 工具调用" → ✅ ReAct 的核心是 **Thought → Action 显式配对**，且 Observation **必须**回灌下一轮
- ❌ "ReAct 是 OpenAI 提出的" → ✅ ReAct 论文 2022 年 Yao et al. (Princeton)，OpenAI 把它工程化为 Function Calling

> 📊 配套交互图：[diagrams/ai01-react-loop.html](./diagrams/ai01-react-loop.html) — 订机票场景 3 轮迭代 + Function Calling JSON Schema

---

### Q2 · ReAct 与 Plan-and-Execute 的区别？（⭐⭐⭐⭐）

**【一句话答】** ReAct 是「边想边做、步步基于上一步 Observation」，Plan-and-Execute 是「先一次性规划完整步骤、然后机械执行」，后者省 token 但早期版本易幻觉。

**【标准答】**
- **ReAct**：每步 Thought 都依赖前一步 Observation，**适应性**强（工具报错可立即重试路径），但 token 多、慢
- **Plan-and-Execute**：Planner 一次性生成 `Step 1/2/3/...` 然后 Replacer 执行，**省 token、快**，但 Plan 错了后续全错（Plan → Replan 兜底）
- **混合方案**：Planner 生成大纲 → 每步内部走 ReAct → 验证器评估 → Replan（LangGraph 的标准做法）
- **适用**：ReAct 适合**多跳、不确定任务**（订机票要看实时价）；Plan-and-Execute 适合**步骤可枚举**的任务（写周报分 3 步）
- **工具消耗**：ReAct 通常 5-15 轮 LLM 调用；Plan-and-Execute 通常 2-4 轮

**【易错点】**
- ❌ "Plan-and-Execute 一定比 ReAct 好" → ✅ 没有 Observation 喂回 → 早期版本幻觉严重；现代用 Replan 修复但仍复杂
- ❌ "ReAct 不能用规划" → ✅ 实际项目多混合：ReAct 内部可嵌套 todo list

---

### Q3 · Agent 的 Planning 模块有哪些主流实现？（⭐⭐⭐⭐）

**【一句话答】** 三种主流：Step-by-Step（一次性分步）、Plan-and-Execute（规划后执行）、ReAct（边想边做无显式规划）；实际项目常用 ReAct + 内部 todo list 混合。

**【标准答】**
- **Step-by-Step**：CoT 风格，Prompt 里说「请先列出步骤再回答」，无外部执行器
- **Plan-and-Execute**：Planner Agent 一次性输出完整步骤列表 → Executor Agent 逐步执行 → Evaluator 判断 → 失败 Replan
- **ReAct**：无显式 Plan，Thought 即规划，每步重新评估
- **Tree-of-Thoughts**：维护一棵搜索树，DFS/BFS 探索多个分支，适合需要回溯的任务
- **Reflexion**：执行后 Self-Critic → 写入长期 memory → 下次避免同坑

**【易错点】**
- ❌ "ReAct 没有规划" → ✅ Thought 就是隐性规划，只是没显式列出
- ❌ "ToT = ReAct + 多分支" → ✅ ToT 需要专门的状态管理，复杂度高于 ReAct

---

### Q4 · Agent 的上下文管理和记忆机制如何设计？（⭐⭐⭐⭐⭐）

**【一句话答】** 三层记忆：短期（当前对话上下文，受限于 Context Window）、长期（短期记忆，向量库或 KV 存储）、Working（任务级 todo list）；设计目标是「信息全 + 不超长」。

**【标准答】**
- **短期记忆**：即当前 LLM 上下文窗口（128K / 200K），所有 Recent Thought / Observation 都在这里
- **长期记忆**：跨会话持久化，存向量库（语义检索）或 KV（按 user_id 精确查）
- **Working Memory**：任务级 todo list，Agent 内部维护，类似「我现在到第几步」
- **压缩策略**：超阈时触发压缩——
  - **Truncate**：保留最近 N 轮
  - **Summarize**：LLM 把旧消息摘要成一段文字
  - **Sliding Window**：保留最近 K 轮 + 长期摘要
  - **Entity Extraction**：抽取实体 / 偏好 / 关键事件，存到长期
- **典型实现**：LangGraph 的 `MemorySaver`、Mem0、Letta（前身 MemGPT）
- **避免污染** —— 每轮 Prompt 加 `Trust Boundary`：明示哪些是 system、哪些是 user、哪些是 tool 返回（防 Prompt Injection）

**【易错点】**
- ❌ "记忆 = 把所有历史塞进 Context" → ✅ 必然爆窗口，必须分层 + 压缩
- ❌ "FIFO 淘汰最优" → ✅ 简单场景可以，但会丢关键事实 → 必须配合 LLM 摘要

---

### Q5 · 多 Agent 协作有哪些常见模式？（⭐⭐⭐⭐⭐）

**【一句话答】** 4 种主流：单 Agent（基线）、Supervisor（中央调度）、Hierarchical（树形分层）、Peer-to-Peer（对等 / Debate）；选型看任务是否需要多角色、可分解性、审计需求。

**【标准答】**
| 模式 | 结构 | 适用 | 代表 |
|---|---|---|---|
| **单 Agent** | 1 个 LLM + 全部工具 | 简单任务、工具 < 5 | LangChain ReAct |
| **Supervisor** | 1 个调度 + N 个 Worker | 任务可清晰分解、需 guardrail | LangGraph、CrewAI |
| **Hierarchical** | 多层 Supervisor 树 | 超复杂任务、贴近企业架构 | ChatDev、MetaGPT |
| **Peer-to-Peer** | 无中心、黑板共享 | 需要多视角（辩论/审查） | AutoGen、ReConcile |
| **Blackboard** | 所有 Agent 读写共享空间 | 复杂决策 | 经典 AI 范式 |
- **通信**：消息总线（Kafka / Redis Streams）/ 共享内存 / 共享黑板
- **冲突解决**：Voting、Critic-Reviewer、Debate、Priority、Confidence 加权
- **何时多 Agent**：工具 > 10 / prompt > 4K / 需要 guardrail / 需要多视角 / 需要细粒度权限隔离

**【易错点】**
- ❌ "Agent 越多越好" → ✅ 多 Agent token 成本 3-10× 单 Agent，延迟叠加
- ❌ "黑板共享最方便" → ✅ 易冲突、难持久化，应给关键信息广播 + 敏感信息隔离

> 📊 配套交互图：[diagrams/ai03-multi-agent.html](./diagrams/ai03-multi-agent.html) — 4 种模式可切换 + 单/多决策表

---

## 二、Agent 记忆 / 上下文（Q6-Q10）

### Q6 · 大模型长上下文场景下如何压缩与优化？（⭐⭐⭐⭐）

**【一句话答】** 四种策略：Truncate（保留最近 N 轮）、Summarize（LLM 摘要）、Sliding Window（最近 K + 长期摘要）、Entity Extraction（抽实体入长期库）；优先 Summarize + Entity Extraction 组合。

**【标准答】**
- **截断策略**：保留 system + 最近 K 轮 user/assistant（K 通常 5-10）
- **摘要策略**：定期（每 5 轮 / 每超阈值）调 LLM 把旧消息压成 200 字摘要
- **实体抽取**：抽人名 / 偏好 / 关键事件 → 存向量库 → 下次按语义召回
- **Sliding Window + Summary**：最近 3 轮原文 + 历史摘要 + 检索到的实体
- **注意**：压缩不能破坏**任务依赖**——前一步 Observation 是后一步 Thought 的依据，必须保留
- **工具**：`llm-summarize`、`langchain.conversation.summary`、Mem0

**【易错点】**
- ❌ "只留最后一条" → ✅ 中间 Observation 可能丢了关键事实
- ❌ "压缩 = 删历史" → ✅ 应是「摘要 + 抽取」而非「删除」

---

### Q7 · 在 RAG 中，AI 如何减少和规避幻觉？（⭐⭐⭐⭐⭐）

**【一句话答】** 三大机制：答案必须基于检索结果（Faithfulness）、Process Call 强制结构化、Self-Critic / Reflection 自我审查；外加兜底：用户反馈 + 置信度阈值。

**【标准答】**
- **Faithfulness**：Prompt 强约束「仅基于以下 Context 回答，禁止编造」+ 评估指标 `Faithfulness Score`
- **Process Call**：输出受 JSON Schema 约束，参数错误立即报错不会硬猜
- **Reflection**：Self-Critic Agent 评估生成结果 → 不达召回则重试（Reflexion、Self-RAG）
- **RAG vs 微调**：知识更新用 RAG（避免灾难性遗忘），能力/风格用微调
- **Citation**：让 LLM 引用来源（[1][2]），用户可点回原文验证
- **Confidence**：让 LLM 输出置信度分数，< 阈值时主动告知用户「我不确定」

**【易错点】**
- ❌ "微调可以消除幻觉" → ✅ 微调只能减少「已知错误」，新增事实仍会编
- ❌ "RAG 就一定不幻觉" → ✅ Context 不相关 / Rerank 漏召 → 仍编，必须配套评估

---

### Q8 · 工具调用超时或返回空值时如何设计 Prompt 让 Agent 反馈？（⭐⭐⭐⭐）

**【一句话答】** 三层防护：① Prompt 提示「超时即重试 ≤3 次」② Observation 注入错误码 + 描述 ③ 兜底问用户澄清。

**【标准答】**
- **重试机制**：Agent 收到 `null / timeout / 5xx` → 自动 Retry（指数退避）→ 仍失败则换工具或兜底
- **Prompt 模板**：`若工具返回为空，检查：1) 参数是否正确 2) 是否需调用其他工具 3) 是否需要向用户澄清`
- **错误结构化**：工具返回结构化错误码 `{code: 404, message: "user not found"}`，Agent 据此决策
- **降级路径**：查 DB 失败 → 降级到查缓存 → 降级到让用户重输
- **兜底用户反馈**：所有降级失败 → Agent 输出 `请提供 X 信息，我才能继续`
- **避免死循环**：连续 N 次同工具失败 → 强制跳出并报错

**【易错点】**
- ❌ "工具失败就抛出异常" → ✅ Agent 应自主决策重试或降级，不是硬异常
- ❌ "用户反馈是最后手段" → ✅ 应该是所有失败路径的兜底

---

### Q9 · 工具调用的安全控制与敏感接口限制如何实现？（⭐⭐⭐⭐）

**【一句话答】** 四道闸：① Tool 白名单 ② 参数白名单（取值范围） ③ 危险操作人工审批（高风险 Tool 调前 wait） ④ Sandbox 隔离（文件 / 网络 / 资源）。

**【标准答】**
- **Tool 白名单**：Agent 能调的工具清单，未注册即使 LLM 写出也忽略
- **参数校验**：每个 Tool 入参做范围 / 类型校验（用 Pydantic / Zod）
- **危险分级**：
  - `safe`（查 DB / 读文件）：可直接调
  - `sensitive`（写 DB / 发邮件）：调前 Prompt 提示用户确认
  - `dangerous`（删库 / 转账）：必须硬拦截，需人类审批
- **Sandbox**：Agent 进程运行在容器 / 微沙箱，文件系统、网络、CW 受限
- **审计**：所有 Tool 调用记日志（谁、何时、参数、结果），可追溯

**【易错点】**
- ❌ "白名单就够了" → ✅ LLM 可被诱导调未在白名单的「看起来相似」的工具名
- ❌ "用户确认 = 万无一失" → ✅ 用户可能误点，应二次确认 + 撤销窗口

---

### Q10 · 工具描述中「什么时候该用」比「能做什么」更重要？（⭐⭐⭐⭐）

**【一句话答】** 工具描述（Function Calling 的 `description` 字段）是 LLM 唯一的选择依据，必须写明「**何时**调用」而非仅「能做什么」，否则 LLM 选错工具。

**【标准答】**
- **错误示例**：`"description": "执行 SQL 查询"` → LLM 不知道何时用
- **正确示例**：`"description": "当用户询问具体的某场景某状态或某订单时调用，输入用户 ID 返回订单详情，禁止用于模糊查询"`
- **包含要素**：
  1. **触发条件**：「当用户问 Y / 具体询问 / 模糊 / 需要 X 数据时」
  2. **输入约束**：「输入必须包含 X，否则报错」
  3. **输出说明**：「返回 N 个字段，含义是...」
  4. **边界**：「不返回合法答案时不要使用」
- **对比工具**：description 里写明「与 X 工具的区别是...」，避免 LLM 选错
- **测试**：每个 Tool 写若干测试 Query，看 LLM 是否在正确场景调它

**【易错点】**
- ❌ "description 写越详细越好" → ✅ 太长会让 LLM 抓不到重点，反而选错
- ❌ "Tool Schema 自动生成" → ✅ 必须人工 review description，机器生成会过度工程化

---

## 三、Tool 与 Function Calling（Q11-Q15）

### Q11 · LLM 是如何实现工具调用的？（⭐⭐⭐⭐⭐）

**【一句话答】** LLM 在训练时被喂入 JSON Schema 形式的 Tools 列表，输出受 Schema 约束的结构化 JSON（`tool_calls`），执行器按 JSON 调工具；Function Calling 是工程化机制，不是 LLM「理解」了工具。

**【标准答】**
- **训练数据**：在 SFT 阶段喂入 `(query, tools_schema, tool_call_json, observation, final_answer)` 五元组
- **推理机制**：Prompt 拼装 = `system + tools(JSON Schema) + history`，LLM 输出 `tool_calls: [{name, args}]`
- **执行**：Client 解析 `tool_calls` → 按 name 路由 → 执行函数 → 结果作为新消息注入
- **多工具并行**：LLM 一次输出多个 `tool_calls`，并行执行（OpenAI 支持 `parallel_tool_calls`）
- **停止条件**：LLM 输出无 `tool_calls` 字段 → Client 视为 Final Answer
- **厂商差异**：OpenAI 用 `tool_calls`、`Anthropic` 用 `tool_use` blocks，OpenAI 改用 `tools: []` 数组

**【易错点】**
- ❌ "LLM 自己学会调工具" → ✅ 是训练数据里硬塞的，不是涌现能力
- ❌ "Function Calling = 插件系统" → ✅ 是结构化输出机制，不是 OAuth / 权限系统

> 📊 配套交互图：[diagrams/ai01-react-loop.html](./diagrams/ai01-react-loop.html) — Step 2 看完整 Function Calling JSON

---

### Q12 · GRPO 和 PPO 的核心区别？（⭐⭐⭐⭐）

**【一句话答】** GRPO（Group Relative Policy Optimization）是 DeepSeek 提出的 RL 算法，去掉 PPO 的 Critic 网络、用同一批样本的相对优势估计，降低显存并更稳；PPO 需要单独的价值网络。

**【标准答】**
- **PPO**：
  - Actor-Critic 架构，需单独训练 Critic（Value Network）
  - Advantage = `r + γV(s') - V(s)`，依赖 Critic 估计
  - 显存大（两个网络），训练慢
- **GRPO**：
  - 无 Critic，对每个 prompt 采样 G 个回答
  - Advantage = `(r_i - mean(r)) / std(r)`，**组内相对**比较
  - 显存省一半，训练更快，方差控制更稳
- **信用分配**：GRPO 组内归一化天然处理「同一个 prompt 不同回答的相对好坏」
- **应用**：DeepSeek-R1 用 GRPO 训练推理能力，Qwen3 也跟进

**【易错点】**
- ❌ "GRPO 取代 PPO" → ✅ GRPO 主要用于 LLM 训练，RLHF 其他场景仍用 PPO
- ❌ "GRPO 没有 Critic 就更简单" → ✅ 实现更复杂（需组内采样 + 归一化），只是显存小

---

### Q13 · 基于强化学习的 Agent 与基于 Prompt 的 Agent 区别？（⭐⭐⭐⭐）

**【一句话答】** Prompt Agent 靠 Prompt + LLM 直出（零训练成本），RL Agent 通过环境反馈训练策略网络（高成本但可优化特定任务）。

**【标准答】**
- **Prompt Agent**：纯 Prompt 工程 + LLM，零训练成本、即用即得，但受限于 LLM 本身能力
- **RL Agent**：维护策略网络 π(a|s)，通过奖励 r 训练，可超出 LLM 能力上限
- **混合**：LLM 提供先验 / 决策候选，RL 选择 / 修正 / 拒绝
- **训练数据**：RL Agent 需要环境交互数据（昂贵），Prompt Agent 不需要
- **适用**：
  - Prompt Agent：通用任务、知识问答、快速迭代
  - RL Agent：博弈、机器人、长链路任务优化（AlphaGo、谈判 Agent）
- **代表作**：ToolLLM 用 RL 训练工具选择；RLHF 用 RL 训练对齐

**【易错点】**
- ❌ "Prompt Agent 一定不如 RL Agent" → ✅ 在大多数 LLM 已知领域 Prompt Agent 反而更好，因为无幻觉
- ❌ "RL Agent 不需要 LLM" → ✅ 现代 RL Agent 通常 LLM 作为 backbone，仅 RL 微调策略

---

### Q14 · 开源模型 Function Calling 能力较弱时如何提升？（⭐⭐⭐⭐）

**【一句话答】** 三招叠加：① Prompt 工程加 Few-shot 示例 ② LoRA / DPO 微调 function calling 格式 ③ 在 System Prompt 里强制规定 Tool 选择规则。

**【标准答】**
- **Prompt 工程**：
  - 在 System Prompt 显式规定「必须输出 `tool_calls: [{name, args}]` 格式」
  - 写 2-3 个 Few-shot 示例（不同场景下的工具调用 + 结果）
  - 描述工具时强制包含「**触发条件**和**禁止使用**」
- **微调**：
  - 用 `function-calling-instruct` 数据集 SFT（几百到几千条）
  - 或 LoRA 微调（显存友好）
  - 或 DPO（偏好对齐）
- **格式约束**：后处理兜底——LLM 输出后正则 / AST 解析失败则强制重试
- **替代方案**：用能力更强的闭源模型（GPT-4 / Claude）做 function calling，本地 LLM 出 reasoning

**【易错点】**
- ❌ "开源模型不能调工具" → ✅ Llama / Qwen / Mistral 都有 Function Calling 版本
- ❌ "微调万能" → ✅ 微调会过拟合特定工具集，换工具集要重训

---

### Q15 · Self-Reflection 机制在代码生成中如何应用？（⭐⭐⭐⭐）

**【一句话答】** Generator 写代码 → Executor 跑测试 → Self-Critic 评估错误 → 把「错误 + 反思」写回 Memory → 下一轮 Generator 基于反思改进，循环至通过。

**【标准答】**
- **流程**：
  1. Generator 输出代码
  2. Executor 跑单元测试 / 类型检查 / lint
  3. **Self-Critic** Agent 读测试报告 + 代码 → 输出「错误根因 + 下次怎么改」
  4. 把反思**写回长期 Memory**（不是只塞当前 Context）
  5. Generator 重写代码 → 循环
- **关键设计**：
  - 反思必须**结构化**（不要自然语言），例如 `{error_type, line_number, fix_suggestion}`
  - Memory 跨任务共享 → 类似经验沉淀
  - 设最大迭代次数（避免死循环）
- **代表**：
  - Reflexion（Shinn et al. 2023）
  - Self-RAG 的 `[IsSup]` `[IsUse]` Reflection Tokens
  - SWE-Agent、AutoCodeRover

**【易错点】**
- ❌ "Reflection = 让 LLM 自评" → ✅ 必须有外部信号（测试 / 错误）才能反，不是空想
- ❌ "反思写当前 Context 就够" → ✅ 必须沉淀到 Memory，否则下次任务用不上

---

## 四、Multi-Agent 协作（Q16-Q20）

### Q16 · 单 Agent 和多 Agent 分别适用哪些场景？（⭐⭐⭐⭐⭐）

**【一句话答】** 单 Agent 适合线性、简单任务（工具<5 个、延迟<1s、成本敏感）；多 Agent 适合可分解、需要多视角 / guardrail / 细粒度权限 / 审计 的复杂任务。

**【标准答】**
| 场景特征 | 单 Agent | 多 Agent | 理由 |
|---|---|---|---|
| 任务简单线性 | ✅ | ❌ | 多 Agent 增加延迟和成本 |
| 需要多视角 | ❌ | ✅ | 辩论/审查天然需要多视角 |
| 工具多 prompt 撑爆 | ❌ | ✅ | 每 Agent 持有自己的工具集 |
| 需要权限隔离 | ❌ | ✅ | Agent A 看不到 Agent B 的敏感工具 |
| 需要审计每步 | ❌ | ✅ | 每 Agent 单独 trace |
| 延迟敏感（<1s） | ✅ | ❌ | 多 Agent 串行延迟叠加 |
| 需要长期演化 | ❌ | ✅ | 不同 Agent 独立微调/记忆 |

**【易错点】**
- ❌ "默认选多 Agent" → ✅ 单 Agent 起步，遇到瓶颈再考虑多 Agent
- ❌ "多 Agent = 简单堆 Agent 数" → ✅ 1+1+1=3 的成本，但产出未必优于 1 个精心设计的 Agent

---

### Q17 · 多 Agent 协作中如何解决冲突 / 分歧 / 无法收敛？（⭐⭐⭐⭐⭐）

**【一句话答】** 五种机制：Voting（投票）、Critic-Reviewer（审稿）、Debate（辩论）、Priority（优先级）、Confidence 加权；仍无法收敛引入人类兜底。

**【标准答】**
- **Voting**：N 个 Agent 独立答，多数决（适合分类 / 选择）
- **Critic-Reviewer**：Generator 写、Reviewer 审 → 不通过就重写（适合代码 / 文章）
- **Debate**：双方各持观点 → 互相反驳 → 第三 Judge 裁决（适合决策类）
- **Priority**：Supervisor 按预设优先级裁决（如安全 > 性能）
- **Confidence 加权**：每个 Agent 输出 confidence，加权求和
- **兜底**：所有自动机制失败 → 转人工
- **避免死循环**：最大迭代 N 次 / 总耗时上限 → 超限则强制收敛到一个解

**【易错点】**
- ❌ "多投票几次就收敛" → ✅ 真冲突可能永远不收敛，必须有硬截止
- ❌ "Confidence 可以替代投票" → ✅ LLM 的 confidence 校准差，不能完全依赖

---

### Q18 · Multi-Agent 系统的通信协议与上下文共享策略？（⭐⭐⭐⭐）

**【一句话答】** 三种通信：消息总线（Kafka / Redis Streams）、共享内存（in-memory dict）、Blackboard（结构化状态空间）；上下文策略分共享 / 隔离 / 混合，推荐混合。

**【标准答】**
- **通信方式**：
  - **消息总线**：Kafka、Redis Streams、NATS，事件驱动、可持久化
  - **共享内存**：进程内 dict / 全局变量，快但难持久化
  - **Blackboard**：结构化状态空间（类似数据库），Agent 读写
  - **Pub/Sub**：发布订阅，Agent 订阅感兴趣的事件
- **上下文策略**：
  - **共享**：所有 Agent 同一 Context（Blackboard 模式），易冲突、token 暴涨
  - **隔离**：每 Agent 私有 Context，通过消息传递摘要
  - **混合（推荐）**：私有 + 共享黑板，关键信息广播、敏感信息隔离
- **代表框架**：
  - LangGraph：`StateGraph`
  - CrewAI：共享 `memory`
  - AutoGen：`GroupChat`
  - MetaGPT / ChatDev：消息 + SOP

**【易错点】**
- ❌ "全部共享最简单" → ✅ token 暴涨 + 难调试
- ❌ "全部隔离最干净" → ✅ Agent 之间缺乏共识，需复杂序列化协议

---

### Q19 · 在多 Agent 中如何解决上下文爆炸？（⭐⭐⭐⭐）

**【一句话答】** 三层防御：① 每 Agent 维护私有摘要（Summary） ② 共享信息走 Blackboard 关键字段而非全文 ③ Supervisor 汇总时只传结构化结果。

**【标准答】**
- **私有摘要**：每 Agent 输出结果时强制要求 1-2 句总结，Supervisor 只看总结不看历史
- **Blackboard 字段化**：Blackboard 只存结构化字段（如 `{task_id, status, owner, summary}`），不存完整对话
- **消息分片**：长对话切片，每片单独摘要后再合并
- **Lazy Loading**：Supervisor 只在需要时拉取某 Agent 的完整历史
- **TTL 过期**：Blackboard 字段设 TTL，过期自动清理
- **Token 监控**：每轮监控 token 用量，超阈值主动压缩

**【易错点】**
- ❌ "Blackboard 存什么都行" → ✅ 必须字段化、有 TTL，否则变成垃圾场
- ❌ "摘要能完全替代原文" → ✅ 摘要丢细节，关键决策需原文（如 Observation 报错信息）

---

### Q20 · 如何评估 Agent 的执行效果和项目效果？（⭐⭐⭐⭐⭐）

**【一句话答】** 三层评估：① 任务完成率（最重要） ② 过程指标（轮次、Token、延迟） ③ 质量指标（Faithfulness、Relevance、Hallucination Rate）。

**【标准答】**
- **任务级**：成功率 / 完成率 / 用户满意度（CSAT）
- **过程级**：
  - LLM 调用轮次（越少越高效）
  - Token 成本（监控每个任务花费）
  - 端到端延迟（p50 / p95）
  - 工具调用成功率 / 重试率
- **质量级**：
  - Faithfulness：答案是否忠于 Observation
  - Answer Relevance：答案对 Query 的相关度
  - Hallucination Rate：人工标注抽样
- **A/B 测试**：新旧 Agent 对照组跑同一批任务
- **人工评估**：关键任务（如高金额决策）必须人工 review
- **工具**：LangSmith、Langfuse、Phoenix（OpenTelemetry 兼容）

**【易错点】**
- ❌ "只看任务成功率" → ✅ 100% 成功率但每个任务花 ¥100 也失败
- ❌ "Agent 评估 = 自动评估" → ✅ 关键场景必须人工 ground truth

---

## 五、RAG 完整链路（Q21-Q26）

### Q21 · 为什么使用 RAG？（⭐⭐⭐⭐⭐）

**【一句话答】** RAG = Retrieval-Augmented Generation，用检索外部知识增强 LLM 答案，解决幻觉、知识过时、私域知识三大问题，比微调训灵活、比 Prompt 长 Context 经济。

**【标准答】**
- **三大问题**：
  1. **幻觉**：LLM 编造 → RAG 强约束「仅基于 Context」
  2. **知识过时**：模型有截止日期 → RAG 接实时库
  3. **私域知识**：企业内部文档 → RAG 接企业 DB
- **vs 微调**：RAG 不改模型权重、即接即用；微调需训练、灾难性遗忘
- **vs 长 Context**：Context 越长越贵（RAG 取 Top-K 短文本），且长 Context 注意力衰减
- **vs Web Search**：RAG 可控（内部库），Web Search 不可控（外部网站可能变更）
- **成本**：检索一次几毫秒、Embedding Model 推理便宜

**【易错点】**
- ❌ "RAG 替代微调" → ✅ RAG 解决知识问题，微调解决能力 / 风格问题，互补
- ❌ "RAG 万能" → ✅ RAG 解决不了「LLM 不擅长推理」的问题

> 📊 配套交互图：[diagrams/ai02-rag-pipeline.html](./diagrams/ai02-rag-pipeline.html) — 离线 / 在线 / Agentic 三种 Pipeline 可切换

---

### Q22 · RAG 中长文档切分的粒度和分块策略？（⭐⭐⭐⭐⭐）

**【一句话答】** 常用策略：递归切分（LangChain 默认）+ 父子分块（检索子块、返回父块）；中文 200-500 字、英文 200-800 tokens、overlap 10-20%。

**【标准答】**
- **固定长度切分**：chunk_size=500，overlap=50，简单但切断句子
- **递归切分**：`RecursiveCharacterTextSplitter`，按段落→句→词逐级切，优先保留语义单元
- **语义切分**：Embedding 相似度突变处切，精度高但慢
- **父子分块**：父块 2000 字 + 子块 200 字，检索子块、返回父块（上下文全）
- **Markdown-aware**：按标题分层，适合技术文档
- **特殊文档**：表格转 CSV 行、代码保留缩进、HTML 去标签保留主体
- **经验值**：中文 200-500 字、英文 200-800 tokens、overlap 10-20%

**【易错点】**
- ❌ "切得越细越好" → ✅ 太细丢失语义、太粗引入无关信息
- ❌ "所有文档用同一切分" → ✅ 法律 / 论文 / 代码需不同策略

---

### Q23 · 向量数据库检索到的历史信息即使语义相关但时间过久，还能直接使用吗？（⭐⭐⭐⭐）

**【一句话答】** 不能直接使用，必须叠加时间衰减 + 来源新鲜度评分 + 用户场景判断；时间敏感场景（新闻 / 股价）必须重排时显式过滤。

**【标准答】**
- **时间衰减**：检索打分乘以 `exp(-Δt / τ)`，τ = 半衰期（如 7 天 / 30 天）
- **元数据过滤**：存 `created_at` / `last_updated`，检索时 SQL 过滤 `WHERE date > ...`
- **新鲜度加权**：检索 Score × 新鲜度权重（0-1），老信息权重低
- **场景判断**：
  - 时效强（新闻 / 股价）：必须强制新鲜
  - 知识稳定（数学定理 / API 文档）：可用老信息
- **让 LLM 决断**：检索结果附 `created_at`，Prompt 提示「优先用新信息」
- **Hybrid 检索**：向量 + BM25（关键字），老但精准的 BM25 可弥补

**【易错点】**
- ❌ "向量检索 = 语义最近 = 答案最好" → ✅ 语义相关不等于实际匹配，必须时间 + 来源过滤
- ❌ "时间过滤就够了" → ✅ 老信息有时更权威（如学术经典论文）

---

### Q24 · RAG 系统中既然有向量检索，为什么还需要 Rerank？（⭐⭐⭐⭐⭐）

**【一句话答】** 向量检索快但精确度有限（Bi-Encoder 独立编码、句间无交互）；Rerank 用 Cross-Encoder 逐对打分，慢但精确。Pipeline = 先向量召 Top-50 → Rerank 取 Top-5。

**【标准答】**
- **Bi-Encoder（向量检索）**：Query 和 Doc **独立**编码再算相似度，快但**无交互**
- **Cross-Encoder（Rerank）**：Query 和 Doc **拼接**后联合打分，慢但**精确**
- **Pipeline**：先召回 Top-50（向量快）→ Rerank 取 Top-5（精排准）
- **性能权衡**：向量检索 10ms、Top-50 召回 100ms、Rerank 200ms，整体 < 500ms
- **TopK 取多少**：业务定，无标准；3-10 为常见
- **评估**：NDCG@K、MRR、Hit Rate、MAP
- **代表模型**：bge-reranker、cohere-rerank、Cohere Rerank 3

**【易错点】**
- ❌ "向量检索就够了" → ✅ 长文档 / 复杂 query 时 Bi-Encoder 精度不够
- ❌ "Rerank 替代向量" → ✅ 全量 Rerank 太慢，必须漏斗

---

### Q25 · Agentic RAG 与传统 RAG 的核心区别？（⭐⭐⭐⭐⭐）

**【一句话答】** 传统 RAG 单轮 Retrieve → Generate（固定 pipeline）；Agentic RAG 让 Agent 决定「查不查 / 查几次 / 用啥工具混合」（按需触发、多跳）。

**【标准答】**
- **传统 RAG**：每次 query 都查 → 拼成 Context → 生成；固定 pipeline，无思考
- **Agentic RAG**：
  - Agent 判断「**该不该查**」→ 内部知识够就直答
  - 「**查几次**」→ 多跳检索（基于上轮 Observation 决定下轮 Query）
  - 「**用什么工具**」→ 混合检索 / Web / SQL
  - Self-Reflection → 检索结果评估 → 不行就重查
- **变体**：
  - **Self-RAG**：Reflection Tokens `[Retrieve]` `[IsRel]` `[IsSup]` `[IsUse]`
  - **CRAG**：Corrective RAG，检索结果评估 + 必要时 Web 补充
  - **Adaptive-RAG**：根据 query 复杂度动态决定策略
- **代价**：延迟↑、token↑、需评估「该不该调」
- **适用**：复杂 query（需多跳）、私域 + 公域混合、需要反问澄清

**【易错点】**
- ❌ "Agentic RAG 一定更好" → ✅ 简单 query 用传统 RAG 就够，Agentic 是过度工程
- ❌ "Self-RAG = Self-Critic" → ✅ Self-RAG 用专门训练的 token 标记，延迟低

---

### Q26 · RAG 系统评测的维度和常用指标？（⭐⭐⭐⭐⭐）

**【一句话答】** 四大指标：Context Precision（召准率）、Context Recall（召回率）、Faithfulness（忠实度）、Answer Relevance（答案相关度）；工具用 RAGAS、TruLens、ARES。

**【标准答】**
- **Context Precision**：检索到的 Context 里**相关**的比例（检索质量）
- **Context Recall**：答案所需的**全部** Context 被召回了多少（召回完整度）
- **Faithfulness**：答案是否**忠实于** Context，有无幻觉
- **Answer Relevance**：答案对 Query 的相关程度
- **端到端指标**：任务成功率、用户满意度
- **工具**：
  - **RAGAS**（最主流，基于 LLM 评估）
  - **TruLens**
  - **ARES**（需标注数据训练）
- **必须**：评估数据集需要人工标注 ground truth（query + 答案 + 标注 relevant docs）
- **流程**：线下评估集跑分 → 线上 A/B → 持续监控

**【易错点】**
- ❌ "LLM 自评就够了" → ✅ LLM 自评校准差，必须有人工标注 ground truth
- ❌ "只看 Faithfulness" → ✅ Faithfulness 高但 Relevance 低也没用

---

## 六、LLM 训练与对齐（Q27-Q31）

### Q27 · LLM 模型选择策略？（⭐⭐⭐⭐）

**【一句话答】** 三种策略：单一模型（成本低、运维简单）、多模型切换（按场景路由：强模型在复杂、弱模型在简单）、自研模型（特定场景优化）；90% 项目用单一闭源模型起步。

**【标准答】**
- **单一模型**：全部请求走同一 LLM（如 GPT-4 / Claude 3.5），运维简单、成本可控
- **多模型路由**：Router 根据 query 复杂度路由——
  - 简单问答用 GPT-4o-mini / Qwen-Turbo
  - 复杂推理用 GPT-4 / Claude Opus / DeepSeek-R1
  - 长文本用 Gemini 1.5 Pro / Claude 3.5 Sonnet（200K context）
- **自研模型**：基于开源底座（Qwen / Llama / DeepSeek）LoRA 微调
- **决策依据**：
  - 任务类型（对话 / 推理 / 长文）
  - 成本（每千 token 价）
  - 延迟（P50 / P95）
  - 合规（数据出境 / 隐私）
  - 能力（特定 benchmark）

**【易错点】**
- ❌ "用最强模型一定最好" → ✅ 贵且慢，简单任务浪费
- ❌ "切换模型很简单" → ✅ 不同厂商 API / Prompt / 输出格式都不同，需抽象层

---

### Q28 · LLM 微调的 SFT / LoRA / DPO 区别？（⭐⭐⭐）

**【一句话答】** SFT（全参数微调，效果好但贵）、LoRA（低秩适配，省显存但效果略逊）、DPO（偏好对齐，不需训练奖励模型）；现代项目常用 LoRA + DPO 组合。

**【标准答】**
- **SFT（Supervised Fine-Tuning）**：全参数在标注数据 `(prompt, response)` 上反向传播。贵（百万级 GPU hour），效果好。
- **LoRA（Low-Rank Adaptation）**：冻结原权重，加 `W + A·B` 低秩矩阵，只训 A、B。省显存 10×，效果接近 SFT，可多个 LoRA 热切换。
- **QLoRA**：LoRA + 4bit 量化，单卡 24G 可微调 65B 模型
- **DPO（Direct Preference Optimization）**：用 `(prompt, chosen, rejected)` 三元组训练，跳过 RLHF 的奖励模型，效果接近 PPO
- **组合**：先 SFT（学格式）→ 再 DPO（学偏好），现代对齐主流
- **何时用**：
  - 改格式 / 学特定风格 → SFT
  - 资源有限 / 多任务 → LoRA
  - 改人类偏好 / 拒答风格 → DPO

**【易错点】**
- ❌ "LoRA 一定不如 SFT" → ✅ 现代 LoRA 效果 ≈ SFT，差距 < 2%
- ❌ "DPO 替代 RLHF" → ✅ DPO 简化流程但理论上限略低，顶级模型仍用 PPO

---

### Q29 · 模型蒸馏（Distillation）的原理与实践？（⭐⭐⭐）

**【一句话答】** 用大模型（Teacher）的输出训练小模型（Student），Student 学到 Teacher 的「软标签」（含概率分布）；用于模型压缩、降低推理成本。

**【标准答】**
- **原理**：Teacher 输出概率分布 `p_T(y|x)` → Student 拟合 `KL(p_T || p_S)`（KL 散度）
- **数据合成**：用 Teacher 给大量无标注 query 生成标注数据 → 训练 Student
- **优点**：Student 比直接训练小模型效果好（小模型学小模型学不到的知识）
- **场景**：
  - 移动端 / 边缘部署（GPT-4 → Phi-3）
  - 成本敏感（Teacher 调用 $0.01 / 1K token → Student 自部署 $0）
  - 闭源转开源（API 输出训练本地模型）
- **代表**：DeepSeek-V2 Lite、Phi-3、Qwen2.5 系列

**【易错点】**
- ❌ "蒸馏 = 数据标注" → ✅ 数据是合成的，不是人工标
- ❌ "Student 一定能压缩" → ✅ 复杂任务蒸馏损失大（如数学推理）

---

### Q30 · LLM 中的 Token 是什么？（⭐⭐⭐）

**【一句话答】** Token = 模型输入输出的最小单位；中文 1 字符 ≈ 1-2 token，英文 1 单词 ≈ 1.3 token；Context Window 上限按 token 计（如 128K = 12.8 万 token ≈ 9.6 万中文字）。

**【标准答】**
- **分词方式**：BPE（Byte Pair Encoding，OpenAI 用）、WordPiece（BERT 用）、SentencePiece（多语言）
- **数量参考**：1 token ≈ 0.75 个英文单词 ≈ 1.5 个中文字符
- **Context Window**：模型一次能处理的 token 数（含输入 + 输出），GPT-4 = 128K、Claude 3.5 = 200K、Gemini 1.5 = 2M
- **成本**：按 token 计费（input vs output 价不同，通常 output 更贵）
- **计算**：用 `tiktoken`（OpenAI）、`transformers.tokenizer` 精确计算
- **优化**：长文本压缩 / 摘要 / 检索降低 token

**【易错点】**
- ❌ "1 token = 1 字" → ✅ 中文 1 字 ≈ 1.5 token，差异显著
- ❌ "Context Window 越大越好" → ✅ 越长越贵 + 注意力衰减，多数场景 8K-32K 就够

---

### Q31 · 什么是 LoRA 微调？原理与适用？（⭐⭐⭐）

**【一句话答】** LoRA（Low-Rank Adaptation）冻结预训练权重 W，加低秩分解 `ΔW = A·B`（A: d×r，B: r×k，r << d），只训 A、B；省显存 10×、多任务可热切换。

**【标准答】**
- **原权重**：`W ∈ R^{d×k}` 预训练冻结
- **LoRA 增量**：`ΔW = A·B`，`A ∈ R^{d×r}`、`B ∈ R^{r×k}`，r 是秩（如 8、16、64）
- **总参数**：原 `d×k` + LoRA `r×(d+k)`，r=8 时约 1/100
- **推理合并**：训练完成后 `W' = W + A·B`，推理无额外开销
- **多任务**：同一底座 + 不同 LoRA 权重 → 切换任务无重训
- **适用**：领域适配（医疗 / 法律）、格式学习、风格调整
- **QLoRA**：4bit 量化底座 + LoRA，65B 模型单卡 24G 可训

**【易错点】**
- ❌ "LoRA = 全模型微调" → ✅ 只训低秩矩阵，原权重冻结
- ❌ "r 越小越好" → ✅ r 太小欠拟合、太大过拟合，经验值 8-64

---

## 七、MCP（Model Context Protocol）（Q32-Q34）

### Q32 · MCP 和 Skills 有什么区别？（⭐⭐⭐⭐）

**【一句话答】** MCP 是 Anthropic 提出的**协议标准**（让 LLM 调用外部工具/数据/模板的统一接口）；Skills 是 Anthropic 在 Claude.ai 中提供的**预制能力包**（如 PDF 解析、代码执行），MCP 是底层协议、Skills 是上层应用。

**【标准答】**
- **Skills**（Anthropic 概念）：
  - 预制能力包（PDF / 代码执行 / 数据分析 / Web 搜索）
  - 由 Anthropic 官方维护，开箱即用
  - 用户在 Claude.ai 里启用即可
  - 类似 ChatGPT 的 GPTs / Plugins
- **MCP**（Model Context Protocol）：
  - 开放协议标准（类似 LSP / ODBC）
  - 定义 Host / Client / Server 三层
  - 任何 LLM / 任何工具都可适配
  - 三原语：Tools / Resources / Prompts
- **关系**：Skills 底层走 MCP，第三方可开发自有 MCP Server 自定义 Skills

**【易错点】**
- ❌ "Skills = MCP" → ✅ Skills 是 MCP 之上的具体能力，MCP 是底层协议
- ❌ "MCP 只 Anthropic 用" → ✅ OpenAI 2025 已跟进，跨厂商标准

> 📊 配套交互图：[diagrams/ai04-mcp-architecture.html](./diagrams/ai04-mcp-architecture.html) — 整体架构 + 三原语 + MCP vs FC 三模式可切换

---

### Q33 · 请介绍 MCP 的作用与原理？（⭐⭐⭐⭐⭐）

**【一句话答】** MCP = AI 应用的「USB-C」/「LSP」，统一 LLM 与外部工具/数据/模板的通信协议（JSON-RPC 2.0），解决 M 个 LLM × N 个工具 = M×N 适配问题，标准化后变 M+N。

**【标准答】**
- **问题**：M 个 LLM（GPT / Claude / Gemini）× N 个工具（DB / API / 文件）= M×N 适配
- **MCP 方案**：N 个工具收成 N 个统一 MCP Server → M 个 Client 连 → 变 M+N
- **类比**：LSP（编辑器×语言 → M+N）、ODBC（应用×DB → M+N）、USB-C（设备×接口 → 标准）
- **三层架构**：
  - **MCP Host**：用户应用（Claude Desktop / Cursor / Cline），跑 Client
  - **MCP Client**：与 Server 1:1 长连接，发 JSON-RPC 请求
  - **MCP Server**：独立进程，暴露 Tools / Resources / Prompts
- **三原语**：
  - **Tools**：LLM 主动调用（类似 Function Calling）
  - **Resources**：Client 注入的只读数据（文件 / DB / API）
  - **Prompts**：用户手动触发的可复用模板
- **通信**：JSON-RPC 2.0 over stdio（本地）/ SSE / Streamable HTTP（远程）
- **生态**：官方已有 Filesystem / GitHub / Postgres / Slack / Puppeteer 等 Server

**【易错点】**
- ❌ "MCP = Function Calling 升级" → ✅ MCP 是协议，FC 是机制，MCP 可承载 FC
- ❌ "MCP 解决所有问题" → ✅ 新协议、生态未成熟、引入一层网络延迟

---

### Q34 · 如何开发一个 MCP Server？（⭐⭐⭐⭐）

**【一句话答】** 用官方 SDK（Python `mcp` / TS `@modelcontextprotocol/sdk`），用 `@server.tool()` 装饰器暴露 Tools、Resource、Prompt，运行 stdio 模式即可被 Claude Desktop / Cursor 加载。

**【标准答】**
- **Python**：
  ```python
  from mcp.server import Server
  from mcp.types import Tool
  
  server = Server("my-server")
  
  @server.tool()
  async def my_tool(param: str) -> str:
      """工具描述：何时调用"""
      return f"Result: {param}"
  
  if __name__ == "__main__":
      server.run(stdio_server)
  ```
- **TypeScript**：
  ```typescript
  import { Server } from "@modelcontextprotocol/sdk/server";
  const server = new Server({ name: "my-server", version: "1.0.0" });
  server.setRequestHandler("tools/list", async () => ({ tools: [...] }));
  ```
- **传输**：stdio（本地，最常用）/ SSE（远程，旧）/ StreamableHTTP（远程，新）
- **Client 配置**：`~/.config/claude-desktop/claude_desktop_config.json`：
  ```json
  {
    "mcpServers": {
      "my-server": { "command": "python", "args": ["-m", "my_server"] }
    }
  }
  ```
- **调试**：用 MCP Inspector 工具（`npx @modelcontextprotocol/inspector`）

**【易错点】**
- ❌ "开发 MCP Server 很复杂" → ✅ 几十行代码即可起步
- ❌ "MCP 必须远程" → ✅ 本地 stdio 是最常用模式，零延迟

---

## 八、评估、安全与性能（Q35-Q40）

### Q35 · RAG 中长文本应该如何切分，如何选择策略？（⭐⭐⭐⭐）

**【一句话答】** 选策略看 4 个维度：文档类型（结构化 vs 非结构化）、Query 长度（短 vs 长）、领域（数据 vs 创意）；不确定就用 LangChain `RecursiveCharacterTextSplitter` 起步。

**【标准答】**
- **结构化（Markdown / HTML）**：按标题 / 段落切（MarkdownTextSplitter / HTMLSplitter）
- **非结构化（PDF 扫描件）**：OCR 后按页 + 段落切
- **短 query（< 10 词）**：chunk 小一点（200-300 字），精确匹配
- **长 query / 复杂问题**：chunk 大一点（500-800 字），保留上下文
- **代码**：按函数 / 类切，保留缩进
- **法律 / 论文**：按章节 / 法条切
- **黄金法则**：先固定长度 + overlap → 评估召回率 → 调参 / 换策略
- **评估**：换切分策略后跑 RAGAS 看 Context Recall 是否提升

**【易错点】**
- ❌ "固定一种切分走天下" → ✅ 不同文档类型需不同策略
- ❌ "切得越细越好" → ✅ 太细丢语义、太粗召不准，需平衡

---

### Q36 · RAG 中什么时候用静态知识库 vs 动态网页检索？（⭐⭐⭐⭐）

**【一句话答】** 静态知识库（向量库）适合稳定 / 私域 / 高频问答；动态网页检索适合时效 / 开放 / 长尾问题；Agentic RAG 中两者常混合使用。

**【标准答】**
- **静态知识库**：
  - 场景：企业内部文档 / 产品手册 / API 文档
  - 优势：可控（自家数据）、稳定（不依赖外部网站）、低延迟（向量检索 ms 级）
  - 劣势：知识陈旧（更新需 reindex）、覆盖有限
- **动态网页检索**：
  - 场景：新闻 / 股价 / 实时数据 / 长尾问题
  - 优势：实时 / 覆盖面广
  - 劣势：不稳定（网站改版）、不可控（可能下架）、延迟高
- **混合策略**（Agentic RAG）：
  - 优先查静态库 → 召回不足 → 补 Web 检索 → 都答不出 → 转人工
  - 关键决策（高金额）必须标注「数据来源 + 时间戳」
- **代表**：Perplexity（Web + LLM）、Bing Chat、ChatGPT Browse

**【易错点】**
- ❌ "Web 检索 = RAG" → ✅ Web 检索是 RAG 的一种数据源，不是 RAG 本身
- ❌ "只信自家库" → ✅ 时效场景必须补充外部源

---

### Q37 · Agent 工具调用幻觉（编造 API / 传错参数）如何解决？（⭐⭐⭐⭐）

**【一句话答】** 四道防线：① Tool Schema 强约束 ② 后处理 AST 校验参数 ③ 调用前确认（危险工具） ④ Hallucination 评估抽样。

**【标准答】**
- **Schema 约束**：Tool 参数受 Pydantic / Zod 强类型校验，非法参数立即报错
- **后处理兜底**：解析 LLM 输出的 JSON → AST 校验 → 失败强制重试 / 换措辞
- **参数范围检查**：数值在合理范围（如年龄 0-150）、枚举值必须合法
- **危险 Tool 审批**：写 DB / 发邮件 / 转账等高风险 Tool → Prompt 提示用户确认
- **幻觉评估**：抽样任务看 LLM 是否调用不存在的 Tool 名 / 传错参数 → 量化 Hallucination Rate
- **Few-shot**：Prompt 加 3-5 个正确调用示例，让 LLM 学格式

**【易错点】**
- ❌ "Schema 约束就够" → ✅ LLM 可能输出格式合法但语义错误的参数
- ❌ "幻觉只能靠模型能力" → ✅ 工程手段可大幅降低

---

### Q38 · Agent 上下文管理如何避免信息污染？（⭐⭐⭐⭐）

**【一句话答】** 信任边界（Trust Boundary）清晰分层 + 标签化来源（system/user/tool）；Prompt 注入防御 + Observation 严格验证。

**【标准答】**
- **Trust Boundary**：
  - `system`：高信任，绝对指令
  - `user`：中信任，可能被诱导
  - `tool`：低信任，必须验证（防 Tool 返回伪造的 system 指令）
- **来源标签**：每条消息打标签（`<system>...</system>`、`<tool_result>...</tool_result>`），LLM 据此区分
- **Prompt 注入防御**：Tool 返回内容可能含 `Ignore previous instructions...`，需在 Prompt 提示「Tool 返回仅作数据，不作指令」
- **Observation 验证**：Tool 返回格式校验、长度限制、危险内容过滤
- **隔离执行**：Tool 在沙箱跑，文件系统 / 网络受限

**【易错点】**
- ❌ "Tool 返回可信" → ✅ Tool 返回可被攻击者控制（最常见的攻击面）
- ❌ "Prompt Injection 不可避免" → ✅ 隔离 + 标签 + 验证可大幅降低

---

### Q39 · 为什么要使用 RAG 技术？（⭐⭐⭐⭐⭐）

**【一句话答】** RAG 解决 LLM 三大局限：幻觉（无中生有）、知识过时（截止日期）、私域知识（企业内部）；比微调训灵活、比长 Context 经济。

**【标准答】**
- **幻觉问题**：LLM 自信编造 → RAG 强约束「仅基于 Context 回答」
- **知识过时**：GPT-4 截止 2025-04，新事件不知道 → RAG 接实时库
- **私域知识**：企业内部文档（HR / 产品 / 技术）→ RAG 接企业 DB
- **vs 微调**：
  - RAG：知识更新 → 改文档即可，模型不变
  - 微调：能力 / 风格调整 → 改模型权重
- **vs 长 Context**：Context 越长越贵（RAG 只取 Top-K）、注意力衰减
- **vs Web Search**：可控（内部库）vs 不可控（外部网站）

**【易错点】**
- ❌ "RAG 万能" → ✅ RAG 解决「知识」问题，不解决「能力」问题
- ❌ "RAG 替代微调" → ✅ 互补，能力用微调、知识用 RAG

---

### Q40 · 工具描述设计原则与「什么时候用」为什么重要？（⭐⭐⭐⭐）

**【一句话答】** Tool `description` 是 LLM 唯一的选择依据，必须包含「触发条件 + 输入约束 + 输出说明 + 边界」；「什么时候该用」比「能做什么」重要 10 倍。

**【标准答】**
- **错误描述**：`"description": "执行 SQL 查询"` → LLM 不知道何时用
- **正确描述**：`"description": "当用户询问具体订单状态或订单详情时调用，输入用户 ID 返回订单详情，禁止用于模糊查询或商品推荐"`
- **四要素**：
  1. **触发条件**：「当用户问 Y / 具体询问 / 模糊 / 需要 X 数据时」
  2. **输入约束**：「输入必须包含 X，否则报错 Y」
  3. **输出说明**：「返回 N 个字段，含义是...」
  4. **边界**：「不返回合法答案时不要使用」
- **对比工具**：description 里写明「与 X 工具的区别是...」，避免 LLM 选错
- **A/B 工具**：每个 Tool 写测试 Query → 看 LLM 是否在正确场景调它 → 调 description

**【易错点】**
- ❌ "description 越详细越好" → ✅ 太长让 LLM 抓不到重点，反而选错
- ❌ "自动生成 description" → ✅ 必须人工 review，机器生成会过度工程化

---

## 九、工程落地与流式输出（Q41-Q45）

### Q41 · 如何自建一个生产级 Agent 系统？（⭐⭐⭐⭐）

**【一句话答】** 六层架构：① Prompt 管理 ② Memory 层 ③ Tool 抽象 ④ Router / Planner ⑤ Executor ⑥ 观测（Observability）；推荐 LangGraph + Langfuse。

**【标准答】**
- **六层**：
  1. **Prompt 管理**：版本化（git）、A/B 测试、热更新
  2. **Memory**：短期（Context Window）+ 长期（向量 / KV）
  3. **Tool 抽象**：统一接口、参数校验、错误处理
  4. **Router / Planner**：决定调什么工具 / 顺序
  5. **Executor**：执行 + 重试 + 超时 + 降级
  6. **Observability**：trace、metric、log（Langfuse / LangSmith）
- **关键技术**：
  - **Retry**：指数退避 + 最大次数
  - **Timeout**：每个 Tool 设超时
  - **Circuit Breaker**：连续失败 → 熔断
  - **Rate Limit**：LLM API 限流
- **选型**：
  - Python：LangGraph（推荐）、LangChain、CrewAI
  - TS：Vercel AI SDK、LangGraph.js
- **上线检查**：评估集 → A/B → 灰度 → 全量

**【易错点】**
- ❌ "Agent = Prompt + LLM" → ✅ 工程化要求 6 层 + 监控 + 容错
- ❌ "自研 vs 框架" → ✅ 90% 用 LangGraph 等框架，特殊场景自研

---

### Q42 · Rerank 的 TopK 数量通常如何确定？（⭐⭐⭐⭐）

**【一句话答】** 无标准答案，看业务：简单 QA 用 3-5、复杂推理用 10-20；通过评估 NDCG@K 找到最优 K；Rerank 后再截断到 LLM Context 预算内。

**【标准答】**
- **业务定**：
  - 简单 QA：K=3-5（短答案）
  - 多跳推理：K=10-20（需多证据）
  - 长文生成：K=15-30（背景丰富）
- **Context 预算**：K × 单 chunk 长度 ≤ LLM Context 预算（如 32K Context 最多 64 个 500 字 chunk）
- **评估指标**：
  - **NDCG@K**：位置越靠前权重越大，看 Top-K 质量
  - **Recall@K**：K 个里召回真实证据的比例
  - **MRR**：第一个相关结果的位置
- **调优流程**：K=1, 3, 5, 10, 20 各跑一遍 → 看 NDCG 曲线 → 选拐点
- **成本权衡**：K 越大 → Rerank 越慢 → 整体延迟↑

**【易错点】**
- ❌ "K 越大越好" → ✅ K 大到一定程度边际收益递减，延迟却线性增加
- ❌ "K 越小越好" → ✅ K=1 容易漏召，多跳场景必须 ≥ 5

---

### Q43 · Agent 的 Memory 系统为什么必要？长期记忆的 FIFO 淘汰问题？（⭐⭐⭐⭐）

**【一句话答】** Memory 维持任务连贯性（短期）+ 跨任务经验沉淀（长期）；FIFO 简单但丢关键事实（早期偏好 / 关键决策），应替换为 LLM 摘要 + 重要性评分。

**【标准答】**
- **必要性**：
  - **短期**：单次任务连贯（不能丢前一步 Observation）
  - **长期**：跨任务偏好（用户喜欢 X 风格）、经验沉淀（上次犯过同类错误）
- **FIFO 问题**：
  - **简单** → 队列满丢最早
  - **问题 1**：早期重要事实被丢（如「用户是医生」「项目预算 100 万」）
  - **问题 2**：重复压缩 → 信息扭曲
- **改进方案**：
  - **Importance Score**：LLM 给每条记忆打分（如 1-10），低分先淘汰
  - **Recency × Importance**：综合得分
  - **LLM 摘要 + 实体抽取**：不丢原文而是抽摘要 / 实体入长期
  - **分层**：高频短期（Redis）+ 低频长期（向量库）
  - **手动清理**：用户主动删除 / 编辑

**【易错点】**
- ❌ "FIFO 简单够用" → ✅ 简单场景可以，但用户偏好 / 关键决策不能丢
- ❌ "Memory 越多越好" → ✅ 检索成本↑、噪声↑，应挑"重要的"存

---

### Q44 · Agent 耗时过长时如何优化？（⭐⭐⭐⭐⭐）

**【一句话答】** 分工程侧（缓存、并行、批处理、降级模型）和基座侧（更小模型、量化、Self-Host 替代 API），按优先级工程侧先做。

**【标准答】**
- **工程侧**（优先做）：
  - **Prompt 缓存**：相同 prefix 命中缓存（OpenAI 50% 折扣）
  - **并行工具调用**：独立 Tool 并发（async gather）
  - **批处理**：多个 query 合并成一个 LLM 调用
  - **结果缓存**：相同 Tool 调用结果缓存
  - **降级模型**：复杂推理用大模型，简单任务用小模型（GPT-4o-mini 比 GPT-4 便宜 30×）
- **基座侧**（次优）：
  - **更小模型**：从 GPT-4 换到 GPT-4o-mini，或 DeepSeek-V3 替代 Claude
  - **量化 / Self-Host**：Qwen-72B 量化 4bit + vLLM 自部署
  - **Speculative Decoding**：小模型预生成、大模型验证
- **观测**：
  - p50 / p95 / p99 延迟监控
  - Token / 任务成本监控
  - 慢任务 trace 分析

**【易错点】**
- ❌ "延迟高就换小模型" → ✅ 先看是不是工程问题（重复调用、串行依赖）
- ❌ "Self-Host 一定便宜" → ✅ 流量小时 API 便宜，量大才自部署

---

### Q45 · Agent 长期记忆的 FIFO 淘汰策略问题与优化？（⭐⭐⭐⭐）

**【一句话答】** FIFO 简单但丢关键事实；优化方案：Importance Score（LLM 打分）+ LRU + 实体抽取 + 分层（Redis 热 + 向量库冷）。

**【标准答】**
- **FIFO 问题**：
  - 关键偏好被丢（如「用户是医生」「项目预算 100 万」）
  - 早期关键决策被丢（如「不能用 MySQL 用 PG」）
- **优化方案**：
  1. **Importance Score**：LLM 给每条记忆打 1-10 分，低分先淘汰
  2. **Recency × Importance**：`score = importance × exp(-Δt / τ)`，τ 是半衰期
  3. **实体抽取**：把「用户偏好」「关键决策」显式抽取入长期，向量检索
  4. **分层存储**：
     - Redis（高频短期，几小时-几天）
     - 向量库（中频长期，语义检索）
     - 文件系统（低频归档）
  5. **手动管理**：用户主动删除 / 编辑 / 收藏
- **代表**：Mem0、Letta（MemGPT）、LangGraph MemorySaver

**【易错点】**
- ❌ "淘汰策略 = 删就行" → ✅ 必须先识别「重要 vs 不重要」
- ❌ "全存向量库" → ✅ 短消息没必要走向量，应分层

---

## 十、Prompt 与实战（Q46-Q50）

### Q46 · 让 Agent 具备自我学习和经验沉淀能力？（⭐⭐⭐⭐）

**【一句话答】** Reflexion 框架：每次任务结束 Self-Critic 反思 → 写入 Memory → 下次类似任务检索并复用反思；类似人脑的「复盘」机制。

**【标准答】**
- **三步循环**：
  1. **执行**：Agent 完成任务
  2. **Self-Critic**：Self-Critic Agent 评估（成功 / 失败 / 为什么）
  3. **反思存储**：写回 Memory（结构化：`{task_type, success, error, lesson}`）
- **下次复用**：新任务 → 检索类似历史 → 把历史反思注入当前 Prompt → 避免重蹈覆辙
- **进阶**：
  - **离线微调**：定期把反思转成 SFT 数据 → LoRA 微调模型
  - **跨任务迁移**：从 A 任务学到的经验应用到 B
- **代表**：Reflexion（Shinn et al. 2023）、ExpeL、Self-RAG Reflection Tokens

**【易错点】**
- ❌ "反思 = 让 LLM 自评" → ✅ 必须有外部信号（测试 / 用户反馈）
- ❌ "存反思就够" → ✅ 必须**结构化**且**检索可用**，否则下任务用不上

---

### Q47 · 什么是 Multi-Agent 系统？它与单 Agent 系统的特点和适用场景？（⭐⭐⭐⭐）

**【一句话答】** Multi-Agent = N 个独立 Agent 协作完成任务；特点是专业化、可分解、可审计，但成本高（3-10×）；适用：可分解任务、需要多视角、需要 guardrail。

**【标准答】**
- **特点**：
  - **专业化**：每 Agent 专精一个领域（如 Researcher / Coder / Reviewer）
  - **可分解**：复杂任务拆给多 Agent 并行 / 串行
  - **可审计**：每 Agent 独立 trace，便于排查
  - **权限隔离**：Agent A 看不到 Agent B 的敏感工具
- **代价**：
  - Token 成本 3-10× 单 Agent
  - 延迟叠加（串行尤甚）
  - 调试复杂（Agent 间交互）
- **适用**：
  - 任务可清晰分解（客服销售工单 → 分类 / 处理 / 复核）
  - 需要多视角（投资分析 → 多空辩论）
  - 需要 guardrail（金融场景：交易 Agent 不可直接下单，需 Risk Agent 审批）
- **代表**：MetaGPT（软件公司）、AutoGen（GroupChat）、ChatDev、CrewAI

**【易错点】**
- ❌ "Multi-Agent 一定更智能" → ✅ 多 Agent 增加协调成本，产出未必更好
- ❌ "越多 Agent 越好" → ✅ 1 + 1 + 1 = 3 成本，单 Agent 精心设计可能产出更高

---

### Q48 · 如何设计 Agent 的流式输出？（⭐⭐⭐⭐）

**【一句话答】** Server-Sent Events (SSE) 流式推送 token；前端用 EventSource 实时渲染；工具调用阶段显示「思考中 → 调用 X 工具 → 返回结果」的多阶段状态。

**【标准答】**
- **流式传输协议**：
  - **SSE**（Server-Sent Events）：单向，主流选择
  - **WebSocket**：双向，复杂场景
  - **HTTP/2 Stream**：高性能
- **OpenAI 兼容流式响应**：每个 chunk 是 `data: {...}\n\n` 格式，结束时 `data: [DONE]`
- **多阶段渲染**：
  - 思考阶段：「🤔 正在分析...」
  - 工具阶段：「🔧 调用 search_flight...」
  - 观察阶段：「📥 拿到 3 个结果」
  - 生成阶段：「✨ 生成中...」（实时 token 流）
- **前端**：
  - EventSource / fetch stream
  - Markdown 实时渲染（marked / markdown-it）
  - 代码块高亮（highlight.js）
  - 防抖 + 节流（避免闪烁）
- **取消**：用户中断 → fetch AbortController → 后端停止生成

**【易错点】**
- ❌ "流式 = 等所有 token 一起返回" → ✅ 用户体验差，必须逐 token
- ❌ "Tool 调用阶段用户看不到" → ✅ 应显示「调用 X 中」否则用户以为卡死

---

### Q49 · 工具描述中「什么时候该用」为什么比「能做什么」更重要？（⭐⭐⭐⭐）

**【一句话答】** LLM 没有工具的「先验知识」，**唯一**选择依据是 `description`；描述「能做什么」LLM 不知道何时用，描述「触发条件」LLM 才能精准决策。

**【标准答】**
- **核心原因**：LLM 没有工具的语义知识（除训练数据），Tool 选择全凭 `description`
- **错误示例**：`"description": "搜索网页"` → LLM 不知道何时用、用什么 query
- **正确示例**：`"description": "当用户询问实时新闻、股价、天气等时效性信息时调用，输入自然语言问题，返回前 5 条搜索结果；不用于内部知识查询"`
- **四个关键字段**：
  1. **触发条件**：「当用户问 Y 时」
  2. **禁止场景**：「不用于 Z」
  3. **输入要求**：「输入必须包含 X」
  4. **输出说明**：「返回 N 个字段」
- **对比工具**：描述里说明 `与 Y 工具的区别是...`，避免选错

**【易错点】**
- ❌ "description 写得越多越好" → ✅ 太长 LLM 抓不到重点
- ❌ "description 可以后期改" → ✅ 改了要重新评估，不能 A/B 不一致

---

### Q50 · 工具调用的安全风险与防范？（⭐⭐⭐⭐）

**【一句话答】** 四大风险：① Prompt Injection（Tool 返回伪造指令） ② 越权执行（Agent 调未授权 Tool） ③ 参数注入（参数含代码 / shell 注入） ④ 沙箱逃逸；防范：信任分层 + 白名单 + 参数校验 + 沙箱。

**【标准答】**
- **风险 1：Prompt Injection**
  - Tool 返回内容可能含 `Ignore previous instructions...`
  - 防范：Prompt 标记 Tool 内容为「数据而非指令」、结构化返回校验
- **风险 2：越权执行**
  - LLM 调未授权 Tool
  - 防范：Tool 白名单 + 权限校验（执行器层）
- **风险 3：参数注入**
  - Tool 参数含 `; rm -rf /` 或 SQL 注入
  - 防范：参数强类型校验（Pydantic / Zod）、参数化查询、转义
- **风险 4：沙箱逃逸**
  - Agent 通过 Tool 读敏感文件 / 调网络
  - 防范：Sandbox（容器 / Firecracker）、网络白名单、文件路径校验
- **审计**：所有 Tool 调用日志化（trace），便于事后追溯

**【易错点】**
- ❌ "Tool 安全 = LLM 安全" → ✅ Tool 是执行器，最大攻击面在 Tool
- ❌ "沙箱 = 100% 安全" → ✅ 沙箱可被逃逸，必须配合审计 + 人工 review

---

## 附录 A · 配套交互图索引

| 图 | 文件 | 关键交互 |
|---|---|---|
| ReAct 循环 + Function Calling | [ai01-react-loop.html](./diagrams/ai01-react-loop.html) | Step-through 7 阶段 + Prompt 演化面板 + Tool JSON Schema |
| RAG 完整链路 | [ai02-rag-pipeline.html](./diagrams/ai02-rag-pipeline.html) | 离线/在线/Agentic 三模式 + 阶段详情 + Rerank 对比表 |
| Multi-Agent 协作架构 | [ai03-multi-agent.html](./diagrams/ai03-multi-agent.html) | 4 种模式可切换 + 单/多决策表 |
| MCP 整体架构 | [ai04-mcp-architecture.html](./diagrams/ai04-mcp-architecture.html) | 整体架构/三原语/对比 FC 三模式 + JSON-RPC 报文 |

## 附录 B · 高频考点速查

| 考点 | 题号 | 频次 |
|---|---|---|
| ReAct 循环 | Q1 | 字节 / 淘天 / 百度 |
| RAG 切分与检索 | Q22 / Q24 / Q26 | 快手 / 字节 / 阿里 |
| Function Calling 机制 | Q11 | 通用 |
| MCP 协议 | Q32 / Q33 | 腾讯 / 蚂蚁 / 字节 |
| Multi-Agent 协作 | Q5 / Q17 | 字节 / 阿里云 |
| Rerank 与 TopK | Q24 / Q42 | 快手 |
| Agent 记忆机制 | Q4 / Q43 | 阿里 / 淘天 |
| 上下文压缩 | Q6 | 快手 / 淘天 |
| Agent 评估 | Q20 | 字节 |
| Self-Reflection | Q15 | 通用 |

## 参考资料

- 牛客「AI Agent 面试 Top50 必刷题」
- 八股精 AI 应用开发与 Agent 方向 2828 条真实记录
- [GitHub Imironman123/llm-interview-notes](https://github.com/Imironman123/llm-interview-notes)
- [GitHub Zchory1106/agent-interview-hub](https://github.com/Zchory1106/agent-interview-hub)
- [Anthropic MCP 官方文档](https://modelcontextprotocol.io)
- [LangGraph 官方文档](https://langchain-ai.github.io/langgraph/)
- [OpenAI Function Calling 文档](https://platform.openai.com/docs/guides/function-calling)

---

> **使用建议**：
> 1. 先浏览 4 张交互图建立直觉（点击各阶段看实现）
> 2. 按目录速查定位到目标题号
> 3. 背诵「一句话答 + 标准答 bullets」，「易错点」重点关注
> 4. 实操题（Q8/Q9/Q20/Q44）必须配合 trace 工具看真实运行