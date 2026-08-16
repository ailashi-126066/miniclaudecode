# MiniClaudeCode × MewCode 能力融合实施计划

> 状态：已完成
> 更新日期：2026-08-16
> 目标项目：`miniclaudecode`
> 参考实现：`mewcode/code-java`

## 1. 最终目标

以 `miniclaudecode` 为产品和代码库主体，保留它已有的安全边界、审批、工具执行账本、RAG、记忆、MCP、Skills、会话持久化和验证能力；运行时主链路改造成与 MewCode 一致的、显式且容易理解的传统 Agent Loop，并吸收 MewCode 的下列成熟能力：

- TUI4J 全屏交互体验。
- Provider 流式事件和 thinking block。
- 上下文超限与 rate limit 分类重试。
- `max_tokens` 自动续写。
- deferred tool schema / `ToolSearch`。
- 计划模式。
- 并行只读工具、串行写工具。
- fork、后台 Agent、team 和后台完成通知。
- 上下文压缩、外置大工具结果和恢复附件。

同时避免复制 MewCode 当前最明显的结构问题：不允许把装配、TUI 状态、命令、审批和会话切换重新集中到一个类似 `MewCodeModel` 的 God Class 中。

Agent 交互方式统一迁移为 TUI4J 全屏界面：删除旧的行式 REPL/交互 CLI，同时删除面向脚本、CI 和管道的一次性非交互 `run` 模式。无参数启动默认进入全屏 TUI；`config`、`index`、`rag` 等管理和诊断命令继续存在。

## 2. 核心架构决定

### 2.1 唯一主链路采用显式 Agent Loop

目标运行链路为：

```text
MiniClaudeCode.main
  → ApplicationBootstrap 创建并连接组件
  → TuiApplication 启动全屏界面
  → TurnCoordinator 提交用户回合
  → AgentLoop 迭代
      → ContextManager 检查并压缩上下文
      → ModelClient 发起 LLM 流式请求
      → StreamAssembler 收集文本、thinking 和 Tool Call
      → ToolScheduler 执行工具
          → READ_ONLY 工具受控并行
          → WRITE/EXECUTE 工具严格串行
      → Tool Result 写回会话
      → 有工具调用：进入下一轮
      → 无工具调用：验证并结束
  → UiEventBus 把运行事件投递给 TUI
```

这条循环是唯一的 Agent 编排引擎。现有 `AgentGraphFactory` 中已验证的节点逻辑应迁移为循环所调用的服务，不长期保留“LangGraph 主循环 + 手写主循环”两套执行路径。

迁移期间可以存在短期兼容适配器，但切换完成后必须满足：

- 所有交互请求只进入 `AgentLoop`。
- checkpoint/resume、审批恢复和错误恢复行为保持不变或更强。
- 不再由 LangGraph4j 决定主流程路由。
- 不重复实现工具执行、安全策略、账本、RAG、记忆和 Provider 客户端。

### 2.2 `MewCodeModel` 的职责必须拆开

目标中不创建一个“新的 MewCodeModel”。组件边界如下：

| 组件 | 单一职责 | 禁止承担的职责 |
| --- | --- | --- |
| `ApplicationBootstrap` | 创建依赖、装配组件、生命周期关闭 | TUI 状态、Agent Loop 规则、业务命令 |
| `TuiApplication` | 启动/停止 TUI4J，连接事件与输入 | Provider、工具执行、会话存储 |
| `TuiState` | 不可变界面状态 | 执行 I/O、直接调用 Agent |
| `TuiReducer` | `TuiState + UiEvent → TuiState` | 组件装配、网络请求 |
| `TuiView` | 根据状态绘制界面 | 修改业务状态 |
| `CommandDispatcher` | 解析并分发斜杠命令 | 保存全局可变状态 |
| `ApprovalController` | 展示审批并返回决定 | 自行绕过权限规则 |
| `SessionController` | 新建、切换、恢复会话 | 模型调用和工具调度 |
| `TurnCoordinator` | 提交、取消、等待一次回合 | 控制循环内部细节 |
| `AgentLoop` | 驱动一次完整 Agent 回合 | TUI 绘制、依赖装配 |
| `ContextManager` | token 估算、压缩、恢复附件 | Provider UI 和命令处理 |
| `StreamAssembler` | 归并流式文本、thinking、tool calls、usage | 执行工具 |
| `ToolScheduler` | 按副作用等级调度工具 | 修改 UI 状态 |
| `BackgroundAgentManager` | fork/background 生命周期和通知 | team 持久化 |
| `TeamManager` | 团队、任务、邮箱和成员状态 | 主 Agent 循环实现 |

