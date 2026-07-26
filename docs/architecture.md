# Architecture

## 模块

| 模块 | 职责 |
| --- | --- |
| `agent-domain` | 消息、模型流事件、工具、审批、会话等稳定端口 |
| `agent-runtime` | LangGraph4j 状态图、路由、压缩、重试、审批恢复、工具账本包装器 |
| `agent-providers` | LangChain4j 的 Anthropic/OpenAI-compatible/Ollama 流式适配 |
| `agent-tools` | 工作区安全、diff、命令、Web、Todo、Ask User 和 Tool Registry |
| `agent-rag` | AST/文本分块、Lucene 索引、BM25/Vector/RRF 与评测 |
| `agent-extensions` | MCP stdio/Streamable HTTP、resource/prompt、SKILL.md |
| `agent-persistence` | 配置、JSONL、checkpoint、权限规则、工具账本 |
| `agent-cli` | Picocli/JLine、流式渲染和全部显式装配 |

核心模块不依赖 Spring。`agent-cli` 是唯一 composition root；运行时只依赖 domain 端口，因此 Provider、工具和持久化均可用测试替身替换。

## Agent 状态图

```text
START → prepare_context → call_model ─┬→ execute_tools ─┬→ call_model
                                      │                 ├→ await_approval ── checkpoint
                                      │                 └→ finish
                                      ├→ compact_context → call_model
                                      ├→ recover_error → call_model
                                      └→ finish → END
```

审批节点使用 LangGraph4j `interruptAfter`。恢复时，CLI 用相同 graph thread id 和 `GraphInput.resume` 注入绑定过的决定。工具账本使已完成调用可复用，并对崩溃时状态不确定的外部副作用再次确认。

## 一轮请求的数据流

用户输入被追加为 JSONL `USER_MESSAGE`；Provider 的 text/thinking/usage 流同时送往 JLine renderer 和审计事件。模型产生的工具名由 LangChain4j adapter 在 Provider 安全名与本地 qualified name 间映射。Registry 创建含 workspace、session、turn、取消令牌和审批上下文的 `ToolContext`。最终状态写入 `TURN_FINAL`。

## RAG 边界

`workspace:code_search` 是普通工具。首次调用会同步增量索引，后续仅处理指纹变化文件。Java 文件由 JavaParser 拆成 type/method/constructor/field chunks；其他结构化文本走标题/段落分块，失败时回退到有界文本块。

本项目没有实现 Graph RAG。LangGraph4j 是 Agent 工作流框架；Graph RAG 是知识图检索范式，两者概念不同。
