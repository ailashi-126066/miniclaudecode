# 阶段三设计：进程内多 Agent

状态：设计审查中，未经通过不开始实现。基线 `a16c4fc`；此文替代 `.agents/tasks/s3-design.md` 第 1 版。阶段四负责迁移功能目录，此阶段沿用现有分层。

## 1. 范围与现有接口

lead 是用户的 `AgentSession`；每个子 Agent 也是独立 `AgentSession`，由同进程 `asyncio.Task` 驱动，拥有自己的 Deep Agents 图、JSONL、执行锁、工具门和上下文。没有 HTTP、端口、Agent Protocol、`AsyncSubAgentMiddleware`，不新增依赖。默认同时运行 4 个子 Agent，`AppConfig.team_max_agents` 和全局 CLI `--max-agents` 可设 1–4，拒绝 bool 和越界。

已核对的接口：

- `AgentSession.run(Message)`、`resume(answers)`、`cancel()`、`submit_input`、`configure_fork_context`、`inspect`、`aclose`；执行锁在 `execution()`，待审批 run 返回 `RunResult(outcome="needs_input", pending=...)`，取消后通过 `recover_conversation` 落盘。
- `DurableInputs.submit(message, kind, run_id)` 在写 JSONL 后才接受输入；同 ID 跨 run 会报 `input_id_conflict`，不能假设跨 run 幂等。`finish_boundary` 关闭 run 的收件入口。
- `HistoryMiddleware.abefore_model` 的 `deliver_steering` 和 `aafter_agent` 是模型前/结束前队列读取点；两处扩展团队收件箱。`awrap_model_call` 的 compact/fallback 重试不另取收件箱，保持一次模型边界只取一次。
- `WorktreeManager.create/inspect/mark_integration/cleanup/scan` 已有，`cleanup` 要求 included、clean、validated、worker_stopped 四个条件。新增专用团队合并接口，不能把代码测试未运行冒充 `validated=True`。
- `PlanToolGate` auto 分支和 `AppConfig.tool_gate` 可绕过其内层工具门；新的强制团队边界放在两者外层。
- 默认 sandbox 为 off；`UnavailableSandbox.launch` 原样执行。worker 必须使用禁止回落的隔离启动器，不能把 cwd 或审批等同于写隔离。
- JSONL 在 `user_data/projects/<workspace hash>/sessions`，不在 worktree 目录内；worker 清理后 JSONL 仍保存，但需保留原 workspace 和 session 路径用于查看。

## 2. 分工、文件和依赖

| 层 | 文件 | 职责 |
| --- | --- | --- |
| core | `core/team.py` | 纯数据、状态、输入长度策略、依赖/claim/合并顺序检查、固定子提示、JSON 数据校验 |
| ports | `ports/team.py` | `ChildSession`、`ChildSessions`、`Worktrees` Protocol；只用 core 类型 |
| services | `services/team.py` | `TeamManager` 任务板、`AgentTeam` 生命周期/收件箱/等待、`TeamMember` 身份绑定；只依赖 core/ports/services |
| services | `services/permission.py` | 强制 `WorkerBoundaryGate`、团队合并 gate 编排协议；保留现有权限限制 |
| workflows | `workflows/team_tools.py` | 普通领域工具的 schema、分发、错误转 ToolResult；lead/child 工具集合 |
| workflows | `workflows/permissions.py` | `TeamToolGate` 合并一次审批，使用现有 `permission_answer` interrupt；审批细节绑定 preview |
| workflows | `history.py`、`state.py`、`settlement.py` | `RunContext.team`、模型前和结束前 flush |
| adapters | `team/worktrees.py` | diff/提交/安全合并/冲突中止/成功清理 |
| adapters | `sandbox/seatbelt.py`、`tools/bash.py`、Python Skill 启动器 | worker 严格沙箱启动，无可用后端时拒绝命令 |
| app | `team_runtime.py` | `TeamDelegate`、`child_config`，不引用具体 adapter |
| app | `container.py`、`runtime_launcher.py` | 团队、child opener、提示后缀、工具、强制工具门、事件转接；装配唯一入口 |
| services | `agent_session.py`、`usage.py`、`sessions.py` | 公开团队方法、取消/关闭联动、统计、隐藏账本会话 |
| UI | `approval.py`、`tui.py`、`commands.py`、`console.py`、新 `agents.py` | 审批来源、答复路由、唤醒、状态视图 |
| app | `config.py`、`cli_parser.py`、`cli.py`、`cli_commands.py` | 并发配置、CLI 事件与等待收拢 |

