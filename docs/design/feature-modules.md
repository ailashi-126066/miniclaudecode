# 阶段四设计：按功能组织源码

状态：已按独立审查通过的设计完成实施，等待最终静态证据与代码终审。基线为阶段三后的 `main@ac40d4f`（阶段三源码 `f21db32`），原 195 个源码模块；最终模块清单与 C01–C18 对照见 `implementation-audit.md`。
实施规范为仓库根目录《五阶段改造计划.md》。本设计不改变任何用户行为。

## 目标与迁移边界

顶层功能包为 `kernel`、`agent`、`session`、`context`、`models`、`tools`、
`sandbox`、`snapshot`、`memory`、`skills`、`mcp`、`hooks`、`plans`、`team`、
`ui` 和 `app`。删除旧的 `core`、`ports`、`services`、`workflows`、
`adapters` 顶层包；不保留旧导入路径的兼容外壳。

`resources` 仍是发行资源，不作为代码层。`evals`、`scripts`、`archive` 仍在产品包外。
生产、测试、评测和开发脚本的导入及字符串模块名一并迁移；历史 archive 不改。
`__main__`、console script、Python Skill worker 的模块启动路径同时核对。

`module-map.json` 是针对当前阶段二源码更新的整文件默认所有权清单。下文拆分表优先于整文件映射；它不是机械替换 import 的指令。
阶段二删除的记忆模块不搬回，阶段三新加文件全部归入 team 及对应入口。
具体 Git 重命名清单在实施时重新生成，使用 `git mv`，禁止复制后删除冒充迁移。

## 每个功能包的内部边界

- `domain.py` 或 `domain/`：该功能的纯数据和策略。
- `ports.py` 或 `ports/`：该功能拥有的 Protocol，依赖本功能 domain 和公共契约。
  多个独立 I/O 契约可分别保留小文件，由功能根公开，检查器将二者识别为同一层。
- `service.py` 或 `services/`：无框架依赖的应用服务。
- `adapters/`：磁盘、Git、SDK、框架 middleware 等具体实现。
- `__init__.py`：显式、静态且有 `__all__` 的公开 API。

计划中的 `domain → ports → service → adapters` 表示从内到外的层次顺序。
实际 import 朝内：domain 不依赖 ports/service/adapters，ports 不依赖 service/adapters，
service 不依赖 adapters；具体实现通过组合根注入。框架执行边界放 adapters，
不能通过把 middleware 改名为 service 绕过框架隔离。

包内部可使用本功能内部路径。跨功能只从另一个包的 `__init__.py` 导入公开符号；最终权威入口为 `docs/architecture.md`、`feature-dependencies.json` 与实际源码检查。
外部包不可直接引用任何功能的 adapters；组合根是具体实现的唯一跨功能引用者。
UI 和 CLI 接受服务接口，具体对象由组合根建立。

## 公共契约和消环

不能只改路径：初始所有权图已显示 session/context/models/tools/agent 的环。
实施必须先拆除这些环，再导出公开 API，不能用函数内 import、`__getattr__`、
模块别名注册、关闭检查或空壳转发隐藏问题。

kernel 仅容纳被多个功能实际共享的基础契约和无功能归属的基础设施：
消息、事件、错误、不可变值、哈希、原子路径 I/O、文件 lease、时钟和事件端口。
其中纯数据与 I/O 基础设施分开，domain 检查仍适用。
消息携带的模型身份、工具调用与结果身份、会话条目引用等共享值对象可提取到
kernel/contracts；Provider 配置、模型发现策略、会话执行策略仍属于各自功能。
不得把全部旧 core/ports 移入 kernel 规避功能划分。

消环按以下原则逐项实施并记录：

1. canonical SessionEntry/EntryDraft/Snapshot 作为多个功能共同操作的持久事实契约，
   与 AgentSession 的执行命令拆开；SessionTree 的通用条目索引不依赖会话执行服务。
2. ModelControl 需要的 projector 仅是 `project(snapshot)` 接口，改用依赖注入；
   models 不直接导入 context 的具体 ContextProjector。
