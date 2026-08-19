# Java 源码解析：Worktree

Worktree 用来给会写文件的子 Agent 一份独立工作目录。主 Agent 留在项目原目录；子 Agent 在 `.mewcode/worktrees/` 下的副本中修改、测试或提交，双方不会同时写同一份文件。

当前实现不是单个 `WorktreeManager`。`worktree/` 包里分别处理创建、子 Agent 隔离、会话、创建后初始化、变更检查和过期清理。

```text
手动进入 Worktree
  EnterWorktreeTool
  -> WorktreeManager.create(...)
  -> WorktreeSessionStore.save(...)

子 Agent 隔离
  AgentTool.runSync(..., isolation="worktree")
  -> AgentWorktree.create(...)
  -> subAgent.setWorkDir(worktreePath)
  -> 子 Agent 完成后检查改动
  -> 无改动删除；有改动保留
```

## 1. 为什么需要 Worktree

普通 Git 分支不能让两个 Agent 同时使用不同文件内容。因为切换分支会改变同一个工作目录。

Git Worktree 会创建第二个真实目录。例如主 Agent 使用 `project/`，子 Agent 使用 `project/.mewcode/worktrees/agent-a1234567/`。两个目录共享 Git 历史，但文件副本互不覆盖。

因此 Worktree 主要用于并行或会修改文件的 Agent。只读、前台且主 Agent 等待结果的子任务不一定需要它。

## 2. 相关类分别做什么

`WorktreeManager` 是通用管理器。它提供 `create`、`remove`、`list`、`cleanupStale` 和 `detectChanges`。

`AgentWorktree` 是给子 Agent 用的轻量入口。它生成 Worktree 分支、快速恢复已有目录、注入隔离提示，并在没有改动时删除临时目录。

`WorktreeSession` 保存用户手动进入 Worktree 时的会话信息。`WorktreeSessionStore` 将它写到 `.mewcode/worktree_session.json`。

`PostCreationSetup` 负责新目录的运行环境。`WorktreeChanges` 判断目录是否有未提交改动或新 commit。`StaleCleanup` 清理异常退出后遗留的临时 Worktree。

## 3. 创建 Worktree：谁调用谁

手动路径由 `EnterWorktreeTool.execute(...)` 开始。它先检查当前是否已经在一个 Worktree 会话中，再生成或接收名称。

```text
EnterWorktreeTool.execute(...)
  -> SlugValidator.validate(slug)
  -> WorktreeManager.create(slug, null)
  -> WorktreeSessionStore.restoreSession(session)
  -> WorktreeSessionStore.save(projectRoot, session)
```

`WorktreeManager.create(...)` 也会再次调用 `SlugValidator.validate(...)`。工具层和管理层都校验，避免上层遗漏时把危险名称传给 Git 或文件路径。

通过校验后，它执行：

```text
git worktree add -B <branch> <worktreeDir>
```

`-B` 会重置同名孤儿分支，适合临时 Agent Worktree。默认目录是 `<project>/.mewcode/worktrees/<branch>`。

## 4. Slug 校验：为什么不能直接信任名称

Worktree 名称会同时进入目录名和 Git 分支名。`SlugValidator` 限制总长度为 64，只接受字母、数字、点、下划线、连字符和用 `/` 分隔的段。

`.` 与 `..` 虽然由字符规则允许，但作为完整段会被明确拒绝。这样 `../../outside` 之类的路径不会被拼进 Worktree 目录。

`SlugValidator.flatten(...)` 将嵌套 slug 的 `/` 改成 `+`。例如 `team/alice` 会变成 `team+alice`，用于目录和分支名。

## 5. 新目录创建后还要做什么

`WorktreeManager.create(...)` 与 `AgentWorktree.create(...)` 都会调用：

```text
PostCreationSetup.perform(repoRoot, worktreePath, symlinkDirs)
```

它按顺序做四件事：复制 `.mewcode/settings.local.json`；配置 Git hooks 路径；为配置指定的大依赖目录建立符号链接；读取 `.worktreeinclude`，复制其中列出的被忽略文件。

这些步骤都是 best-effort。某项失败只记录日志，不会让已经创建成功的 Worktree 直接失败。