不做第二套模型/工具循环、通用调度器或持久化框架。现有 `ToolExecutor`、`ToolSettlementMiddleware`、事件流、JSONL store 和 session recovery 原样复用。

## 3. 数据与持久化

独立账本使用同一项目 session repository 的 `team-<sha256(lead_session_id)[:32]>` JSONL，单独 writer lease。后台状态不会改 lead 的 leaf，因此 `/branch`、`/revert` 不会和后台写入竞争；团队状态不随对话分支回退。`list_sessions` 隐藏 `team-`，子会话 `agent-<32hex>` 保留可检查。

每次状态转移追加完整快照，以 ID 最后一条为准。涉及 agent+task 的原子变更统一放在单条 `team_transition`（内含 agent/task/message 数组），追加成功后才替换相应内存视图；重放同样按数组展开。独立任务更新仍是一条 team_task。底层 append 返回错误但可能已 fsync 时，刷新 snapshot 按 transition ID 查验是否提交，失败则团队 degraded，不继续基于陈旧内存执行：

- `team_agent`：`agent_id`（8hex，团队内查重）、`session_id`、`mode`（readonly/worker）、`kind`（agent/delegate）、`role`、`task`、`task_id`、`status`、`workspace`、`provider/model_id`、`worktree_id/branch/base`、`pending`、`final_text`、`error_code`、`runs`、`generation`、`created_at/updated_at`、`review`（review head/tree hash）、`merge_commit`。
- `team_message`：`id`（16hex）、`sender`（lead/agent_id/team）、`recipient`、`kind`（message/agent_finished/agent_needs_input/agent_interrupted）、`text`、`status`（pending/delivering/delivered/dropped）、`delivery`（wait/steer/wake + 接收 run/input ID）、`wake`、`created_at`。broadcast 在一条 `team_message_batch` 中写入所有收件者副本，避免半次广播。
- `team_task`：`task_id`（t1…）、`title/description`、`dependencies`、`status`（open/in_progress/done/failed/cancelled）、`assignee`、`result`、`done_by`、`last_assignee`、`interrupted`。
- lead JSONL 仅在执行锁内追加 `team_agent` 链接（child session ID/workspace、role/mode、派出 run ID），用于统计与发现；子会话首个业务条目 `team_parent` 链接 lead 和账本。

每个 child JSONL 用该 child workspace 对应的现有 `StorageLayout`，请求数据库固定为 lead 的 runtime.sqlite；`UsageService` 由 lead 链接聚合 child session 的请求，purpose=subagent、agent_id=agent:<id>。worktree 删除不删其 user_data 下的会话或结果。错误码和日志不包含完整任务/模型凭据。

上限：生命周期最多 64 子 Agent、200 任务；role 2000、task 8000、消息 4000、任务标题 200/描述与结果 4000 字符；每发送者每 run 30 条普通消息，每收件箱 20 条 pending 普通消息。系统通知独立，不受普通消息配额影响；同 agent/generation 的结束和审批通知只生成一次。所有字符串 strip 后校验非空。列表、依赖数量有 schema 上限，服务再次校验；坏持久化条目报 WARNING 并跳过，不执行其中路径/命令。

## 4. 工具契约

所有工具 `source="team"`，参数 schema `additionalProperties=false`。除 `merge_agent` 为 write，其他为 read（只修改团队自身状态）；worker spawn、任务修改等另做 actor/plan/父权限校验。查询 list/review 可 parallel，其余 sequential，确保同批 spawn 后 wait 看到已注册句柄。所有工具走普通 settlement，结果先落盘再发完成事件。