3. 框架消息转换由 models/adapters 实现，工具框架适配由 tools/adapters 实现；
   composition 注入转换器/工厂，不从功能公开 API 重导出。models/tools 不反向导入 agent 主循环。
4. RunContext 的框架独立数据和执行能力接口与 DeepAgentState 分开。
   工具中间件只声明自己实际写入的状态字段；不导入 AgentRuntime 的状态实现。
5. `make_framework_tool`、工具结算和 ToolGate 放 tools；模型请求准备和校准放 models；
   AgentRuntime 组合这些边界，其他功能通过注入回调调用它们。
6. 只用于 annotations 的具体服务类型改成所属能力的 Protocol，避免运行时导入具体服务。
   TYPE_CHECKING 不能作为功能图中循环依赖的豁免。
7. kernel 不 import 功能包；如果公共消息依赖模型身份，把最小身份值对象提取出来，
   保留模型配置和业务策略的原所有权。

每次消环记录原依赖、接口替换和不变的调用约定，供审查确认并非削弱约束。

## 目标功能 DAG（实施门槛）

`feature-dependencies.json` 是目标**允许**依赖图；箭头表示 importer → imported。
实际实现可以少用边，不能新增反向边。所有包（包括 adapters 和 TYPE_CHECKING imports）
都计入功能图；唯一高位装配包是 app，kernel 不依赖任何功能。

| 功能 | 允许导入的功能公开 API（省略所有功能都可导入的 kernel） |
| --- | --- |
| kernel | 无 |
| sandbox、snapshot、models、mcp | 无 |
| skills、plans、hooks | sandbox |
| context | models、mcp、skills |
| memory | context |
| tools | sandbox、snapshot、skills、plans |
| agent | models、context、tools、skills、plans、hooks、mcp |
| team | snapshot、tools、plans |
| session | context、models、skills、snapshot、tools、memory、team |
| ui | tools、plans、team |
| app | 全部功能 |

一个可核查的拓扑层次（底部被依赖，数字递增为调用方）为：
0 kernel；1 sandbox/snapshot/models/mcp；2 skills/plans/hooks；
3 context/tools；4 memory/agent/team；5 session/ui；6 app。
同层的功能互不 import。`app/composition` 可以导入各功能 adapters；app 的 CLI、
config、launcher 等其余模块仍只用公开 API 和组合根提供的构造结果。

context → models 只允许模型选择/预算的公开纯数据、无框架服务 Protocol；
不能导入 ModelRequestRunner、MeteredChatModel 或 SDK 消息转换器。
tools → skills/plans 的用途是集中的 SkillToolGate/PlanToolGate：它们消费功能公开的
权限策略与状态接口；skills/plans 的业务服务和工具 adapter 不再反向 import tools。
team 使用 session 自己实现的 ChildSessionFactory，但 Protocol 由 team 消费方拥有，
所以形成运行时调用不产生 team → session 的源码依赖。session 允许使用 team 的公开服务。

## 最小共享契约（精确提取，不整包搬旧 core/ports）

以下是 kernel 唯一新增的跨功能契约组；所有符号仍由 `pycode.kernel` 显式导出。
每组单独文件，schema、JSON 字段名、默认值和相等语义不变。`module-splits.json` 保存实施前逐符号拆分清单，最终实际路径以 `implementation-audit.md` 和 `final-module-map.json` 为准；
优先于整文件 module-map；公开名字清单在阶段三合入后校准一次。

