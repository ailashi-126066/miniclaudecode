# Java 源码解析：Agent Team

Agent Team 让多个长期队员并行工作。每个队员有独立 Agent、对话和邮箱；Lead 通过 `SendMessage` 接收结果，再决定下一步。

当前实现的主入口在 `TeamCreate` 和 `Agent` 工具。它不是一个强制调度器：系统提供队员、邮箱和任务工具，谁先做什么仍由模型决定。

```text
模型调用 TeamCreate
  -> TeamManager.createTeam(...)

模型调用 Agent(team_name=...)
  -> AgentTool.runAsTeammate(...)
  -> SpawnDispatcher.spawnTeammate(...)
  -> 队员开始自己的 Agent Loop

队员发 SendMessage
  -> FileMailBox
  -> 收件人下一轮读取
  -> 作为 system reminder 进入收件人上下文
```

## 1. Team 和普通 SubAgent 的区别

普通 SubAgent 接到一个任务，做完把结果返回主 Agent。它适合一次性、边界明确的工作。

Team 队员做完当前任务后会进入空闲轮询，继续等待邮箱消息。Lead 可以继续给同一个队员发后续任务，不需要重新创建一份空白上下文。

队员之间不能直接共享对话。它们通过文件邮箱传递信息；消息在收件人的下一轮 Agent Loop 才可见，因此是异步通信。

## 2. TeamManager：内存中的团队注册表

`TeamManager` 用 `LinkedHashMap<String, Team>` 保存当前 JVM 内的团队。`Team` 保存团队名、运行模式、成员 map 和一个 `FileMailBox`。

`Member` 保存队员名、`Agent`、`ConversationManager`、活跃标记、线程和进度对象。进程内队员的运行状态就在这些字段里。

```text
TeamManager
  -> Team
      -> Member
          -> Agent + ConversationManager + thread
      -> FileMailBox
```

`TeamManager` 本身不保存团队配置。进程重启后，内存中的 Team 对象会消失。

## 3. 创建团队：TeamCreate 只搭建容器

`TeamTools.TeamCreateTool.execute(...)` 先解决重名：同名团队已存在时追加 `-2`、`-3`。

随后它调用 `TeamManager.detectBackend()`，再调用 `teamMgr.createTeam(name, mode)`。这一步只创建 Team 容器和邮箱目录，还没有创建任何队员。

```text
TeamCreate
  -> TeamManager.detectBackend()
  -> TeamManager.createTeam(...)
  -> 返回：请用 Agent(team_name=...) 添加队员
```

当前 `detectBackend()` 直接返回 `IN_PROCESS`。虽然代码中还有 tmux、iTerm 后端和 `detectPaneBackend()`，但 `TeamCreate` 没有调用后者，因此通过当前主入口创建的团队实际总是进程内模式。

## 4. 添加队员：AgentTool.runAsTeammate

主 Agent 调用 `Agent` 工具并提供 `team_name` 时，会进入 `AgentTool.runAsTeammate(...)`，而不是普通 `runSync(...)` 或 `runAsync(...)`。

它根据 `description` 生成队员名。重名时自动追加数字。

```text
Agent(team_name="refactor", description="data worker", prompt="...")
  -> runAsTeammate
  -> 过滤子 Agent 工具
  -> 额外注册 SendMessage
  -> 生成团队提示词 addendum
  -> SpawnDispatcher.spawnTeammate
```

队员 addendum 会告诉模型：自己属于哪个团队、其他成员叫什么、需要通过 `SendMessage` 通信、完成当前工作后会向 Lead 发送空闲通知。

`isolation="worktree"` 时，`runAsTeammate(...)` 也会调用 `AgentWorktree.create(...)`。子 Agent 的 `workDir` 改为该 Worktree，并在任务前收到路径转换提示。

## 5. SpawnDispatcher：按后端启动

`SpawnDispatcher` 根据 Team 的 mode 分三条路。

`IN_PROCESS` 会把成员加入 Team，使用 virtual thread 调用 `TeammateRunner.runInProcessTeammate(...)`。这是当前 TeamCreate 的实际路径。

`TMUX` 和 `ITERM` 会先把任务写进邮箱，再启动一个外部 CLI 进程。`TmuxBackend` 创建 tmux window；`ITermBackend` 用 AppleScript 创建 iTerm2 标签页。

这两条外部后端代码存在，但标准 TeamCreate 当前不会选择它们。

## 6. 进程内队员如何持续工作