| 使用者 | 工具与参数 | 结果与约束 |
| --- | --- | --- |
| lead | `spawn_agent(mode, role, task, task_id?)` | 返回 ID/session/status/worktree；模式固定；满槽立即拒绝 |
| lead/child | `send_message(to, text)` | to 为 lead、agent_id、all；返回各消息 ID；禁止给自己发送；all 为发送时其他 active 成员和 lead |
| lead | `wait_agents(ids?, timeout=120)` | 任一消息/目标结束/审批阻塞时返回状态和消息；timeout 1–600 秒；空团队立即返回；无 all 参数 |
| lead | `cancel_agent(id)`、`list_agents()` | 幂等取消；列表含状态、角色、任务、工作区、待审批数、结果摘要 |
| lead | `create_task(title, description?, depends_on?)` | 唯一顺序 task ID、依赖 DAG 检查 |
| lead | `update_task(task_id, status?, result?, title?, description?, depends_on?)`、`list_tasks()` | lead 可管理全部卡片；修改依赖仍检查 DAG |
| child | `claim_task(task_id)`、`update_task(task_id, status, result?)` | claim 原子；仅所有者能改自己的 in_progress/done/failed；不能改依赖、负责人、标题 |
| lead | `review_agent(id)` | diff、stat、文件/未跟踪清单、base/head/tree fingerprint；保存 review token；可查看 running，但该 token 不可用于合并 |
| lead | `merge_agent(id, action="merge")` | action 为 merge/prepare_resolution；completed worker、已 review、review fingerprint 未变、依赖已合并、显式审批；返回 merge commit/清理结果 |

child 额外有 `list_tasks`，方便认领；没有 spawn/wait/cancel/create/review/merge。工具身份绑定 `TeamMember`，不接受可伪造的 actor 参数。lead 每次模型切换时 spawn 继承调用时 provider，不持久化凭据。

错误以明确 `ToolError.code` 返回：`unknown_agent/task/dependency`、`invalid_dependency/dependency_cycle`、`task_already_claimed/task_closed/dependencies_pending/task_not_owned/agent_has_task`、`agent_limit_reached/agent_total_limit`、`worker_requires_git/worker_read_only_session/plan_read_only`、`message_too_long/message_limit_reached/inbox_full/agent_closed`、`review_required/review_stale`、`agent_active/not_a_worker/agent_not_completed/already_merged/merge_dependency_pending`，以及 Git/审批/存储错误。StorageError 在工具边界转为 `team_storage_error`；后台持久化失败触发团队 degraded 状态并拒绝新 spawn/merge，而非假报成功。

## 5. 权限与角色

`child_config` 收紧 lead 配置：`allow_delegation=False`、memory_mode=off、凭据从原 workspace 解析、只保留 child 工具。保留 lead 所有网络/信任/自定义 gate 限制，role 仅追加提示。readonly lead 不能派 worker。父自定义 gate 作为额外限制保留，不能替换强制 readonly/worker 边界；所有强制 gate 在 app 中最终装配的最外层，Plan、Skill、Memory、embedding gate 均不能绕过。

固定提示在所有默认/项目说明之后，角色在其后单独标注为不可信职责文本：readonly 只读、不执行命令；worker 只写自己的 worktree、命令要审批、不提交/合并/推送；用 send_message 通信、任务板协作。权限以工具注册/服务校验/沙箱落实，不依靠提示。

worker 原生 write_file/edit_file 使用已有 backend 的 resolve 路径边界与 symlink 检查，只能写 worktree。worker Bash 和 Python Skill 的 settings 强制 mode=on（保留 network_enabled），用新 `RequiredSandbox` adapter 包装现有 backend：`status.available && status.enabled` 才能 launch，`UnavailableSandbox` 不能回落执行。命令执行前仍单独经过 InterruptToolGate（不继承 Auto Plan 免审批，也不 on-auto）。沙箱不可用时 file 工具仍可用，但命令返回 `worker_sandbox_unavailable` 并解释可用平台限制。