| 来源 | kernel 目标/保留符号 | 明确留在功能内的部分 |
| --- | --- | --- |
| core.messages；core.models 的两个身份类 | `contracts/messages.py`：Message、ToolCall、ToolResult、UsageRecord、ThinkingBlock、Attachment、Role、Purpose、StopReason；`contracts/model_identity.py`：ModelSelection、ModelCapabilities | ProviderConfig/ModelReference/发现/selector策略留 models；SDK类不进入 kernel |
| core.session、core.session_tree | `contracts/history.py`：SessionHeader、SessionEntry、EntryDraft、SessionSnapshot、ProjectedEntry、ProjectedContext；`history_tree.py`：SessionTree；`history_boundaries.py`：valid_kept_boundary（跨会话/压缩共同的 call-result 完整性不变量） | 分支命名、恢复、执行状态、session repository、JSONL codec 留 session；上下文算法留 context |
| ports.history | `contracts/history.py`：MessageJournal.append_message；HistoryReader.snapshot；HistoryJournal 组合二者并提供 append_entry | SessionStore（branch/aclose/claim_writer 等会话控制能力）留 session；其他功能只接收所需的只读/追加接口 |
| core.tools 的声明与调用身份；ports.tools.Tool | `contracts/tools.py`：ToolSpec、ToolContext、ExecutableTool(spec, execute(context, arguments)->ToolResult) | ToolRegistry/ToolExecutor/ToolGate/ArgumentValidator/batch_execution_mode/权限策略留 tools；只共享每种工具都要实现的执行签名 |
| core.runtime.RunResult | `contracts/completion.py`：RunResult | AgentRuntime、执行循环及 NextTurn 留 agent；RuntimeLauncher 接口由 session 拥有 |
| core.memory.SECRET_PATTERNS 中通用脱敏规则 | `redaction.py`：SECRET_PATTERNS、redact_secrets(text) | WRITE_SECRET_PATTERNS、check_memory_write、scope/namespace 规则留 memory |

原 `core.usage` 的 RequestRecord 聚合逻辑整体归 **models**，不再把它放入 kernel；
原 `core.operations.operation_spec_hash` 归 **tools**。因此不需要把 RequestRecord、
重试策略或全部模型配置搬入 kernel。`kernel.contracts` 内部有明确无环顺序：
model_identity → messages → tools/history → completion；基础 values/errors 不回指契约。
上表中的箭头表示定义依赖顺序，与 import 方向相反。

纯路径判断/哈希留 kernel；`kernel/io/` 才放 atomic_write、owned_io、FileLease 与 SystemTiming。
纯 domain 禁止 import `kernel.io`（包括从 kernel 重导出的 I/O 符号），不能借 kernel 掩盖 I/O。

## 循环拆分表（实施必须逐项核对）

以下条目来自阶段二实际 import 扫描和阶段三设计。每行同时给出旧依赖、替换能力和
最终调用约定；仅 TYPE_CHECKING 或延迟 import 均不能算完成。

