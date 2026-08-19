# Java 源码解析：SubAgent

本文按当前 Java 代码说明 SubAgent。它的用途是把一个独立任务交给新的 Agent 执行，例如探索代码、设计计划、跑测试或审查；主 Agent 可以等待结果，也可以让它在后台运行。

入口在 `AgentTool`。定义加载、工具过滤、创建子 Agent、等待或接收后台通知，分别由不同类处理。

```text
模型调用 Agent 工具
  -> AgentTool.execute(...)
  -> 定义式：runSync(...) 或 runAsync(...)
  -> Fork：runFork(...)
  -> Agent / SubAgentTaskManager 运行子 Agent
  -> 前台直接返回结果，后台稍后发任务通知
```

## 1. 六个核心类各做什么

`SubAgentSpec` 是 Agent 蓝图。它保存名称、说明、工具白名单、工具黑名单、系统提示词、最大轮数和模型名。

`AgentLoader` 读取 Markdown 定义文件。`ToolFilter` 从父 Agent 的工具集中筛出子 Agent 能用的工具。

`AgentTool` 是模型调用的入口。它判断这次调用是普通定义式子 Agent，还是 Fork 子 Agent，并选择前台或后台路径。

`SubAgentTaskManager` 管理后台任务。`SubAgentProgress` 是前台子 Agent 每次完成工具调用后发送给 TUI 的进度信息。

## 2. Agent 定义：YAML + Markdown

定义文件放在 `.mewcode/agents/*.md`。YAML frontmatter 是配置，Markdown body 是子 Agent 的系统提示词。

```md
---
name: explore
description: 快速定位代码位置
tools: [ReadFile, Grep, Glob]
disallowedTools: [WriteFile, EditFile]
model: haiku
maxTurns: 30
---

你负责只读搜索代码并报告位置。
```

加载后会变成 `SubAgentSpec`。其中 `tools` 是白名单；`disallowedTools` 是黑名单；body 对应 `systemPromptOverride`。

内置定义有三个：`general-purpose`、`plan`、`explore`。`plan` 和 `explore` 禁止写文件；`explore` 默认指定较快的 `haiku` 模型。

当前 `AgentLoader` 不再限制模型名。它只把大小写不同的 `inherit` 标准化为 `inherit`，其他模型名原样交给模型路由层。

## 3. 定义从哪里加载

`AgentLoader.loadAll(projectRoot)` 按以下顺序加载同名定义：

```text
内置定义
  -> ~/.mewcode/agents/
  -> <project>/.mewcode/agents/
```

后加载的同名定义覆盖先加载的。因此项目定义优先级最高，用户定义其次，内置定义最低。

定义文件没有 YAML frontmatter 时不会立刻报错；解析器会把整个文件当成 body。但 `name` 与 `description` 仍是必填字段，缺失时该文件会被跳过。

## 4. 工具过滤：定义式子 Agent 能做什么

普通定义式子 Agent 不会直接复用父 Agent 的完整工具表。`ToolFilter.filterForAgent(...)` 新建一个 `ToolRegistry`，逐个检查父工具是否应被注册进去。

过滤顺序是：

```text
父工具
  -> MCP 工具直接放行
  -> ALWAYS_DISALLOWED 全局禁止
  -> 自定义 Agent 额外禁止
  -> 异步白名单（仅 isAsync=true 时）
  -> 定义的 disallowedTools
  -> 定义的 tools 白名单
  = 子 Agent 工具表
```

全局禁止包含 `Agent`、`AskUserQuestion`、`TaskStop`、计划模式工具等。目的不是提醒模型少用，而是让这些工具根本不出现在子 Agent 的 schema 里。

MCP 工具名以 `mcp__` 开头，会在第一层直接放行。因此 MCP 工具不受后续全局黑名单和定义限制，这一点和普通工具不同。

`tools` 有值时只保留其中的工具；`disallowedTools` 再从结果中删除。`tools` 为空或只有 `*` 时，表示不额外限制白名单。

### 当前后台白名单的实际情况

`ToolFilter` 的确实现了 `ASYNC_ALLOWED` 白名单，列出后台任务可用的基础读写、搜索、命令和 Worktree 工具。

但当前 `AgentTool` 和 `SubAgentTaskManager` 都调用两参数的 `filterForAgent(registry, spec)`。该重载默认 `isAsync=false`、`isCustom=false`。

因此普通后台子 Agent 目前没有真正启用异步白名单；自定义 Agent 的额外禁止列表也没有从这些主路径启用。当前实际生效的是 MCP 直通、全局禁止、定义黑名单和定义白名单。

这是当前代码与“后台一定使用固定白名单”的设计说明不一致的地方。

## 5. AgentTool：三条执行路径

`AgentTool.execute(args)` 先检查 `description` 与 `prompt`。之后看是否传入 `subagent_type`。

传了 `subagent_type`，就是定义式子 Agent。`run_in_background=true` 时进入 `runAsync(...)`；否则进入 `runSync(...)`。