child 不加载 effectful Hook（command 类型）或 MCP/任意 external 工具：Hook 有独立 executor、MCP server 不受本地写隔离控制，不能仅凭模型工具 gate 宣称隔离。prompt Hook 保留；readonly Hook agent 动作不递归委派。保留 lead 的全部 Hook/MCP 行为。child 需要的 Python Skill 只在严格沙箱中运行；catalog.verify_resources 核验 activation 后，仅为该次 Python 调用生成可读取此 Skill 源目录的 profile（不授予其他 user_data，.env 禁读继续在后覆盖，写根不变）；Bash 不获得该只读授权。不支持严格沙箱则拒绝。`config.tool_gate` 不能放过这些禁区。worker 的 `.git` 元数据仍由 sandbox 禁写，解决冲突通过 lead 的审批 Git 合并工具调度，不能要求 child 在 Bash 中直接改共享 `.git`。

## 6. 生命周期与并发

`AgentTeam` 维护 records、handles、收件箱、`_lock`（Condition 共享此锁）、`_merge_lock`、`_tasks`、`_closing/_stopping`、epoch。`TeamManager.lock` 保护任务板。锁顺序 team → board；持有 team 不等 git、child.run/open 或 merge lock。状态 reservation 后锁外慢操作，回锁提交时核对 epoch/generation/取消标志。

active 为 starting/running/needs_approval，占槽；terminal 为 completed/failed/cancelled/interrupted/merged。merging 不占槽但不可唤醒。所有状态变更和 notify 在同一 Condition 锁，wait 用谓词循环以避免漏通知。

spawn：锁内检查配置、槽位、Plan、claim，追加 starting 并预留；锁外创建 worktree、打开 session、team_parent、lead 链接；回锁核对 generation/取消后进入 running，开启持有的 drive task。handle 从 starting 开始持有 startup task，取消要取消并等待它。任何失败或取消都关闭打开的会话、释放 claim/槽、保留并标记已创建 worktree，继续抛原取消或 agent_start_failed。已创建资源先绑定句柄再 await；`owned_io` 的线程任务取消后须等待实际完成，资源迟到不会漏清理。

`_drive` 执行 session.run/resume；needs_input → needs_approval，持槽且保留 session；completed → completed；error → failed；取消 → cancelled。非成功不能直接 merge。终态释放槽，最多生成一次结束通知。任务板保留 child 显式设定的结果/终态；尚在 in_progress 的已认领任务，completed 自动设 done（摘要填结果），failed 设 failed，cancelled/interrupted 重新 open，保留 last_assignee 和 interrupted 标识。多个 claim 用 board 锁确保唯一。

直接给 completed/failed/interrupted 子 Agent 发消息可再跑一轮（merged 不可）；cancelled 不自动唤醒，给它发送返回 agent_closed，需重新 spawn。没有槽位时新消息保持 pending，按 FIFO 在槽位释放后启动。wake 只使用当前进程本次新收到的消息，启动时重放的 pending 不能触发 child 自动续跑。readonly 和 worker 的路径不变；child 失败后继续保持同样权限。resolution_pending 的 worker 只允许同一个 child 再次启动，期间禁止 merge；其完成后状态回 completed，resolution 标识继续保留直至 commit_all 成功。

fork Skill/Hook 的 `TeamDelegate` 使用 `run_blocking(mode=readonly, kind=delegate)`，计入并发上限。保留原 Skill full/recent/none fork 语义；无槽位直接返回 agent_limit_reached；调用方取消时取消 child 并等待。delegate 不发自动唤醒通知。删除 SynchronousDelegate、ConfiguredWorkers、SessionWorker、runtime_subagents；deepagents 原生 task 仅为 fork Skill 保留。

## 7. 收件箱与闲时唤醒

发送先写账本再更改内存/发事件；消息长度/配额与 broadcast 所有接收者容量在同一锁内检查。运行中的接收者下一次模型前消费；lead 正在 wait 时作为工具结果返回；完成/普通消息投给闲 lead 发 `team/wake`。审批通知 wake=false，但仍立即显示 UI 并唤醒 wait。

`flush_inbox(context)` 锁内 reserve，记录 delivering（接收 run ID + 确定的 `team-msg-<message id>` input ID）；锁外调用 durable.submit；成功后记 delivered。若写 delivered 失败，此 run 下重试以相同 ID 幂等。不同 run 重试前检查接收者 JSONL 是否已包含相同 input ID（无论 message/input_queued）：存在就记 delivered，不重新 submit；不存在才绑定新 run。`input_admission_closed` 留 pending。branch conflict 作为需要人工处理的可见错误，不更换消息 ID掩盖重复。aafter_agent 先 flush 再 finish_boundary，结束后到达的消息由下次 wake 接住。