| 编号/旧环或反向边 | 明确的替换与所有权 | 注入者与不变约定 |
| --- | --- | --- |
| C01 kernel → models：Message.ModelSelection | 只提取 ModelSelection/ModelCapabilities 身份值至 kernel，models 继续拥有 ProviderConfig 和发现策略 | 无运行时注入；原序列化字段不变 |
| C02 kernel → models：usage → RequestRecord；kernel → tools：operations → ToolSpec | usage 聚合移至 models；operation_spec_hash 移至 tools；ToolSpec 仅声明值移 kernel | composition 将 UsageService 注入 SessionView；模型计量 purpose/agent/session 身份不变 |
| C03 session ↔ agent：AgentSession → RuntimeLauncher，agent → SessionStore | session.ports 拥有完整 RuntimeLauncher；RunResult 与 HistoryJournal 共享，agent 只引用 kernel 历史契约 | app.runtime_launcher 的实现注入 AgentSession；run/resume/cancel_wait/compact/invalidate_summary/reconcile_hook/aclose 签名逐项保留；agent 不 import session；assign_initial_title 通过 agent.ports.TitleAssignment.assign(message) 注入 session 的绑定实现，保持在首次 user JSONL 写入后设置标题 |
| C04 models ↔ context：ModelControl → ContextProjector | models.ports 定义 Projector.project(snapshot: SessionSnapshot)->ProjectedContext；不导入 context 服务类 | composition 将 ContextProjector 实例注入 ModelControl；不改变 model_change/thinking_level_change 事实 |
| C05 context/models → agent：stop_reason、normalize_usage、from_assistant、to_framework | 框架消息解析/转换移 models/adapters/messages.py；输出声明只用 kernel 值；agent/history 与 context summary 使用各自端口接收 converter/request_step | composition 注入模型 adapter 的绑定函数；模型 identity、usage 缺失语义、ToolMessage pairing 不变；这些函数不从 models.__init__ 导出 |
| C06 models ↔ agent：fallback → calibrate_model_request | 校准实现移 models/adapters/calibration.py；models.ports 拥有 CalibrationContext/RegistryLookup/ResourceLookup/SummaryBudget/CalibrationReport 等 native 消费接口；不能导入 agent.RunContext | composition 将 calibrator 同时注入 fallback 与 agent；每个候选都重算有效工具/预算，fallback 不绕过计量/能力检查 |
| C07 tools ↔ agent：settlement → make_framework_tool/save_message/run_hook/PycodeState | make_framework_tool 归 tools/adapters；工具 middleware 定义仅含 failure/settlement 所需字段的私有 ToolSettlementState；save_message 通过 tools.ports.MessagePublisher.publish(message, emit_start=False)->entry_id；生命周期通知通过 ToolLifecycle 端口 | agent/adapters 的 publisher 保持 JSONL → output bind → snapshot boundary → finish event 顺序；composition 传绑定函数，tools 不 import agent；framework converter 也注入 |
| C08 tools → agent：tool_calls_may_execute | 该纯工具安全判断移 tools/domain/turn_guard.py，NextTurn/next_turn 留 agent/domain | ToolExecutor 本功能调用；截断/失败模型输出仍禁止执行工具 |
| C09 tools ↔ hooks：ToolExecutor 构造 HookContext，HookEngine 注解 ToolExecutor/Registry | tools.ports.ToolHooks.fire(event_name, context, call, spec, result=None)->str 或 None；hooks 内桥接实现负责构造本功能 HookContext；HookEngine 通过 hooks.ports.HookExecutorFactory 获得 HookExecution，具体 registry/gate/executor 都由 composition 创建 | composition 注入 HookEngine 及桥接函数；pre_tool_use/permission_request/command_execute/post_tool_use 的事件、拒绝、时点和异常规则不变，tools 无 hooks import |
| C10 tools ↔ mcp：ToolRegistry.ToolDispatch、settlement.result_from_mcp | 通用 ToolDispatch.resolve(call)->ToolCall 归 tools.ports；result_normalizer 为 tools adapter 接收的框架边界回调（由 composition 注入）；MCP 正规化纯函数留 mcp/domain | composition 将 MCP dispatcher/normalizer 作为结构化实例与回调注入；MCP hash/name/原始调用身份与错误边界不变；tools 不 import mcp |
| C11 tools ↔ memory：tool_failures.SECRET_PATTERNS | 通用脱敏归 kernel.redaction；memory 写拒绝仍是本功能纯策略；LangMem adapter 的 ToolSpec/ToolContext/ExecutableTool 用 kernel 声明 | composition 注册具体 memory tools；tools 无 memory import，不通过 source 名称获得额外权限 |
| C12 tools ↔ sandbox：BashTool 依赖 ToolContext/Spec/OutputStore；InterruptToolGate 用 sandbox policy | 声明值改 kernel；sandbox.ports.OutputSink.begin(context, mode)->StreamWriter，仅声明 Bash 真正使用的 begin/append/finish/abort；不引入 tools.ToolOutputStore 整个读回接口 | composition 注入 FileToolOutputStore；InterruptToolGate 留 tools 且只用 sandbox 公开 SandboxSettings/SandboxBackend/denied_command/auto_approvable，最终单向 tools → sandbox；审批和 env 过滤不变 |
| C13 session ↔ snapshot：RevertService/事务状态/Plan 两侧依赖 | RevertService 和 core.revert_state 整体归 session；core.snapshots.RevertPlan（含会话叶子）拆至 session/domain/revert.py；FileVersion/Snapshot/RestoreFile/RestorePlan/RestoreRecord 与 SnapshotStore 留 snapshot | session 调 snapshot 公开 SnapshotStore；snapshot 不 import 会话值/分支/JSONL。preview/apply/recover 的审批、事务记录、补偿与 leaf 语义不变 |
| C14 session → skills → snapshot → session | skills 不再调用 pending_run_entry/pending_revert：skills.ports.CatalogWriteGuard.can_initialize(snapshot)->bool 由会话执行状态适配器提供；skills 只读/追加 HistoryJournal；SkillToolGate 迁 tools | composition 把 session 的纯状态判定绑定给 SkillService；原 skill_catalog_unavailable 原因与不自动创建规则不变；skills 不 import session 或 snapshot |
| C15 models/context/hooks/mcp/memory/plans/team/tools → session canonical history | 只替换为上表 kernel 事实值、SessionTree 和最小 HistoryReader/HistoryJournal；模型 usage 中 run_belongs_to_session 用 models.ports.RunOwnership.owns(header, entry) | composition 注入 session 的归属策略；branch/aclose/recovery 保留 session，不把业务恢复函数泛化进 kernel |
| C16 context/memory → session 与模型框架耦合 | 保留 ContextProjector 在 context；memory 使用其公开 retained_context_entries(path) 纯函数，不能 import 具体 adapter；SummaryGenerator/DirectorySelector/MemoryUpdater 只收相应 Protocol | composition 构造 metered model 后注入各功能 adapter；压缩 retained tail、后台 thinking off、0 重试均维持阶段二行为 |
| C17 plans/skills → tools 与 tools 集中 ToolGate 形成潜在环 | PlanToolGate、SkillToolGate 和 MemoryToolGate 均归 tools；plans 的 QuestionPrompt.ask(payload) 端口由 plans 自己拥有；skills 工具声明/执行签名改 kernel.ExecutableTool | composition 注入 UI prompt、注册各功能工具；tools 使用 plans/skills 的纯策略/状态公开 API，两者无 tools import |
| C18 team ↔ session（阶段三工厂/唤醒/取消） | team.ports.ChildSessions.open/input_accepted、ChildSession 与 Worktrees/WorktreeSnapshotFactory 只引用 kernel 或 snapshot 公开值；唤醒复用注入的事件与会话队列，审批通过已有 pending/answer 路由；不另建空壳 LeadWakeup/ApprovalPrompt | composition 注入子会话工厂和 lead 队列；同时4人上限、JSONL关联、审批来源、cancel传播与重启中断保持阶段三约定；team 不 import session/agent/ui |