`TeammateRunner.runInProcessTeammate(...)` 先把 addendum 和未读邮箱消息加入队员对话，再把初始任务作为 user message 启动 `member.agent.run(...)`。

它消费队员的事件队列。`ToolUseEvent` 会更新 `TeammateProgress`；`UsageEvent` 会累计 token；`LoopComplete` 表示当前任务完成。

第一轮结束后，队员不会立刻销毁。它向 Lead 写一条 `[idle]` 消息，然后每 500ms 轮询自己的邮箱。

```text
完成当前任务
  -> 给 Lead 发 idle 通知
  -> 每 500ms 检查邮箱
  -> 收到新普通消息
  -> 作为下一轮 user message 继续运行
```

收到以 `[shutdown]` 开头的文本消息，或线程被中断时，队员退出。退出前会用 `Transcript.saveTranscript(...)` 保存当前对话，主要用于调试。

当前停止协议只是文本前缀，不是文章中 `shutdown_request / shutdown_response` 那种结构化状态机。

## 7. SendMessage：队员如何通信

当前 `SendMessage` schema 只有两个参数：`to` 与 `content`。没有 `summary`、广播 `*`、Agent ID 寻址或结构化消息类型。

`SendMessageTool` 遍历当前 TeamManager 的团队，按队员名称找到收件人。若发送者在某团队中但收件人不在本进程成员 map，代码仍会把消息直接写入该名字对应的邮箱，兼容外部 pane 进程。

```text
SendMessage(to="bob", content="接口增加 ctx 参数")
  -> Team.sendMessage(...)
  -> FileMailBox.send("bob", message)
  -> Bob 下一轮读取并注入 system reminder
```

Lead 每轮会通过 `TeammateRunner.drainLeadMailbox(...)` 读取发给 `lead` 的未读消息。它们被包装成 `<team-notification>`，再交给 Lead 的 Agent Loop。

## 8. FileMailBox：消息如何安全落盘

每个收件人对应一个 JSON 邮箱文件和一个 `.lock` 锁文件。

发送消息时，`FileMailBox.withLock(...)` 先用 `Files.createFile(lock)` 原子创建锁。拿不到锁时随机等待 5 到 100ms，最多重试 10 次；锁超过 10 秒会被当成崩溃残留锁清理。

拿到锁后，它读取完整邮箱、追加消息、重写完整 JSON，最后删除锁。读消息不会删除内容；`markAllRead(...)` 只是把 `read` 改为 true。

`MailMessage` 有 `summary` 与 `color` 字段，但当前普通 `SendMessage` 创建消息时这两个字段为空。它也没有 `type` 字段，因此计划审批、停止确认等结构化消息尚未实现。

## 9. 任务、名称注册和外部后端：哪些代码尚未接入主 Team 链

`SharedTaskStore` 已实现 `tasks.json`、状态、assignee 和依赖字段，但当前没有被 TeamCreate 或 runAsTeammate 创建和注入。

当前 TUI 注册的是通用 `TaskList` / `TaskTools`，不是 Team 专属的 `SharedTaskStore`。因此文章里“Team 自带共享任务列表”的描述不能直接视为当前已接通功能。

`AgentNameRegistry` 也已定义名称到 Agent ID 的 map，但当前 `SendMessageTool` 没有调用它，仍按成员名称路由。

`TmuxBackend`、`ITermBackend` 和 `detectPaneBackend()` 同样已存在，但 TeamCreate 的 `detectBackend()` 暂时固定返回 `IN_PROCESS`。

## 10. Coordinator Mode：限制 Lead 自己写代码

`Coordinator.ALLOWED_TOOLS` 定义了 Lead 可用的工具白名单：队员管理、任务、消息、读取和 Bash。

`EditFile`、`WriteFile` 不在白名单中。因此 Coordinator Mode 开启时，Lead 应负责拆分、阅读、协调、合并和验证，而不是直接修改代码。

当前 TUI 会在 coordinator mode 启用时使用 `Coordinator.isCoordinatorTool(...)` 过滤 Lead 工具。模式的开关和工作流提示由 TUI 上层处理，不在 `Coordinator` 类本身。

## 11. 一句话串起来

当前 Agent Team 已接通的核心是：进程内长期队员、文件邮箱、Lead 通知、`SendMessage`、Worktree 可选隔离和 Coordinator 工具过滤。

共享任务存储、Agent ID 路由、结构化审批/停止协议，以及自动选择 tmux/iTerm 后端，当前仍是“代码组件存在但未接入标准 TeamCreate 主链”的状态。