`wait_agents` 在锁内选择消息，但待工具 settlement 成功后才确认 delivered；`RunContext` 注册以 operation_id 为键的 ack/rollback 回调；`ToolSettlementMiddleware.settle_ready` 在 save_message 写入该工具 ToolResult JSONL 后（现有 executor.completed 之前）执行 ack 提交 wait delivery；collect_tools 的取消/失败路径执行 rollback 释放 reservation。因此取消不会提前丢消息。进程崩溃时由工具结果的消息 ID 链接判断是否确认。工具结果返回 truncated 摘要，完整通信仍在账本。

`wake_team()` 同一会话执行锁内检查无 pending run、team 非 stopping，reserve 消息，生成由消息 ID 集合确定的 wake input ID。现有 run 方法提取 `run_locked` 私有实现供其复用，避免释放锁后再 run 的竞态。成功 append_message 后确认 delivered；append 后失败依据 JSONL 判断是否接受，不恢复已接受消息。wake JSONL 元数据保存消息 ID；重启时据此修复 delivering。wake=false 的中断/审批通知不会在打开会话时自动启动模型。

TUI 收到 team/wake，且不 busy、不在等 lead 审批时用现有 start_operation 调 wake_team；每次操作结束再次检查 pending，覆盖结束边界竞态。CLI run 一轮完成后 `wait_team`：有可唤醒消息则 wake；还有 running 则等 Condition；没有活动返回 idle；只剩 needs_approval 则打印子审批（ID/role/workspace/pending）并退出 2。CLI 当前为非交互，保持这一已存在的审批模式，不假装自动批准；退出时取消并收拢子 Agent，不提供跨进程恢复审批。TUI 则可在同进程答复子审批。

## 8. 子审批转发

child 返回 needs_input 后将原 `RunResult.pending` 完整存入记录，发送 team/approval 事件（lead session ID、agent_id、role、mode、workspace、pending），原始 interrupt ID/operation_id 保留。child message delta 不混入主对话；activity/error 可作为带来源的团队事件显示。

`AgentSession.answer_agent(id, answer)` 与 lead execution 锁独立，只获取团队锁；定位当前 pending，`validate_operation_answer` 校验，多个 interrupt 用已有 `{interrupt_id: answer}` 结构传给 child.resume。有效答复后 reservation running，后台驱动并立即返回 UI；重复/过时答复拒绝。TUI ApprovalPanel.Responded 增加 agent_id，选择 child 时调 answer_agent，lead 审批仍调 resume。MCP elicitation 不会出现在 child（不加载 MCP）。所有审批标题和 CLI 输出标来源，角色文本 markup=False 并截断。

## 9. Review、合并与冲突

worker 从 lead 当前 HEAD 创建独立分支，不复制未提交文件；工具结果明确提示这一点。非 Git 仓库拒绝 worker，readonly 可用。

`changes` 用 NUL 分隔解析路径，`git diff <base>` 含已暂存/未暂存，额外列 untracked 内容（有大小与文本截断，不将未跟踪文件悄悄排除审查），binary 只给摘要。review fingerprint 包含 worker HEAD、index/worktree 变动及 untracked 内容 hash；review 正在运行仅供观察，不能授权后续 merge。

merge 前需要 worker completed、静止、已 review 且 fingerprint 相同。依赖任务全部 done，且 done_by 若是 worker 必须已 merged；缺失依赖记录拒绝。merge 使用两层验证：工具 gate 构造 preview（worker fingerprint、目标 branch/HEAD、文件列表、提交说明、target dirty 状态）→ 一次显式 `permission_answer` → 执行前在 Git lease 下重新检查同一 preview。目标/worker 变化返回 `merge_preview_stale`，用户重新 review/调用工具会触发新的审批，不能沿用旧答案。