阶段三新增的 WorkerBoundaryGate/TeamToolGate 仍归 tools。它们消费 tools 自己声明的
WorkerPermission（工作根、命令限制、只读判定）与 TeamMergeAuthorization（fresh preview、
审批绑定、调用执行）端口；composition 传入 team 的结构化实现，tools 不 import team。
因此 team → tools 不会形成反向环。具体团队事实值留 team，不全部搬到 kernel。

`RunContext` 不搬进 kernel。它实际位于 agent/services/context.py，以便依赖消费端 ports 而不破坏 domain → ports 分层；DeepAgentState 留 agent/adapters。
对外能力按消费方拆成 RuntimeContext/SettlementContext/CalibrationContext 等结构化 Protocol，
只声明使用字段，不以 `Any` 替代所有类型，不通过继承完整 RunContext 重新引入环。
输入队列同理：agent.ports.InputQueue.has/take、DurableInputDelivery.pending/deliver，由
session 提供实现；agent 不反向导入 session 的 PendingInputs/DurableInputs。

### 关键消费端口的精确调用形状

这些接口只引用 kernel/所属功能公开值，不引用具体 service 或框架对象。
`CatalogWriteGuard` 是同步纯判断；SessionHeaderReader 的同步读取用于既有 owned_io 工作线程；
流式输出和会话追加方法保持 async。

```python
# models.ports
class Projector(Protocol):
    def project(self, snapshot: SessionSnapshot) -> ProjectedContext: ...


class RunOwnership(Protocol):
    def owns(self, header: SessionHeader, entry: SessionEntry) -> bool: ...


# skills.ports
class CatalogWriteGuard(Protocol):
    def can_initialize(self, snapshot: SessionSnapshot) -> bool: ...


# tools.ports
class ToolDispatch(Protocol):
    def resolve(self, call: ToolCall) -> ToolCall: ...


class ToolHooks(Protocol):
    async def fire(
        self,
        event_name: str,
        context: ToolContext,
        call: ToolCall,
        spec: ToolSpec,
        result: ToolResult | None = None,
    ) -> str | None: ...


class MessagePublisher(Protocol):
    async def publish(self, message: Message, *, emit_start: bool = False) -> str: ...


class SessionHeaderReader(Protocol):
    def read(self, session_id: str) -> SessionHeader: ...


# sandbox.ports: only the stream capability used by Bash
class StreamWriter(Protocol):
    async def append(self, data: bytes) -> None: ...
    async def finish(self, result: ToolResult) -> ToolResult: ...
    async def abort(self) -> None: ...


class OutputSink(Protocol):
    async def begin(self, context: ToolContext, mode: str) -> StreamWriter: ...
```