约束：单个生产类达到约 500 行时必须评审拆分；TUI 状态更新必须通过事件和 reducer 完成，禁止让任意服务直接修改界面对象。

### 2.3 保留 MiniClaudeCode 的核心资产

下列能力继续作为基础设施使用，而不是从 MewCode 重写：

- 工具权限、风险分级和人工审批。
- `LedgeredToolExecutor` 与工具执行记录。
- workspace 路径约束和命令安全策略。
- RAG、记忆、MCP、Skills 和 Provider 适配。
- session event store、恢复机制和使用量统计。
- 计划、验证、完成条件和取消令牌。
- `DelegatedAgentTool`、隔离 worktree 等已有委派基础。

## 3. MewCode 到 MiniClaudeCode 的映射

| MewCode 概念 | MiniClaudeCode 目标实现 | 处理方式 |
| --- | --- | --- |
| `MewCode.main` | `MiniClaudeCode.main` | 保留入口，改为调用 `ApplicationBootstrap` |
| `MewCodeModel` | Bootstrap + TUI reducer/view/controllers | 拆分，禁止照搬 God Class |
| `Agent.run` 无限迭代 | `AgentLoop.runTurn` | 成为唯一主流程 |
| `ContextCompactor` | `ContextManager` + `RecoveryAttachmentService` | 复用现有语义压缩并补齐恢复附件 |
| Provider stream | `ModelClient` + `StreamAssembler` | 统一事件协议 |
| `StreamingExecutor` | `ToolScheduler` + `LedgeredToolExecutor` | 保留安全账本，吸收读并行/写串行策略 |
| deferred tools | `DeferredToolRegistry` + `ToolSearchTool` | 会话级发现，checkpoint 可恢复 |
| fork/background | `BackgroundAgentManager` | 使用同一 Agent Loop，不创建第二类 Agent |
| team | `TeamManager` + mailbox + task store | 先做进程内实现，终端后端可插拔 |
| TUI4J | `cli.tui` 包 | 全屏 UI，事件驱动 |

## 4. 目标 Agent Loop 设计

### 4.1 循环伪代码

```java
AgentTurnResult runTurn(AgentTurnRequest request) {
    AgentSession session = sessionService.open(request.sessionId());
    retryState.resetForTurn();

    while (!request.cancellationToken().isCancelled()) {
        PreparedContext context = contextManager.prepare(session);
        ModelStream stream = modelClient.stream(context.modelRequest());
        ModelResponse response = streamAssembler.collect(stream, eventSink);

        session.append(response.assistantMessage());
        usageTracker.record(response.usage());

        if (response.stopReason() == MAX_TOKENS) {
            continuationService.appendContinuationInstruction(session, response);
            continue;
        }

        if (response.toolCalls().isEmpty()) {
            return completionVerifier.finishOrRequestRepair(session, response);
        }

        List<ToolResult> results = toolScheduler.execute(
                response.toolCalls(), request.cancellationToken());
        session.appendToolResults(results);
        checkpointService.saveIterationBoundary(session);
    }

    return AgentTurnResult.cancelled(session.id());
}
```

实际实现必须把异常分类放在循环边界内：

- context overflow：强制压缩一次并立即重试；同一上下文只允许有限次数。
- rate limit / 429：读取 Provider retry hint；否则指数退避并加入 jitter。
- 短暂网络错误 / 5xx：有限重试，不重复已确认的写工具。
- auth / invalid request：立即失败并给出可操作错误。
- `max_tokens`：保存已生成内容后发起续写，禁止丢失 thinking/tool call 片段。
- 用户取消：停止流式请求和未开始的工具；已经开始的写工具必须记录最终状态。

