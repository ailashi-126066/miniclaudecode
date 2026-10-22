# 阶段四实际落地与验证清单

基线：`main@ac40d4f`（阶段三源码 `f21db32`）。阶段二、三的已确认行为和权限规则继续生效。
本阶段没有运行 pytest、Seatbelt 行为验收、发行安装或模型评测；留给阶段五后的统一测试。

原始所有权与 SCC 证据保留在 `module-map.json`、`module-splits.json`、`actual-scc.json`。
实际 Git 移动清单为 `module-targets.json` 与 `test-moves.json`；前者的拆分来源不能再次按
整文件机械执行。最终实际源码/依赖快照为 `final-module-map.json`、`final-import-graph.json`。
严格检查器读取真实源码与公开符号的定义来源，不能仅靠这些清单宣称通过。

| 设计项 | 实际落地位置与注入点 |
| --- | --- |
| C01 模型身份 | `kernel/contracts/model_identity.py` 定义 ModelSelection、ModelCapabilities、ProviderKind；ProviderConfig/模型发现策略留 `models/domain/` |
| C02 usage/operation | `models/domain/usage.py` 聚合 RequestRecord；`tools/domain/operations.py` 计算工具声明 hash；kernel 不依赖 models/tools |
| C03 会话/执行 | RuntimeLauncher 留 `session/ports/runtime.py`，RunResult 在 kernel；`app/runtime_launcher.py` 实现。标题通过 `agent.TitleAssignment` → `composition.runtime.BoundTitleAssignment`，保留初始用户消息落盘后的时点 |
| C04 投影能力 | `models/ports/context.py:Projector`，`composition.models.build_model_control` 注入 ContextProjector，无 models → context 源码依赖 |
| C05 消息与计量 | converters 在 `models/adapters/messages.py`；agent 的 RuntimeBindings、context SummaryWorkflow 都由 composition 注入 converter/request_step。SummaryMessages 是 context 自有框架边界值，不 import models adapter |
| C06 校准与 fallback | `models/ports/context.py` 的 CalibrationContext、RegistryLookup、ResourceLookup、SummaryBudget、CalibrationReport；`models/adapters/calibration.py` 仅在框架边界 cast，显式收窄可空能力；composition partial 注入 converter/estimate，fallback 使用相同 calibrator |
| C07 工具结算 | `tools/adapters/{settlement,framework_tool,state}.py`；ToolSettlementState 只拥有工具 failure 字段；composition 的 factory(context) 传入绑定的 AgentMessagePublisher、生命周期闭包与 converter/normalizer。publisher 保留 JSONL → output bind → snapshot fact → event 次序 |
| C08 工具执行守卫 | `tools/domain/turn_guard.py:tool_calls_may_execute`；NextTurn/next_turn 仍在 agent domain |
| C09 Hooks | `tools.ToolHooks` → `hooks/services/tool_bridge.py:ToolHookBridge`；Hooks 由 HookExecutorFactory 获取 executor；`composition.runtime.build_hook_engine` 构造 registry、tools 的 ConfiguredActionGate 与 ToolExecutor。前置/授权/命令/持久化后事件分别保持原时点 |
| C11 通用脱敏 | SECRET_PATTERNS 在 `kernel/redaction.py`；写入拒绝策略留 memory/domain；LangMem tools 采用共享 ExecutableTool 声明 |
| C12 沙箱输出 | `sandbox/ports/output.py` 只声明 begin/append/finish/abort；FileToolOutputStore/StreamingOutputWriter 通过 composition 注入，sandbox 无 tools import。审批 gate 留 tools，纯命令与环境策略留 sandbox |
| C13 回退与快照 | RevertService 在 `session/services/revert.py`，revert_state/RevertPlan 在 session domain；snapshot 只拥有文件版本、RestorePlan、SnapshotStore/GitSnapshotStore；无 snapshot → session |
| C14 Skills 会话写保护 | `skills.CatalogWriteGuard` → `session/domain/guards.py:SessionCatalogWriteGuard`，composition.build_skills 注入；Skills 无 session/snapshot import，原 pending-run/revert 拒绝条件保留 |
| C15 历史事实 | SessionEntry/Header/Draft/Snapshot、SessionTree 和最小 HistoryReader/HistoryJournal 在 kernel；branch/aclose 仍归 SessionStore；UsageService 通过 models.RunOwnership → SessionRunOwnership 使用原归属规则 |
| C16 记忆/压缩 | `context/domain/retention.py:retained_context_entries` 共享 retained-tail 纯策略；memory 使用其公开 API。MemoryUpdater 由 `composition.memory.ConfiguredMemoryUpdater` 实现；thinking off、0 重试、后台失败日志和撤销保持阶段二约定 |
| C17 工具门 | `tools/services/{permissions,plan_gate,skill_gate,hook_gate}.py` 与 `tools/adapters/permissions.py`；单复数统一为 permissions；plans 自有 QuestionPrompt，Skills/Plan 工具声明使用 kernel，避免反向依赖 |
| C18 团队 | 实际采用 `team.ChildSession/ChildSessions/Worktrees/WorktreeSnapshot/WorktreeSnapshotFactory`；ConfiguredChildSessions 以 session_opener/repository_factory 回调解除 composition.team ↔ composition.session。唤醒/审批保留阶段三事件和 durable input 路由，未创建无实际用途的 LeadWakeup/ApprovalPrompt |

跨 adapter 的其余落地：

- SkillBackend/PlanBackend 改为显式 BackendOperations 委托，不经公共 API 转发 PycodeBackend。
  Skill wrapper 自身硬拒写入并保留 receipts；Plan 的 path_guard 与 search_scope 让每次解析
  以及 rg 目标都只涉及 active Markdown，未 active 时不启动扫描。
- MCP/Skill 声明校验通过各自 SchemaChecker 注入 JsonSchemaValidator。
- Hook/Python Skill 注入 ProcessRunner。SkillReadGrant 仅在 catalog.verify_resources 成功后
  授权精确 Skill 根只读，保留 worker 固定写根、dotenv deny 与强制沙箱。
- 输出 ancestry 读取通过同步 SessionHeaderReader，在线程 owned_io 内使用 session 的有界
  FileSessionHeaderReader；tools 不再解析另一功能的私有 codec。
- WorktreeManager 注入 snapshot_factory；GitSnapshotStore 的 read_blob 只读独立 snapshot
  对象仓库，不再由 team adapter 直接创建或引用另一个 adapter。

组合根拆成 runtime/session/models/context/memory/team/ui/capabilities 八个无环模块，
`app/composition/__init__.py` 只导出构造 API。资源路径按实际新深度修正。
147 个维护中测试文件使用 git mv 移到相应功能目录；support、fixtures 和 architecture
保持共享。跨测试 fixture imports、动态 patch 的实际消费模块、资源 parents 锚点和新端口
构造参数同步更新。所有源码移动使用 git mv，删除旧初始化/已拆分文件使用 git rm；不保留
旧层包、模块 alias 或兼容转发壳。

`docs/architecture.md` 与 `port-implementations.json` 对照所有 ports/contracts Protocol，
检查器验证每个端口有实际存在的生产 class/function/factory，而非把 Protocol 本身当实现。
架构负例覆盖纯层越界、相对/别名、未导出/未定义 API、adapter 工厂洗白、TYPE_CHECKING
循环、隐藏 pycode 动态依赖与共享 kernel I/O 绕过；这些测试本阶段只编写，不运行。