ToolHooks.fire 逐事件调用：pre_tool_use 先于审批；permission_request 在非只读审批前；
command_execute 在授权成功后、子进程启动前；post_tool_use 只在结果已持久化之后。
桥接层保留 HookEngine.fire 的拒绝字符串，由 ToolExecutor 转为原 hook_rejected。
不能把三个前置事件合成一次提前通知，也不能让 after 在 JSONL 完成前触发。
MessagePublisher 是本 run 绑定的对象，内部保持原 save_message 对 event/JSONL/output 的顺序。
SessionHeaderReader 只从输出存储的 owned_io 工作线程调用，composition 注入 session
adapter 的同步有界读取实现并维持同一身份校验，不在线程内跑 event loop。

框架 `request -> request` calibrator、ToolMessage converter 与 middleware factory 的
Callable 形状只定义在对应 adapters 文件，不能把 ModelRequest、BaseMessage、
BackendProtocol 等框架类型写进 domain/ports/service。models.ports 中的
CalibrationContext/RegistryLookup/ResourceLookup/SummaryBudget/CalibrationReport 只包含 kernel 值和结构化能力；
composition 注入负责把它们绑定到当前 run。

## 跨功能 adapter 的具体消除方式

“只经公开 API”不等于“把 adapter 重导出即可”。各功能 `__init__.py` 只导出 domain、
ports、无框架 service 与纯函数；**任何经别名/转发最终定义在 adapters 的符号都禁止导出**。
例如 AgentRuntime、ToolSettlementMiddleware、PycodeBackend、MeteredChatModel、
framework converter、GitSnapshotStore、Textual PyApp 只能由 composition 跨包直接导入。

| 现有跨功能 adapter import | 目标注入边界 |
| --- | --- |
| SkillBackend/PlanBackend 继承 tools.PycodeBackend | 改为组合：skills/plans 各自 adapter 拥有 BackendOperations 结构化端口（在 adapter 文件定义，可含 Deep Agents 返回类型），由 composition 传入 PycodeBackend 实例。校验后委托 read/ls/glob/grep/download/write；禁止 fallback 绕过 Skill 版本校验或 active Plan exact path |
| MCP manager / skills resources 自建 tools.JsonSchemaValidator | mcp/skills 各自 ports 接受 SchemaChecker.check_schema/validate 的纯签名；composition 传同一 validator 实例，禁止模块中自建跨功能具体类 |
| HookActions/PythonSkillTool import sandbox.run_process | hooks/skills 的 adapter 接收 ProcessRunner callable，composition 注入 sandbox/adapters/process_runner.run_process；subprocess_environment 为 sandbox 公开纯策略可正常 import |
| PythonSkillTool import sandbox.RequiredSandbox 并 isinstance/for_skill | composition 注入 SkillReadGrant（verified activation root -> sandbox launcher），skills 不认识 RequiredSandbox；只有 catalog.verify_resources 成功后授权精确 Skill 根只读，worker 固定写根与 dotenv deny 保持不变 |
| PythonSkillTool import tools.resolve_workspace_path | 安全路径校验纯函数移 kernel/path_policy.py；其 Path I/O/解析版本留 kernel/io，adapter 可用，domain 不能用 |
| WorktreeManager 自建 snapshot.GitSnapshotStore | team.ports.WorktreeSnapshotFactory(workspace, directory)->SnapshotReader，由 composition 注入 factory；只共享 snapshot 公开值和端口，不经 team 公共 API 泄漏 Git 实现 |
| tools output_store import session_codec.decode_header | tools.ports.SessionHeaderReader.read(session_id)->SessionHeader，composition 注入 session adapter 的有界只读 header reader；FileToolOutputStore 不自行解析别的功能的存储格式 |
| agent 自建 ToolSettlementMiddleware/PycodeSkillsMiddleware/模型 middleware | AgentRuntime 构造器接收已构造的 middleware、tool factory、converter、backend；agent 可创建本功能自己的 history middleware。composition 建具体跨包 middleware 并指定原有顺序，禁止 agent 经公开 API 导入具体类 |
| context summary、memory consolidator 使用 metered SDK 模型 | composition 构造 models adapter，注入 BaseChatModel 依赖给功能内 adapter；各功能 service 只接收自己端口，无框架类型 |