`TeamToolGate` 放所有可跳过审批的 Plan/custom/Skill gate 之外；merge 先调用 Plan 只做 deny 检查，auto 结果不算批准，再独立走 InterruptToolGate。每次 merge 都审批，不提供 automerge、持久化 grant 或 allow-agent-merge。preview 必须绑定 operation spec hash，重放在执行前验证。空合并也遵循一次审批（可能写 Git 元数据/执行清理）。具体装配为 merge 先做 team 硬限制，再执行 `SkillToolGate(history, inherited_custom_gate or NoOpGate)` 保留 Skill scope 和父 custom 拒绝，再调用 Plan.guard（只取拒绝），最后一次默认强制 InterruptToolGate；不能为了绕开 Plan auto 而丢弃父拒绝。custom 自己需要额外审批时保留其语义。worker 同样先执行模式硬限制和父 custom/Skill 拒绝，再仅对其 worktree 原生 file 写放行、对命令走严格隔离与强制 interrupt；IsolatedWorkerToolGate 的直接 return 不能绕过父 custom gate。

WorktreeManager 新增 changes、commit_all、merge_into_workspace 以及 complete_team_merge 清理入口：

1. 预检目标必须在预期 branch/HEAD、无 MERGE_HEAD、无 staged/unstaged/untracked 改动（保守拒绝 dirty，避免 abort 恢复风险）。worker 路径/分支必须匹配管理记录，HEAD 与 fingerprint 相符。仓库 lease 从预检一直持有到 merge 完成；其他 pycode worktree 操作串行，外部 Git 并发无法完全锁住，命令前后验证 HEAD。
2. worker `git add -A` 后 `git diff --cached --quiet`，非空才 commit。commit_all 不在用户 lead 分支提交。缺少身份时用仅该命令的 pycode agent identity，不改用户 config。Git hooks 禁用策略沿用现有 WorktreeManager，审批细节明确显示 hooks_disabled=true。
3. lead 执行 `git merge --no-ff --no-edit`。成功记目标 commit、worker commit、review hash；没有额外改动且已是祖先视为 empty。
4. 冲突收集 `--diff-filter=U -z`；尝试 merge --abort 并验证原 HEAD/clean。abort 失败则返回 `merge_recovery_required`，保留实际冲突状态，不宣称仓库恢复。worktree 和分支一直保留。
5. lead 首先分析 merge_conflict 返回的冲突摘要。选择 `merge_agent(id, action="prepare_resolution")` 会形成新的审批，明确只在 worker 工作树执行 `git merge --no-commit <target_commit>`，绑定 source/target 指纹；审批后将冲突局限于 worker，保留 worker MERGE_HEAD，lead 工作区已经 abort 并保持干净。该操作成功返回冲突路径/三方有界内容及 worker 路径，记录 resolution_pending，不算 merged，也不清理。lead 用 send_message 给原 worker 具体解决意见；worker 用 file 工具编辑冲突文件并完成报告（不调用 git），随后 lead review_agent 检查新指纹，再 merge_agent(action=merge) 经新的审批检查所有冲突标记与 unresolved index。commit_all 先 add -A，再确认没有 unmerged entries，才能完成 worker 的 merge commit 并集成到 lead。审批细节明确这一动作会完成待处理 merge。自动判断冲突已解决只能基于 unmerged/冲突标记检查，不能代替 lead review；未解决时返回 resolution_required。无法解决则 AskUserQuestion 给用户路径与冲突信息，记录 resolution_pending，禁止自动重试循环。
6. 成功后先持久化 merged，再关闭子会话，再清理 worktree/branch。新增 complete_team_merge 验证 branch 已包含、clean、child driver 已停止、合并提交与审批指纹匹配后调用受控 cleanup；单独 `integration_verified` 标识代码集成事实，不把测试未跑写成 validated。原 CLI confirm-integration 的 validated 语义不变。清理失败不回滚合并，只返回 retained/reason。

合并中的取消：git 线程由 owned_io 持有，不能直接停止线程后报告 cancelled；等待 Git 操作完成/超时，检查 MERGE_HEAD 并按上述恢复策略结算。进程崩溃留下 merge 意图与实际 commit；重启只核对 Git 状态、标 interrupted/recovery_required，不自动 merge/abort。review/merge 的审查、审批、结果与 cleanup 都写账本。

## 10. 取消、关闭与重启