### 4.2 流式事件协议

核心层定义稳定的 `AgentEvent`/`ModelStreamEvent`，至少覆盖：

- turn started/completed/failed/cancelled。
- text delta。
- thinking started/delta/completed。
- tool call started/input delta/ready。
- tool execution queued/started/completed/failed。
- approval requested/resolved。
- retry scheduled。
- context compact started/completed。
- usage updated。
- plan created/step changed。
- background/team notification。

TUI 只消费事件，不参与解析 Provider 私有事件。Provider adapter 必须先转换为统一事件。

### 4.3 工具并行策略

沿用 MewCode 中合理的基本原则，并增加 MiniClaudeCode 的安全约束：

1. 根据 `ToolEffect` 把一次响应中的调用分组。
2. `READ_ONLY` 调用使用有界虚拟线程并行执行。
3. 任意 `WRITE`、`EXECUTE`、未知副作用调用都按模型返回顺序串行执行。
4. 如果调用之间存在显式依赖，全部串行。
5. 同一路径的读取与写入不得并发。
6. 每次执行仍经过权限检查、审批和 `ToolExecutionLedger`。
7. 并行度默认不超过 4，可配置但必须有硬上限。
8. 工具结果按原始 Tool Call 顺序写回，不能按完成顺序打乱。

## 5. 分阶段实施

### 阶段 0：冻结并修复当前基线

目标：先让当前 `miniclaudecode` 成为可信迁移基线。

- [x] 审核当前工作树 38 项变更，按功能拆分并提交；不把生成物或无关修改混入提交。
- [x] 运行并保存完整 `mvn verify` 的可复现证据。
- [x] 修复 `agent-core` 当前约 23.3% 覆盖率与 50% 门槛的冲突。
- [x] 优先补测试，不把降低覆盖率门槛作为最终修复。
- [x] 校正文档中的 Middleware 描述：实现真实链路或明确写成扩展点，不能继续声称空列表已生效。
- [x] 校正“人工审批记忆”描述和实现。
- [x] 校正 ONNX embedding benchmark 与生产 wiring 的偏差。
- [x] 确认所有模块测试、Spotless、SpotBugs、JaCoCo 在 Java 23 下可复现。

记忆审批目标规则：

- 模型推断出的长期记忆一律先进入 `PENDING_REVIEW`。
- 用户明确陈述并明确要求记住的内容可以直接进入 `ACTIVE`，但必须保留来源证据。
- TUI 提供批准、拒绝、编辑和批量查看入口。
- 未审批记忆不能参与普通 prompt 注入。

验收：

- 五个模块覆盖率都达到项目声明的最低门槛。
- `mvnw.cmd verify` 零失败并生成完整报告。
- README 与实际生产 wiring 一致。

### 阶段 1：抽取显式 Agent Loop

目标：把现有图节点能力迁移为服务，并以传统循环接管主链路。

新增建议：

```text
agent-core/.../runtime/loop/
  AgentLoop.java
  AgentTurnRequest.java
  AgentTurnResult.java
  AgentLoopPolicy.java
  StreamAssembler.java
  ContinuationService.java
  ErrorClassifier.java
  RetryCoordinator.java
```

实施项：

- [x] 从 `AgentGraphFactory` 提取上下文准备、模型调用、工具执行、审批等待、验证和恢复逻辑。
- [x] 保持 `MiniClaudeState` 或设计等价的 `AgentSessionState`，避免迁移时破坏持久化格式。
- [x] 让每一轮模型调用与工具结果形成明确 iteration boundary。
- [x] 将 checkpoint/resume 接入循环，而不是依赖图节点隐式跳转。
- [x] 用契约测试对比旧图和新循环在相同 fixture 下的消息、工具顺序、审批和最终结果。
- [x] 默认入口切换到新循环。
- [x] 删除或降级仅用于迁移的旧图编排代码和依赖。

验收：