BackendOperations 不能拥有默认实现或从具体类动态取方法：列出 read/ls/glob/grep/download_files/
write 等实际需要的方法，并保留 async 对等方法。代理包装只转发已校验路径；SkillBackend
的 version receipts 由 skills 自己维护，PlanBackend 的 active_name 由 plans 自己维护。
不会将 filesystem 后端整块丢进 kernel 解决继承问题。

## 对外 API 初始清单与实现归属

| 包 | 可导出的 API 示例（非 adapters） | composition 才能直接引用的实现示例 |
| --- | --- | --- |
| kernel | 上述共享值/最小协议、纯哈希、纯值转换、SessionTree | kernel.io 基础设施可供各功能 adapter 使用，domain 不得引用 |
| models | ProviderConfig/ModelReference/RequestRecord/RetryPolicy、ModelControl、ModelCatalog、UsageService、Projector/RunOwnership | ModelFactory、SQLiteRequestStore、MeteredChatModel、消息转换/校准/request-step |
| context | InputBudget/ContextMeter/SummarySettings、ContextProjector、retained_context_entries、SummaryService、SummaryGenerator | SummaryWorkflow、FileSummaryStore、ProjectPromptSource、InstructionFileBackend |
| tools | ToolRegistry/ToolExecutor、ToolGate/ArgumentValidator/ToolDispatch、权限/失败纯策略、输出端口 | framework settlement/permissions/question middleware、PycodeBackend、输出存储与 schema validator |
| sandbox | SandboxSettings/SandboxStatus、SandboxBackend、command/env 纯策略 | BashTool、SeatbeltSandbox、process runner、WindowsJob |
| snapshot | Snapshot/RestorePlan 等纯文件值、SnapshotStore | GitSnapshotStore |
| session | AgentSession/SessionView/RevertService、SessionStore/RuntimeLauncher、恢复/导航纯策略 | JsonlSessionRepository、codec/header reader、history backend、持久输入存储 |
| skills | SkillService、SkillCatalog/SkillInstaller、skill_scope/active_skills 等纯策略 | SkillBackend、PythonSkillTool、资源 parser/SDK middleware |
| plans | PlanService、PlanStore/QuestionPrompt、PlanState/PlanDocument | FilePlanStore、PlanBackend、框架 plan tools |
| hooks | HookEngine、Hook/HookContext、HookActions/HookExecution | HookActions 实现、进程 runner bridge |
| mcp | MCPService、MCPRuntimeBindings、MCPServer、active_mcp/result_from_mcp 等纯函数、MCPConnections | MCPAdapterConnections、连接/配置适配器、MCPTool |
| memory | MemoryService、MemoryRecord/MemorySnapshot、namespace/过滤纯策略、MemoryUpdater | JsonMemoryStore、MemoryChangeLog、LangMem tools/consolidator/selector |
| agent | NextTurn/next_turn、RunContext 的无框架部分、能力 Protocol | AgentRuntime、DeepAgentState、HistoryMiddleware |
| team | 团队/任务/消息服务与值、ChildSessions/ChildSession/WorktreeSnapshotFactory | worktree/进程内 SDK 工具适配器；最终类名以阶段三提交为准 |
| ui | InteractiveSession/QuestionPrompt 等 UI 消费端口 | PyApp、Textual widgets、ConsoleOutput |

上表也是文档中的 port→实现对照的输入；实施时必须将每个具体端口补为实际类名与路径，
不能以未实现 Protocol 自己充当实现。结构化 Protocol 的注入对象可在另一功能实现，
但功能 public API 不重导出那个具体 adapter 类。