lead 当前 run 被取消（显式 session.cancel 或 asyncio cancellation）都在 finally 调 team.cancel_all。不仅 TUI Stop 路径，CLI Ctrl+C/调用方直接取消 run 也覆盖。lead 空闲时 Stop 仍取消子 Agent。先设置 stopping/递增 epoch，禁止 spawn、自动 child wake 和 idle lead wake；标 cancel_requested 并 drop pending 给被取消 child 的消息；取消 startup/drive tasks 和 pending child run，await gather 之后才释放槽。不可让释放槽的完成回调再次自动启动排队 child。单个 cancel 也以 generation 阻止迟到 startup/drive 复活。

用户之后手动发起新的 lead run 时解除 stopping，不恢复被取消的 child。wait 取消只释放自身消息 reservation；真正 lead run 取消才 cancel_all。取消过程中不发送可唤醒 lead 的结束通知，记录 cancelled 历史；关闭则永久 closing。

aclose：先设置 closing、停止新团队活动并取消收拢普通子任务；再 lead launcher.aclose 执行 shutdown Hook。若该 Hook 使用同步 delegate，仅允许团队内部 run_blocking(kind=delegate) 通过临时 closing_delegate 入口，仍计入限额、受关闭管理，不允许 spawn 或唤醒普通 child；Hook 失败/取消也回收 delegate。其后团队关闭全部 child session/ledger，最后关 lead view/store。每层用 try/finally 保证所有资源收拢。不得在团队关完后再运行需要 delegate 的 Hook。

initialize 在 launcher.startup 之前：重放账本（delivering 按接收 JSONL/settled tool result 修复）；starting/running/needs_approval/merging 一律 interrupted，未完成任务重新 open，保留 last_assignee/interrupted；丢弃给已中断 child 的普通 pending，记录 agent_interrupted(wake=false)。不创建任何 child session/task，不自动调用模型。worker worktree 保留；只有用户/lead 在新的运行中显式发消息才可能唤醒 interrupted child。合并崩溃状态需展示实际 Git recovery 信息，不能自动清理。

## 11. 验证与交付

按用户决定阶段中只做 ruff check、ruff format --check、architecture、compileall、导入冒烟；下面测试编写但全部等五阶段完成后统一运行。实现审查通过后单独阶段提交，不推送/PR，不自行合并 main。

| 测试 | 必须覆盖 |
| --- | --- |
| core/team | DAG 自依赖/重复/未知/环、claim/merge 顺序、状态转换、数据校验、角色不改变规则 |
| services/team | max=1/4、第 5 个拒绝、claim 并发唯一、发送限额与原子广播、模型前注入、wait 超时/取消不丢消息、空闲唤醒、startup/drive/finish/cancel 竞态、审批重复/多 interrupt、存储失败 degraded、重启不自动跑 |
| workflows/team_tools | 全 schema、lead/child 工具集、错误结果、批次 spawn→wait 顺序、settlement 确认 wait delivery、direct cancellation 联动 |
| workflows/team | 本地假模型 readonly 禁写/worker 隔离、独立 JSONL/parent/统计、消息请求体、空闲唤醒、多 child 审批转发、merge 逐次审批和 Auto Plan/custom gate 无法绕过、旧 readonly 删除 |
| adapters/worktrees | 临时 Git 成功 merge/空合并/dirty/detached/MERGE_HEAD/冲突 abort 及 abort 失败/过时 preview/未跟踪文件 review/合并后清理及保留历史 |
| sandbox/permissions | worker 强制 mode on、不可用后端拒绝、Bash/Python 逃逸写被拦、禁止 MCP/effectful Hook、自定义 gate 无法放宽 |
| UI/CLI | child 来源标题、lead 忙时答复 child、多个审批队列、wake 边界、CLI blocked 输出与退出 2、关闭收拢、max-agents 校验 |
| fork Skills/Hooks | 原 fork full/recent/none、结果、用量保留，delegate 计入限额且取消不泄漏 |

文档同步 README（使用/权限/CLI 非交互限制/worker 沙箱平台限制）、architecture（团队流程/账本/端口实现）、storage-format（team 条目与恢复）。阶段四将其重组到 team 功能目录，并保留此设计约束。