- 无工具、单工具、多工具、审批、失败恢复、取消、续写均通过端到端测试。
- 写工具在重试场景不重复执行。
- 生产入口只存在一条 Agent 编排路径。

### 阶段 2：建立 UI 事件边界并接入 TUI4J

目标：全屏 TUI 替换当前行式 REPL，但 UI 与 Agent Loop 解耦。

新增建议：

```text
agent-cli/.../tui/
  TuiApplication.java
  TuiState.java
  TuiReducer.java
  TuiView.java
  TuiEventBridge.java
  InputController.java
  CommandDispatcher.java
  panes/ConversationPane.java
  panes/ThinkingPane.java
  panes/ToolPane.java
  panes/PlanPane.java
  panes/TeamPane.java
  dialogs/ApprovalDialog.java
  dialogs/MemoryReviewDialog.java
  dialogs/SessionDialog.java
```

实施项：

- [x] TUI4J 依赖只放在 `agent-cli`，核心模块不得依赖终端 UI。
- [x] 把现有 `StreamingRenderer` 的能力转成 `TurnEvent` → `TuiEvent` 消费链路。
- [x] 支持 conversation、thinking、tool、plan、usage、session、background/team 状态。
- [x] 审批使用非阻塞 TUI 对话状态，决定通过异步回调返回 Agent Loop。
- [x] 第一次 `Ctrl+C` 取消当前回合，空闲时再次操作才退出应用。
- [x] 终端生命周期交由 TUI4J `Program.withAltScreen()` 管理。
- [x] 删除 `RunCommand`、`run` 子命令及其专属帮助文本、测试和文档。
- [x] 删除 `Repl`、旧交互入口及其专属测试和帮助文本。
- [x] 保留 `config`、`index`、`rag` 等非 Agent 管理命令。
- [x] 将现有斜杠命令迁移到独立 `CommandDispatcher`。

验收：

- Windows Terminal 中可稳定启动、缩放、切换会话、审批、取消和退出。
- TUI reducer 可无终端环境单元测试。
- `TuiApplication` 不持有 Provider、工具实现或数据库细节。

### 阶段 3：deferred tool schema / ToolSearch

目标：默认只向模型暴露少量核心工具，需要时再发现其余工具，降低 schema token 成本。

设计：

- `ToolDescriptor` 增加 `EAGER` / `DEFERRED` 暴露策略及 tags、namespace、summary。
- `ToolSearchTool` 永远 eager，支持按名称、标签、能力和自然语言查询。
- `DeferredToolRegistry` 保持不可变完整注册表；`DiscoveredToolSet` 保存会话可见集合。
- 工具发现结果写入 checkpoint 和恢复附件。
- MCP 动态工具进入相同注册表，保持命名空间、权限和冲突规则。

实施项：

- [x] 定义最小 eager 工具集合和最大 schema token 预算。
- [x] 搜索结果只返回必要摘要，明确选择后才注入完整 schema。
- [x] 发现、选择和调用全部写入审计事件。
- [x] 未发现工具被直接调用时返回可恢复错误并提示先搜索。
- [x] 禁止在并发回合中原地修改共享注册表。
- [x] 会话恢复后保持已发现工具，但工作区变化时重新验证可用性。

验收：

- 大工具集场景的初始 prompt/schema token 明显下降。
- ToolSearch → 发现 → 调用流程可端到端运行。
- MCP、权限审批和工具账本不被绕过。

### 阶段 4：成熟的上下文压缩与恢复附件

目标：在现有语义压缩基础上吸收 MewCode 的恢复设计，压缩后仍能继续正确工作。

压缩策略：

1. 大工具结果先外置到 `ToolResultStore`，对话中保留稳定引用、摘要、大小和 hash。
2. 老工具结果按预算截断，但保持 tool call/result 配对。
3. Provider 实际 token usage 作为估算锚点。
4. 达到软阈值时调用模型生成结构化摘要；达到硬阈值时强制压缩后重试。
5. 保留最近若干轮原始消息。
6. 写入 compact boundary，原始事件仍可审计。
7. 把继续执行所需状态作为恢复附件，而不是只依赖自然语言摘要。