没有传 `subagent_type`，就是 Fork 路径。Fork 不等待结果，始终走后台。

```text
有 subagent_type + run_in_background=false
  -> runSync

有 subagent_type + run_in_background=true
  -> runAsync

没有 subagent_type
  -> runFork
```

## 6. 前台 runSync：主 Agent 等结果

`runSync(...)` 先过滤工具、选择模型，再创建独立的 `Agent` 和空 `ConversationManager`。定义中的系统提示词先作为 reminder 加入；调用参数中的 prompt 再作为用户消息加入。

```text
runSync
  -> ToolFilter.filterForAgent(...)
  -> new Agent(...)
  -> subAgent.run(conv)
  -> poll AgentEvent 队列
  -> LoopComplete 时返回最终文本
```

它每次 `poll(60, TimeUnit.SECONDS)` 最多等 60 秒事件。60 秒没有任何事件就返回超时错误，避免主 Agent 永久卡住。

收到 `ToolResultEvent` 时，`runSync(...)` 调用 `emitProgress(...)`。所以 TUI 能展示前台子 Agent 已用了哪个工具、输出是否出错、累计用了多少工具和时间。

## 7. 后台 runAsync：主 Agent 先继续工作

`runAsync(...)` 不自己运行子 Agent。它把模型客户端、父工具表、定义和 prompt 交给 `SubAgentTaskManager.spawnSubAgent(...)`，立刻返回 task ID。

```text
AgentTool.runAsync(...)
  -> taskManager.spawnSubAgent(...)
  -> 返回：任务已在后台启动，taskId 是 ...
```

`SubAgentTaskManager` 创建 `TaskEntry`，状态先为 `PENDING`。随后启动 virtual thread，将状态改为 `RUNNING`。

虚拟线程里创建子 Agent、启动 `subAgent.run(conv)`，然后持续消费它的 `BlockingQueue<AgentEvent>`。收到文本就累计；收到 `LoopComplete` 就保存输出并标记 `COMPLETED`；收到 `ErrorEvent` 或 60 秒无事件就标记 `FAILED`。

任务状态只有五种：`PENDING`、`RUNNING`、`COMPLETED`、`FAILED`、`CANCELLED`。`cancelTask(...)` 会先标记 `CANCELLED`，再中断对应虚拟线程。

完成或失败时，任务管理器把 `TaskNotification` 放进自己的通知列表。TUI 和 RemoteServer 会周期性调用 `drainNotifications()`，把结果作为任务通知放回主 Agent 的上下文和界面。

当前后台循环只收集文本、错误和完成事件，不会像 `runSync(...)` 一样逐工具发送 `SubAgentProgress`。因此后台任务的实时工具进度比前台路径少。

## 8. Fork：复制父对话后后台执行

Fork 用于“基于当前对话继续分支做一件事”。`runFork(...)` 会复制父 `ConversationManager` 的消息，而不是创建空对话。

复制时，普通用户/assistant 消息、thinking blocks、tool use 和 tool result 分别按对应 API 结构写入新会话。若父会话中有尚未返回结果的 tool use，会补一个 `(tool execution interrupted by fork)` 占位结果，避免工具调用链不完整。

复制完成后，Fork 会话末尾加入 `<fork_boilerplate>` 和新任务。Boilerplate 告诉 Fork 子 Agent 它是后台工作者，不应与用户直接对话，也不应再次 Fork。

Fork 不调用普通的 `spawnSubAgent(...)`，而是调用 `SubAgentTaskManager.spawnForkAgent(...)`。它使用 `ToolFilter.cloneForFork(...)` 复制父工具表，不走定义式过滤；最大轮数固定为 200。

Fork 工具表中的 `AgentTool` 会被浅复制，并带上 `querySource = agent:builtin:fork`。Fork 子 Agent 再请求 Fork 时，`runFork(...)` 先检查这个标记并拒绝；扫描 `<fork_boilerplate>` 是第二道兜底。

Fork 还会复制父 `ContentReplacementState`。这样父子对话共享历史 tool use 时，工具结果缩写和落盘引用保持一致。

## 9. Worktree 隔离

定义式前台 `runSync(...)` 支持 `isolation="worktree"`。它创建临时 Git worktree，把子 Agent 的工作目录切到该目录。

执行结束后，Worktree 没有改动就删除；有改动就保留，并把路径和分支名附加到结果，让主 Agent 知道改动在哪里。

Fork 和普通后台路径不会经过这段 `runSync(...)` 的 Worktree 创建逻辑。

## 10. 当前没有的功能

当前项目支持调用时传 `run_in_background=true`，Fork 也固定后台运行。

但代码中没有“前台运行超过 120 秒自动转后台”，也没有 `adoptRunning(...)` 把已经运行一半的前台子 Agent 移交给后台任务管理器。按 ESC 移交的完整机制同样没有实现。

## 11. 一句话串起来

定义式子 Agent 从空会话开始，先经过工具过滤，再前台同步或后台运行。Fork 子 Agent 从父对话复制上下文，始终后台运行，保留父工具表但禁止再次 Fork。
