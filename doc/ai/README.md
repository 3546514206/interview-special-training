# AI 面试专题

> **目标岗位**：大厂 AI 应用开发 / AI Agent 开发 / 大模型应用工程师（P6/P7）
> **数据来源**：牛客「AI Agent 面试 Top50」+ 八股精 2828 条真实面试记录（2025-2026 年）
> **风格**：速记版答卷 + 交互架构图配套

## 目录

| 文件 | 内容 |
|---|---|
| [AI面试真题答卷-v1.md](./AI面试真题答卷-v1.md) | Top 50 高频题速记答卷（Agent / RAG / LLM / MCP / 评估 / 工程 / Prompt 等 10 章节） |
| [diagrams/ai01-react-loop.html](./diagrams/ai01-react-loop.html) | **Q1 · ReAct 循环 + Function Calling** · 7 阶段 step-through · Prompt 演化 + Tool JSON Schema |
| [diagrams/ai02-rag-pipeline.html](./diagrams/ai02-rag-pipeline.html) | **Q21-Q26 · RAG 完整链路** · 离线索引 / 在线查询 / Agentic RAG 三模式可切换 · Rerank 对比 |
| [diagrams/ai03-multi-agent.html](./diagrams/ai03-multi-agent.html) | **Q5/Q17 · Multi-Agent 协作** · 4 种模式（单 / Supervisor / Hierarchical / Peer）+ 单 vs 多决策表 |
| [diagrams/ai04-mcp-architecture.html](./diagrams/ai04-mcp-architecture.html) | **Q32-Q34 · MCP 整体架构** · 三原语（Tools/Resources/Prompts）+ vs Function Calling + JSON-RPC 报文 |

## 快速开始

```bash
# 用浏览器打开任一交互图（双击 html 即可）
open doc/ai/diagrams/ai01-react-loop.html

# 或本地起个静态服务（方便 Chrome/Edge 看）
cd doc/ai/diagrams && python3 -m http.server 8000
# 然后访问 http://localhost:8000/ai01-react-loop.html
```

> 📌 交互图均为单文件 HTML，离线可看（仅依赖 fonts.googleapis.com + cdn.jsdelivr.net 两个 CDN）。

## 进度

- [x] Top 50 高频题答卷（10 章节 · Q1-Q50 全量）
- [x] HTML 配图 · ReAct 循环 + Function Calling（7 阶段可交互）
- [x] HTML 配图 · RAG 完整链路（3 模式可切换 + 切分策略表）
- [x] HTML 配图 · Multi-Agent 协作（4 模式可切换 + 单/多决策表）
- [x] HTML 配图 · MCP 整体架构（3 模式 + JSON-RPC 报文）
- [ ] V2 进阶（Fine-tuning / RLHF / 量化 / Self-Host）
- [ ] Q 编号索引补充（对接牛客 Top50 原题号，方便定位错题）
- [ ] 实操 Lab：LangGraph / AutoGen / CrewAI 跑通最小 Agent

## 高频考点速查

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

## 参考

- 牛客「AI Agent 面试 Top50 必刷题」
- 八股精 AI 应用开发与 Agent 方向 2828 条真实记录
- [GitHub Imironman123/llm-interview-notes](https://github.com/Imironman123/llm-interview-notes) ⭐
- [GitHub Zchory1106/agent-interview-hub](https://github.com/Zchory1106/agent-interview-hub) ⭐
- [Anthropic MCP 官方文档](https://modelcontextprotocol.io)
- [LangGraph 官方文档](https://langchain-ai.github.io/langgraph/)
- [OpenAI Function Calling 文档](https://platform.openai.com/docs/guides/function-calling)