`RecoveryAttachment` 至少包含：

- 当前目标、结构化计划、当前步骤和完成证据。
- 最近读取文件的路径、hash、读取范围和必要片段。
- 已修改文件、未提交状态及已执行验证。
- 已加载 Skills/SOP 及其版本或 hash。
- 已发现 deferred tools。
- pending approval 和 memory review。
- background Agent、team 成员和任务摘要。
- 外置工具结果引用。
- Provider usage 锚点和 compact boundary id。

安全要求：

- 附件有独立字节/token 预算。
- secret、token、环境变量值在持久化前脱敏。
- 文件 hash 不匹配时标记 stale，禁止把旧片段当作当前事实。
- 恢复附件是结构化数据，不能把后台通知原样当成可信用户指令。

验收：

- 在压缩前后执行同一多步骤 fixture，计划进度、工具可见性、文件状态和审批不丢失。
- 会话重启后可从 compact boundary 恢复。
- 外置结果缺失或损坏时给出明确降级行为。

### 阶段 5：fork 与后台 Agent

目标：扩展现有 `DelegatedAgentTool`，所有子 Agent 复用同一 `AgentLoop`。

模型：

- `ISOLATED`：只接收任务、系统约束和显式附件。
- `FORK`：复制经过裁剪的父会话快照、计划和已发现工具。
- `BACKGROUND`：立即返回 task id，在后台运行并通过事件通知。

`BackgroundAgentManager` 保存：

- task id、parent session/turn id。
- 状态、创建/开始/结束时间。
- 输入摘要、权限上限、模型配置。
- cancellation token。
- 结果摘要、完整结果引用、错误。
- worktree 信息和清理状态。

实施项：

- [x] 并发上限默认 4，使用有界虚拟线程执行。
- [x] 子 Agent 权限只能等于或小于父 Agent，不能自行扩大。
- [x] 默认禁止子 Agent 再 fork；需要时必须有深度和总数上限。
- [x] 后台完成事件进入 TUI 通知中心，并作为结构化事件关联父会话。
- [x] 提供 list/status/wait/cancel/result 操作。
- [x] 写任务默认使用隔离 worktree；不自动合并、不自动删除有未保存修改的 worktree。
- [x] 主会话退出时按策略等待、取消或保留可恢复后台任务。

验收：

- 后台启动不阻塞前台输入。
- 完成、失败和取消通知不会丢失。
- 子 Agent 无法绕过权限、账本和路径安全。
- 并发、递归和资源用量均有硬上限。

### 阶段 6：Team、任务和通知机制

目标：提供 lead/member、任务分派、邮箱和状态面板，而不是把 team 简化成一次并行调用。

核心对象：

```text
TeamManager
TeamState
TeamMember
TeamTask
TeamTaskStore
AgentMailbox
TeamNotification
```

实施顺序：

1. 先完成跨平台的进程内 team backend。
2. 再增加可选的 tmux backend。
3. 再增加可选的 iTerm backend。

Windows 默认使用进程内 backend；tmux/iTerm 只能是适配器，不能成为核心功能的前置条件。

实施项：

- [x] 支持 create、join、assign、message、status、stop、archive。
- [x] lead 负责拆分任务、审批写入和汇总结果。
- [x] mailbox 消息具有 sender、recipient、task id、类型和时间戳。
- [x] team 状态可持久化并在崩溃后恢复。
- [x] writer member 使用独立 worktree；主工作树写入必须经过审查。
- [x] TUI `TeamPane` 显示成员状态、当前任务、消息和结果。
- [x] 后台通知与普通模型内容分离，禁止提示注入式通知改变权限。
- [x] 停止和归档操作不得静默删除未合并工作。

验收：

- 至少 1 lead + 3 members 的任务可并发执行、通信、取消和恢复。
- 成员崩溃不会阻塞整个 team。
- 任务状态转换可审计，没有孤儿任务或静默丢失结果。

### 阶段 7：Provider 韧性、计划模式和行为对齐

目标：补齐 MewCode Agent Loop 已处理的运行细节，并用测试固化。

