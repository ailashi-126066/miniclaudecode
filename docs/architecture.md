# Architecture

## 模块边界

| 模块 | 单一职责 | 主要扩展点 |
| --- | --- | --- |
| `agent-core` | 领域状态、模型/工具 SPI、Prompt/Context、计划、8 节点 Workflow、验证与 Middleware | `ModelClient`、`AgentTool`、`Verifier`、`AgentMiddleware` |
| `agent-tools` | 工作区文件、命令、Web、Todo、Ask User 和安全策略 | `Tool`、`CommandPolicy` |
| `agent-rag` | AST/文本分块、Lucene、BM25/Vector/RRF 与评测 | `EmbeddingModel` |
| `agent-integrations` | 模型供应商、SQLite/JSONL/checkpoint、MCP 与 Skills 适配器 | Provider、Store、MCP transport、Skill scanner |
| `agent-cli` | Picocli/JLine 和唯一 composition root | 显式构造器装配 |

目录名直接对应能力，定位问题时先按现象选择模块：模型请求失败看
`agent-integrations/providers`，上下文或 Workflow 问题看 `agent-core`，命令被拒绝看
`agent-tools/process`，代码检索问题看 `agent-rag`。

核心依赖保持单向：

```text
agent-cli (composition root)
   ├── agent-core
   ├── agent-tools ────────────→ agent-core SPI
   ├── agent-rag ──────────────→ agent-core SPI
   └── agent-integrations ─────→ agent-core SPI
```

没有 `BaseAgent`、`SubAgent` 继承树，也没有为每个 Agent 单开类。主 Agent 是一条由
状态图表达的数据流；只读委派是受控工具能力。模型、提示词、上下文、工具和输出协议
通过小接口插入这条线，避免组件互相反向导入。

## 固定 Workflow + 有界 ReAct

```text
START
  → prepare_context
  → call_model
  ↔ execute_tools
      ├─ 需要批准 → await_approval → execute_tools
      └─ discovery 完成 → route_execution
                            ├─ SIMPLE  → call_model(DIRECT) ↔ execute_tools → verify
                            └─ PLANNED → plan_control
                                           → call_model(PLAN_STEP)
                                           ↔ execute_tools
                                           → verify
                                              ├─ RETRY  → call_model
                                              ├─ NEXT   → plan_control
                                              └─ REPLAN → plan_control（最多 1 次）
  → finish → END
```

物理图只有 8 个节点。压缩、记忆、预算、追踪和错误标准化通过有序 Middleware 或节点
内部组合完成，不再扩张图节点。Discovery 只开放只读工具；简单任务不创建 Plan；复杂任务
每个步骤仍复用同一个 ReAct 循环。Direct/Step 最多执行 2 次，整个任务最多 Replan 1 次，
没有通用 Reflection 循环。

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
