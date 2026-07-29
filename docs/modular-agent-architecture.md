# Agent 架构重构说明

## 需求分析

原项目已有状态图、Provider、工具、RAG、MCP、Skill 和持久化能力，功能覆盖面不错；
主要结构问题是模型端口放在通用 domain、上下文算法藏在 runtime、系统提示词集中在
CLI，一个新模型或新压缩策略容易牵动多个模块。终止条件也主要依赖“没有工具调用”，
对要求 JSON 终止的模型缺少格式兜底。

本次重构的目标不是增加一套 Agent 继承体系，而是把 Agent 看作一条可组合的执行线：

```text
Prompt → Context → Model → Output Gate → Tools → Loop/Finish
```

每个阶段只暴露一个小接口，Runtime 持有流程而不持有具体厂商、具体提示词或具体命令
实现。CLI 只负责装配。

## 需求到源码映射

| 需求 | 落点 | 结果 |
| --- | --- | --- |
| 模型调用单独抽离 | `agent-model-api` | `ModelClient`、`ModelRequest`、流事件不再混在 domain |
| 模型实现可插拔 | `agent-providers/ModelProviderPlugin` | 使用 `ServiceLoader` 发现 Anthropic、OpenAI-compatible、Ollama |
| 支持模型用 YAML 管理 | `providers.<name>` | 模型、端点、thinking、输出协议和修复次数均由 profile 配置 |
| Context 单独维护 | `agent-context` | 预算规划、压缩算法、`ContextTransformer`、`ContextPipeline` |
| Prompt 单独维护 | `agent-prompt` | `PromptContributor` 可按序增删，系统 Prompt 不再散落于 Session |
| Tools 独立 | `agent-tools` | 工具继续通过统一 Registry 注册，Runtime 只依赖执行端口 |
| 避免 Agent 类继承树 | `AgentGraphFactory` | Agent 是状态图；没有 BaseAgent/子类 Agent |
| 不稳定输出兜底 | `agent-runtime/output` | 自然语言和 JSON 两种终止协议，失败后反馈模型修复 |
| 明确 Loop 终止 | Runtime 路由与 `TurnLimits` | 成功、取消、审批、重试、修复和步数上限均显式 |
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