- [x] Anthropic、OpenAI、OpenAI-compatible adapter 使用统一流式事件协议。
- [x] thinking block 可显示、折叠、持久化策略可配置。
- [x] context overflow、rate limit、网络错误、auth 错误分类明确。
- [x] `max_tokens` 续写不会重复工具调用或丢失半成品内容。
- [x] 计划模式只允许读工具、搜索、计划编辑；写工具必须退出计划模式或单独审批。
- [x] usage 和预算跨续写、重试、fork 和 team 正确累计。
- [x] Provider 不支持的能力通过 capability negotiation 降级。

验收：

- 使用可控 fake Provider 覆盖所有 stop reason、错误和断流情形。
- 三类 Provider 的相同事件 fixture 产生相同领域事件。
- 重试次数、退避和费用上限可配置且可观测。

### 阶段 8：文档、CI 和发布验收

- [x] 更新 README 的架构图、启动方式、快捷键和命令列表。
- [x] 记录从旧交互 REPL 和非交互 `run` 迁移到 TUI 的说明。
- [x] 记录 deferred tools、后台 Agent、team 和压缩恢复的数据格式。
- [x] 为 Windows、Linux、macOS 建立 CI 矩阵；终端相关测试分为 reducer 测试和 smoke test。
- [x] 在 CI 中强制每模块 JaCoCo 门槛、Spotless、SpotBugs、单元测试和集成测试。
- [x] 生成可运行 fat jar；启动 Agent 时如果没有可用 TTY，给出清晰错误和帮助，不得静默切换到非交互模式。
- [x] 发布前完成会话格式兼容和迁移测试。

## 6. 重点文件改造清单

| 当前文件 | 计划动作 |
| --- | --- |
| `agent-cli/.../MiniClaudeCode.java` | 缩减为入口；调用 bootstrap 和 TUI |
| `agent-cli/.../Repl.java` | 已删除，由 TUI4J 替代 |
| `agent-cli/.../StreamingRenderer.java` | 已删除，由 `TurnEvent` → `TuiEvent` 桥接替代 |
| `agent-cli/.../commands/RunCommand.java` | 已删除非交互 Agent 运行模式 |
| `agent-cli/.../app/ApplicationSession.java` | 只保留会话级资源和生命周期 |
| `agent-cli/.../app/TurnCoordinator.java` | 调用 `AgentLoop`，负责 turn 级取消和等待 |
| `agent-cli/.../app/ToolWiringFactory.java` | 注册 eager/deferred 工具和 ToolSearch |
| `agent-cli/.../app/DelegatedAgentTool.java` | 接入 fork/background manager |
| `agent-core/.../runtime/AgentGraphFactory.java` | 分阶段提取能力，切换后退出主链路 |
| `agent-core/.../runtime/SemanticCompactContextNode.java` | 逻辑迁移到 `ContextManager` |
| `agent-core/.../runtime/LedgeredToolExecutor.java` | 继续作为所有工具调用的强制边界 |
| `agent-core/.../runtime/retry/RetryPolicy.java` | 扩展 Provider 错误分类和 retry hint |
| `agent-core/.../runtime/middleware/*` | 实现真实中间件或修正文档，禁止空链路虚假宣称 |

## 7. 测试策略

### 7.1 单元测试

- Agent Loop 路由表和终止条件。
- `StreamAssembler` 的分片、乱序保护和未完成 block。
- 错误分类、退避和 `max_tokens` 续写。
- 工具读并行/写串行和结果顺序。
- deferred registry、搜索、发现集和恢复。
- TUI reducer、命令分发和审批 future。
- recovery attachment 的预算、脱敏、stale 检测。
- background/team 状态机和 mailbox。

### 7.2 集成测试

- fake Provider 驱动完整无工具/工具循环。
- checkpoint → 进程重启 → 审批继续。
- 压缩 → 会话重启 → 继续修改和验证。
- MCP deferred tool 发现后调用。
- fork/background 完成通知返回父会话。
- team writer worktree 生成变更但不自动合并。
- TUI headless reducer 测试和 PTY smoke test。

