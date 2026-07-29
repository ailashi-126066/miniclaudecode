# Architecture

## 模块边界

| 模块 | 单一职责 | 主要扩展点 |
| --- | --- | --- |
| `agent-domain` | 消息、工具、审批、会话、事件等稳定领域类型 | `Tool`、`ApprovalGateway` |
| `agent-model-api` | 与厂商无关的模型请求、流事件和输出协议类型 | `ModelClient` |
| `agent-context` | Token 预算、压缩规划和上下文变换流水线 | `ContextTransformer` |
| `agent-prompt` | 按顺序组装系统提示词，不持有运行状态 | `PromptContributor` |
| `agent-runtime` | Query Loop 状态图、路由、重试、终止和格式修复 | `OutputProtocol` |
| `agent-providers` | Anthropic、OpenAI-compatible、Ollama 适配器 | `ModelProviderPlugin` + `ServiceLoader` |
| `agent-tools` | 工作区文件、命令、Web、Todo、Ask User 和安全策略 | `Tool`、`CommandPolicy` |
| `agent-rag` | AST/文本分块、Lucene、BM25/Vector/RRF 与评测 | `EmbeddingModel` |
| `agent-extensions` | MCP 与 `SKILL.md` 的发现、路由和按需加载 | MCP transport、Skill scanner |
| `agent-persistence` | YAML 配置、记忆、JSONL、checkpoint、权限和工具账本 | Repository/Store 接口 |
| `agent-cli` | Picocli/JLine 和唯一 composition root | 显式构造器装配 |

目录名直接对应能力，定位问题时先按现象选择模块：模型请求失败看
`agent-providers`，上下文溢出看 `agent-context`，输出无法终止看
`agent-runtime/output`，命令被拒绝看 `agent-tools/process`。

核心依赖保持单向：

```text
agent-domain
   ├── agent-model-api ──┬── agent-context
   │                     ├── agent-providers
   │                     └── agent-runtime
   └── agent-prompt

agent-cli (composition root)
   ├── runtime + providers + context + prompt
   ├── tools + rag + extensions
   └── persistence
```

没有 `BaseAgent`、`SubAgent` 继承树，也没有为每个 Agent 单开类。主 Agent 是一条由
状态图表达的数据流；只读委派是受控工具能力。模型、提示词、上下文、工具和输出协议
通过小接口插入这条线，避免组件互相反向导入。

## Agent Query Loop

```text
START
  → prepare_context
  → call_model
      ├─ 有 tool call ─────────→ execute_tools
      │                           ├─ 需要批准 → await_approval → execute_tools
      │                           └─ 已执行 ───────────────────→ call_model
      ├─ 上下文溢出 ───────────→ compact_context → call_model
      ├─ 可重试错误 ───────────→ recover_error → call_model
      ├─ 输出格式不稳定 ───────→ repair_output → call_model
      ├─ 修改后需要验证 ───────→ require_verification → call_model
      └─ 满足终止协议 ─────────→ finish → END
```

循环不是无限 `while`。`TurnLimits` 限制模型步数和工具步数，重试次数来自
`max-retries`，格式修复次数来自 `max-output-repairs`，LangGraph4j 还有独立的节点
递归上限。取消、不可重试错误、审批拒绝、步数耗尽、修复耗尽和有效最终输出都是明确
终止条件。

## 可插拔链路

一轮请求沿以下边界流动：

1. `PromptPipeline` 按 `order()` 合并多个 `PromptContributor`，并注入当前模型的输出约束。
2. `ContextPlanner` 计算预算；`ContextPipeline` 依次调用 `ContextTransformer`，且不会拆开
   assistant tool call 与对应 tool result。
3. Runtime 只调用 `ModelClient`；`ProviderFactory` 通过 Java `ServiceLoader` 发现
   `ModelProviderPlugin`，厂商 SDK 不会泄漏到 Runtime。
4. 模型工具名在 Provider 安全名和本地 qualified name 之间映射，之后交给统一
   `ToolRegistry`。
5. 没有工具调用时，`OutputProtocol` 判断是否真正结束；不稳定输出会进入有界修复回路，
   而不是在本地猜测答案。

新增 Provider 时实现 `ModelProviderPlugin`，并在
`META-INF/services/dev.miniclaudecode.providers.ModelProviderPlugin` 注册。新增提示词片段
或上下文算法只实现对应小接口，不修改主循环。

## 输出协议与终止

`natural-language` 接受非空自然语言，适合 Claude、Ollama 等正常对话模型。
`json` 要求最终输出能解析为：

```json
{"status":"completed","final":"面向用户的最终答案"}
```

解析器允许 JSON code fence 或前后少量说明文字，但字段或状态不正确时不会自行补值：
`RepairOutputNode` 把精确格式要求反馈给模型。达到 `max-output-repairs` 后仍无效，任务以
`FAILED` 结束。这一兜底层只处理终止输出，不绕过工具审批、沙箱或状态图限制。

## 配置与信任边界

支持模型以 YAML profile 描述，调用接口与配置、厂商实现相互分离。用户级配置可以选择
输出协议和命令策略；项目级配置可以覆盖非敏感模型参数，但不能包含明文 Key，也不能
覆盖 `security`。这样打开一个不可信仓库时，仓库自身无法清空命令 denylist。

命令策略按以下顺序裁决：

1. 命中 `deny-fragments`：硬拒绝，审批也不能放行。
2. 命中 `allow-prefixes`：只对非高危命令快速放行；高危/严重命令仍交给风险分类器。
3. 未命中：`allowlist-only: true` 时拒绝，否则进入风险分类和审批。
4. 获准执行后仍进入 OS 沙箱；策略、审批、沙箱是三层不同防线。

## 设计参考

本次重构借鉴而不复制两套开源结构：

- [AgentScope](https://github.com/agentscope-ai/agentscope) 将 model、formatter、memory、
  tool/toolkit、pipeline 等能力做成清晰组件，本项目据此强化物理模块边界。
- [Pi mono](https://github.com/badlogic/pi-mono) 将 provider、agent loop 和 coding-agent
  分包，并把上下文变换、工具和循环事件作为组合点；本项目采用类似的“小核心 +
  可插拔流水线”，但继续使用 Java、Maven 与 LangGraph4j。

实现细节和图片需求逐项映射见
[Agent 架构重构说明](modular-agent-architecture.md)。

## RAG 边界

`workspace:code_search` 是普通工具。首次调用会同步增量索引，后续仅处理指纹变化文件。
Java 文件由 JavaParser 拆成 type/method/constructor/field chunks；其他结构化文本走
标题/段落分块，失败时回退到有界文本块。

本项目没有实现 Graph RAG。LangGraph4j 是 Agent 工作流框架；Graph RAG 是知识图检索
范式，两者概念不同。