## 组合根拆分

`app/container.py` 删除，改为 `app/composition/`：

| 模块 | 职责 |
| --- | --- |
| `__init__.py` | CLI/测试使用的稳定构建 API |
| `runtime.py` | AgentRuntime、backend、tool gates、hooks 和 MCP 的装配 |
| `session.py` | 会话仓库、SessionView、AgentSession、交互会话集合 |
| `memory.py` | 阶段二 MemoryService 和 LangMem 模型装配 |
| `context.py` | SummaryService、summary generator、投影与压缩装配 |
| `models.py` | Provider factory、catalog、计量、fallback、选择器装配 |
| `team.py` | 阶段三团队、子会话工厂及 worktree 适配器装配 |
| `ui.py` | CLI 输出和 Textual App 构造 |

业务判断不能随 container 一起机械拆分：选择器兼容性属于 models；
MCP/Hook 恢复配置校验属于各功能服务；记忆命名空间属于 memory/domain。
旧 SessionOutputReader 已由阶段二删除，不为满足旧文件清单而重新创建；若仍需输出读取
业务逻辑则归 tools 的输出读取服务。具体 adapter 类不经包公开 API 泄漏。
组合模块之间若互相依赖，用构造回调注入，不保留函数内 import。

## 静态架构检查

重写 `scripts/check_architecture.py`，保留品牌、依赖锁和单 harness 检查：

- 识别功能包、domain/ports/service/adapters 和组合根。
- 正确解析绝对 import、相对 import、包内 `from . import name`。
- 检查跨功能访问仅经公开 API，且访问符号在显式 `__all__` 中；除环检查外，逐边匹配
  `feature-dependencies.json` 的允许图。
- 沿 import/alias 解析公开符号的真实定义，禁止 `__init__` 经别名、二次转发或工厂包装
  导出 adapter 实现；仅允许 composition 构造它们。禁止把定义搬出 adapters 来规避识别，
  无框架 service 依赖框架的检查仍独立执行。
- 检查 domain 无 I/O；domain、ports、service 无框架引用。
- domain I/O 检查覆盖 import 别名及 open/input/print，而非仅字符串扫描。
- 检查模块图和功能图均无环，报告完整环路径；TYPE_CHECKING 中的 import 也计入。
- 检查只有 app/composition 可以跨功能引用具体 adapters。
- 检查 pycode 不依赖 evals/scripts/archive，evals 不依赖 archive。
- 检查没有旧层导入和函数内延迟 import。

更新架构测试，增加违规跨 API、别名、相对路径、跨包 adapter、函数内 import、
模块循环和功能循环的负例。阶段四只编写测试，不执行 pytest。
架构文档补全 port→实际实现对照表；保留真实缺口，不能拿 Protocol 本身充当实现。

## 搬迁顺序与验证

先完成共享契约提取及消环，再按计划顺序搬独立功能：
mcp/hooks/sandbox/skills/plans/snapshot；models/context/session/tools；
memory/agent/team/ui；最后拆 composition。测试目录同步按功能移动，
保留 support 等测试设施，evals 保持独立。

阶段三合入后的设计实施入口先重新扫描全部源码：默认 module-map 与实际模块一一对应，
新增团队模块必须归属；module-splits 中所有旧符号必须仍存在或标记由阶段三删除。
按 C01–C18（含阶段三 gate 约束）记录实际落地路径、注入位置与最终 import 边，
与 feature-dependencies.json 比对；存在未解释的 SCC 就退回设计，不开始批量搬迁。
不把旧图的 SCC 数量当作迁移完成证据，最终必须检查真实代码导入图。

每个批次运行 ruff check、ruff format --check、架构检查、compileall 和导入冒烟。
导入冒烟扫描所有产品模块但不调用资源构造函数，不联网、不调用模型、不运行测试。
阶段四完成后独立审查设计对应实现、消环记录和所有调用路径；
审查通过再提交，rebase 到阶段三结果并 ff-only 合入 main。
完整 pytest、Seatbelt、发行安装和离线评测留到阶段五完成后的统一测试。