### 7.3 安全回归

- 路径穿越、符号链接逃逸和危险命令。
- deferred tool 不得绕过审批。
- 子 Agent 和 team member 不得权限升级。
- 后台消息不得作为可信 system/user 指令注入。
- 压缩附件不得持久化 secret。
- 写工具在模型重试和会话恢复中至多执行一次。

### 7.4 每阶段验证命令

Windows：

```powershell
.\mvnw.cmd spotless:check
.\mvnw.cmd verify
java -jar agent-cli\target\mini-claude-code.jar --help
java -jar agent-cli\target\mini-claude-code.jar
```

最后一条是人工 TUI smoke test；不再提供非交互 Agent `run` 接口。

## 8. 里程碑与提交边界

建议每个里程碑独立提交，避免 38 项基线变更与架构迁移混在一起：

1. `baseline`: 清理工作树、修复覆盖率和文档漂移。
2. `agent-loop`: 显式循环、事件协议、兼容测试。
3. `tui`: TUI4J、状态 reducer、审批和会话界面。
4. `deferred-tools`: ToolSearch、会话发现集、MCP 对接。
5. `compaction`: 外置结果、恢复附件、compact boundary。
6. `background-agents`: fork、后台生命周期、通知中心。
7. `teams`: task store、mailbox、worktree 和可选终端 backend。
8. `hardening`: Provider 韧性、CI、文档和发布验收。

每个提交必须满足：可编译、相关测试通过、没有关闭既有安全检查、没有把半迁移的第二主链路设为默认。

## 9. 明确不做的事项

- 不把 `code-java` 已删除的 Go 源码重新带入项目。
- 不照搬约 2000 行的 `MewCodeModel`。
- 不长期维护 LangGraph 和传统循环两套主运行时。
- 不为追求功能对齐而绕过 MiniClaudeCode 的审批、账本和 workspace 安全。
- 不自动合并或静默删除 Agent worktree。
- 不让 tmux/iTerm 成为 Windows 或核心 team 功能的必需依赖。
- 不保留或重新引入非交互 Agent `run` 模式。
- 不以降低覆盖率门槛代替测试缺口修复。

## 10. 总体验收标准（Definition of Done）

全部满足后才视为本计划完成：

- [x] `MiniClaudeCode.main → bootstrap → TUI → AgentLoop → compaction → stream → tools → results → finish` 是唯一生产主链路。
- [x] Agent Loop 覆盖 thinking、超限重试、rate limit、续写、计划模式和取消。
- [x] 只读工具受控并行，写/执行工具严格串行且全部进入安全账本。
- [x] TUI4J 支持会话、流式内容、thinking、工具、审批、计划和通知。
- [x] 没有任何类重新承担 `MewCodeModel` 式跨层职责。
- [x] deferred tool schema / ToolSearch 可用且 checkpoint 后可恢复。
- [x] fork、后台 Agent、team、mailbox、取消和完成通知可用。
- [x] 压缩后计划、文件、Skill、工具发现、审批及后台任务状态不丢失。
- [x] 旧交互 REPL/CLI 已删除，TUI4J 成为默认交互界面。
- [x] 非交互 Agent `run` 已删除，Agent 只通过 TUI4J 运行。
- [x] Middleware、记忆审批和 ONNX 文档与生产实现一致。
- [x] 所有模块覆盖率达到 50% 门槛，完整 `mvn verify` 可重复通过。
- [x] Windows 为一级支持平台；Linux/macOS 无结构性退化。

## 11. 推荐的实际执行顺序

严格按以下顺序推进，除非某阶段的契约测试已为并行工作建立稳定边界：

```text
基线可信
  → 显式 Agent Loop
  → 统一事件协议
  → TUI4J
  → deferred tools
  → 压缩恢复附件
  → fork/background
  → team
  → Provider hardening
  → 全量发布验收
```

首个实现任务应是“阶段 0：冻结并修复当前基线”，而不是立即改 TUI；否则现有 38 项变更、覆盖率冲突和文档漂移会让后续回归无法归因。