符号链接适合 `node_modules`、`.venv` 等大目录，但它会共享依赖文件，不是完全隔离的依赖环境。

## 6. 子 Agent 如何进入隔离目录

模型调用 `Agent` 工具时传入：

```text
isolation: "worktree"
```

`AgentTool.runSync(...)` 会生成类似 `agent-a1234567` 的随机 slug，再调用 `AgentWorktree.create(...)`。

```text
AgentTool.runSync(...)
  -> AgentWorktree.create(...)
  -> subAgent.setWorkDir(worktreePath)
  -> prompt 前加入 Worktree notice
  -> subAgent.run(conv)
```

notice 会告诉子 Agent：继承对话里出现的绝对路径属于父目录；必须换成当前 Worktree 的路径，并在编辑前重新读取文件。这样它不会因为沿用父上下文中的路径而误改主目录。

`AgentWorktree.create(...)` 发现目录已存在时，不再启动 Git 子进程。它读取 Worktree 的 `.git` 指针和 HEAD 文件恢复 commit，并更新时间，防止被过期清理误删。

创建新 Agent Worktree 时会设置 `GIT_TERMINAL_PROMPT=0` 与空 `GIT_ASKPASS`。Git 需要认证时会失败，而不是卡住等待交互输入。

## 7. 子 Agent 完成后：保留还是删除

`AgentTool.runSync(...)` 收到 `LoopComplete` 后调用：

```text
WorktreeChanges.hasChanges(worktreePath, headCommit)
```

它检查两件事：`git status --porcelain` 是否有未提交改动；`git rev-list <head>..HEAD` 是否有新 commit。

任一检查失败时，`hasChanges(...)` 也返回 `true`。这是 fail-closed：无法确认安全时宁可保留 Worktree，不自动删除。

没有改动时调用 `AgentWorktree.remove(...)` 删除目录和临时分支。有改动时保留目录，并把路径和分支名追加到子 Agent 的结果中，交给主 Agent 决定是否 review、merge 或 cherry-pick。

## 8. 手动退出：ExitWorktree 的变更保护

`ExitWorktreeTool.execute(...)` 从 `WorktreeSessionStore` 取得当前会话。`action="keep"` 只退出当前会话，目录继续保留。

`action="remove"` 时，工具先调用 `WorktreeChanges.countChanges(...)`。若有未提交改动或新 commit，必须再传：

```text
discard_changes: true
```

否则工具拒绝删除。它不会猜测这些改动是否有价值。

退出时会清空内存中的 `WorktreeSessionStore.currentSession`，并删除 `.mewcode/worktree_session.json`。这样下次启动不会错误恢复到已经退出或删除的目录。

## 9. 过期 Worktree 怎么清理

`StaleCleanup.cleanup(...)` 只处理名称符合临时规则的目录，例如 `agent-a` 加 7 位十六进制。

它依次检查：是不是临时名称；是不是当前会话目录；是否超过截止时间；是否没有未提交改动；是否没有未推送 commit。

全部满足才调用 `AgentWorktree.remove(...)`。任何 Git 检查失败都会跳过删除，因此清理偏向保守。

`StaleCleanup.startCleanupLoop(...)` 可以由上层传入定时执行器，周期性运行这个清理。

## 10. WorktreeManager 与 AgentWorktree 不要混淆

`WorktreeManager.remove(...)` 和 `cleanupStale(...)` 是较低层的强制操作，会使用 `git worktree remove --force`。

用户手动退出时的保护在 `ExitWorktreeTool`；子 Agent 自动清理时的保护在 `WorktreeChanges`。调用低层管理器前必须先由上层完成变更判断。

## 11. 当前范围与限制

当前 `isolation="worktree"` 在 `runSync(...)` 和团队队员路径中接入。普通 `runAsync(...)` 与 Fork 路径不经过这段创建逻辑。

Worktree 只隔离文件工作目录，不会自动把子 Agent 的改动合并回主分支。合并策略因项目而异，主 Agent 需要先检查保留的 Worktree，再自行使用 Git 命令完成 merge 或 cherry-pick。
