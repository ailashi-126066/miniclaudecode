# Agent 架构重构说明

## 需求分析

原项目已有状态图、Provider、工具、RAG、MCP、Skill 和持久化能力，功能覆盖面不错；
当前实现已把原 12 个 Maven 模块收敛为 5 个，并将运行时图收敛为固定 8 节点。
`agent-core` 保存稳定 SPI 与 Workflow；工具、RAG 和供应商/持久化适配器只依赖 core；
CLI 是唯一装配入口。

本次重构的目标不是增加一套 Agent 继承体系，而是把 Agent 看作一条可组合的执行线：

```text
Prompt → Context → Model → Output Gate → Tools → Loop/Finish
```

每个阶段只暴露一个小接口，Runtime 持有流程而不持有具体厂商、具体提示词或具体命令
实现。CLI 只负责装配。

## 需求到源码映射

| 需求 | 落点 | 结果 |
| --- | --- | --- |
| 模型与工具端口 | `agent-core` | `ModelClient`、`AgentTool`、状态、计划与验证接口集中维护 |
| 模型实现可插拔 | `agent-integrations/providers` | 使用 `ServiceLoader` 发现 Anthropic、OpenAI-compatible、Ollama |
| 支持模型用 YAML 管理 | `providers.<name>` | 模型、端点、thinking、输出协议和修复次数均由 profile 配置 |
| Context/Prompt | `agent-core` | 预算、压缩和 `PromptContributor` 保持小接口，但不再各占 Maven 模块 |
| Tools 独立 | `agent-tools` | 工具继续通过统一 Registry 注册，Runtime 只依赖执行端口 |
| 避免 Agent 类继承树 | `AgentGraphFactory` | Agent 是状态图；没有 BaseAgent/子类 Agent |
| 固定 Workflow | `agent-core/AgentGraphFactory` | 8 个物理节点，Simple Direct 与 Planned Step 复用 ReAct |
| 明确 Loop 终止 | `VerificationPipeline` 与有界计数 | Direct/Step 最多 2 次、Replan 最多 1 次 |
| 轻量长期记忆 | `agent-integrations/persistence/memory` | SQLite/FTS5、确定性写入门控、去重/冲突/异步整合 |
| 沙箱黑白名单 | `CommandPolicy` + `security.shell` | deny 优先、可选严格 allowlist、项目配置不能弱化策略 |

## 扩展示例

### 新增 Prompt 片段

实现 `PromptContributor`，提供稳定 `id()`、排序值 `order()` 和
`contribute(PromptBuildContext)`。把实例加入 `PromptPipeline` 即可，不需要修改
`AgentGraphFactory`。

### 新增 Context 策略

实现 `ContextTransformer` 并加入 `ContextPipeline`。策略应保持消息顺序，尤其不能把
assistant 的 tool call 与对应 tool result 拆开。压缩摘要应被视为新上下文，而不是伪造
原始对话。

### 新增模型 Provider

实现 `ModelProviderPlugin`，在 `META-INF/services` 注册，并把厂商流事件规范化成
`ModelStreamEvent`。Provider 不应导入 CLI、工具实现或状态图节点。

### 新增输出协议

实现 `OutputProtocol`，返回 `Evaluation`：

- `valid=true`：提供规范化 `finalText`，允许结束。
- `valid=false`：提供可直接反馈给模型的 `repairInstruction`。

修复必须有次数上限，禁止“解析失败就猜一个成功答案”。

## Loop 不变量

- 只有 `OutputProtocol` 验证通过的正常响应才可进入 `COMPLETED`。
- 模型产生工具调用时，文本不是最终答案。
- 每个模型调用、工具调用、重试、压缩、验证和格式修复都受独立计数约束。
- 取消不可重试；不可重试 Provider 错误直接结束。
- 工具审批中断可 checkpoint/resume，恢复后仍使用相同 session/thread id。
- 修改类工具执行后，若开启验证门，必须有后续成功验证结果。
- 格式修复只改变消息和修复计数，不提升工具权限。

## 文件命名约定

- `*Api` / `*Client`：稳定调用端口。
- `*Plugin`：可发现的厂商或能力适配器。
- `*Pipeline`：有确定顺序的可插拔组合。
- `*Contributor` / `*Transformer`：流水线中的单个插件。
- `*Protocol`：输入/输出的可验证契约。
- `*Policy`：安全裁决规则；`*Classifier` 只负责风险识别。
- `*Node`：状态图单步；`*Router`：只决定下一条边。
- `*Factory`：装配，不承载业务决策。

按这些后缀搜索即可快速定位责任，避免出现 `Utils`、`Manager`、`Handler` 一类语义过宽
的文件名。
