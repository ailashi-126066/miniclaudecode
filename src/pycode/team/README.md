# Team

保留进程内 Team、邮箱通信、任务板、独立 Git worktree、审查、合并和清理。
子任务仍是项目自己的 AgentSession；框架只负责每个会话中的图执行。

- `api.py`：AgentTeam/TeamMember 的公开入口；`engine.py` 实现协调与生命周期。
- `mailbox.py`：消息投递、接收和结果持久化后的 ACK。
- `tasks.py`：负责人、任务状态与依赖。
- `worktrees.py`：创建、检查差异、审查/合并与安全清理。
- `tools.py`：主 Agent 和成员可调用的工具。
- `types.py`：状态与纯策略；`contracts.py`：子会话与工作区依赖。
- `module.py`：供应用组装，普通调用方不构造内部实现。

普通 Team 委派经 AgentTeam 进入原有子会话工厂。移除的是只用于旧 agent Hook 的
TeamDelegate 包装，以及 Skill 自带的 fork 入口；邮箱、TeamSpawn/查询/消息等操作不变。

取消信号直接作用于所拥有的任务，不依赖成员及时读取邮箱。合并需要对应审批和验证；
冲突、取消、未知执行影响时保留工作成果。任务完成不等于可以删除 worktree。
