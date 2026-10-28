# Team

进程内 Team 使用邮箱沟通、任务看板和独立 worktree。TeamState 是成员、任务、邮箱的统一内存状态，业务变更仍使用现有 Team SQLite Store；完整对话保存在任务会话 SQLite Store 中。

- state.py：公共看板 schema 与按 ID 合并的 reducers；直接更新，不再套单节点图。
- framework.py：LangGraph 原生 TimeoutPolicy、heartbeat、条件路由和超时 error_handler；审批无期限，工具硬期限不随输出续期。
- engine.py：派发、活动筛选、停止确认、A→B 交接与合并。
- execution.py：任务持有的子会话和运行通知，保持进程内 checkpoint 生命周期。
- tasks.py：依赖、写入权限、状态迁移、提交与 lead 验收。
- mailbox.py：定向消息、任务关联、预约、ACK 与交接转投。
- worktrees.py：代码版本、范围内成果、审查与合并。
- tools.py：工具 schema。子成员提交 submitted，lead 使用 update_task 验收；handoff_task 用于未完成任务的接管。
- module.py：装配及 ChildEventRelay。

默认无活动 180 秒、停止确认 15 秒、最多自动交接一次。任务总时长不设统一上限；有效模型/工具活动续期，审批暂停，安静命令由其自身超时负责。停止未确认不启动替代者。

交接保持任务会话、执行线程、worktree、已有输出和读取版本。正常任务结果先进入待验收；必须验收后才能请求 merge_agent。整个进程重启不恢复持久化 checkpoint，也不自动执行旧调用。

详见 docs/design/team-state-handoff.md。
