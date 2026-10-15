# 模块装配修订

用户于 2026-10-15 指出：功能目录和依赖无环尚不足以实现模块解耦。此前阶段四的静态通过
只证明依赖规则，不能作为当前模块化目标已经完成的结论。本设计修订阶段四；阶段五暂存成果保留。

## 目标

从 `app/bootstrap.py` 的 `compose_application` 开始读应用。CLI、TUI 接线、评测和开发脚本
使用此入口；`app/composition` 仅保留内部的 application/session/run 跨模块连线。
不再从其 `__init__.py` 暴露几十个零散构造 API。

每个功能在自己的 `module.py` 提供具名装配入口。它负责本模块的配置解释、适配器选择、
服务构造、会话/运行资源及关闭动作；只接收自己的配置值和显式依赖，不导入 `app`。
装配入口可以依赖本功能的 adapters；其他功能不能导入它的 adapters 或装配入口。
应用组合根只能调用功能模块入口和公开的原生契约，不再直接创建功能内部适配器。

模块允许依赖共享 kernel 和显式列出的其他功能公开契约。独立可读并不要求复制通用事实
或创建另一套依赖注入框架；不用全局容器、字符串注册器、动态 import 或兼容转发壳。

## Skills 与 MCP 垂直边界

Skills 模块拥有 catalog/installer/service、资源工具、只读后端路由、原生框架扩展、
工具披露/作用域约束及历史投影贡献。根向它提供 schema 验证器、进程执行器、工作区后端工厂、
Skill 只读沙箱授权及工具转换器；模块不自行获取其他功能的实现。

MCP 模块拥有配置、连接、catalog/service、控制工具、动态工具、原生调用边界、
历史投影贡献及关闭。上下文输入额度由根传入数值；不得为读取额度反向依赖 context。
禁用时不读取配置、不创建连接、不安装扩展；历史显示所需纯投影仍可由调用方明确选择。

模块产物是具体而有类型的工具、后端路由、框架扩展、投影贡献与生命周期所有者。
不得只把旧构造函数包一层 Module，仍由根处理其内部知识。
Agent 接收有序 middleware 工厂/模型前过滤回调，不内置 Skill 的工具组解析和别名表。
ContextProjector 接收有序原生投影贡献器，Skill/MCP 的 JSONL 重建留在各自模块。
将 Skill gate 的领域判断放回 Skills，工具执行层只组合返回的权限约束。
所有扩展仍经过统一工具执行和 worker 强制外层审批，不允许模块扩大权限。

## 统一根与生命周期

`compose_application(config, providers, modules=...) -> Application` 暴露会话、查看、模型、
存储、记忆、终端界面的应用操作。模块集合显式构造，可分别替换/禁用 Skills 和 MCP。
内部 session/run 组合代码只连接模块产物，不承担这些模块的配置解析、协议转换或投影重建。

配置、应用、会话和运行的资源作用域分开。保持既有 close/cancel 次序：初始化失败收拢已创建
的资源，取消等待会等待拥有的任务，工具结果落盘后才 ACK/发布完成，重启不自动续跑。

## kernel 四层

kernel 根只保留包入口；统一为 `domain / ports / services / adapters`。

- domain：跨模块交换的 Message/ToolCall/ToolResult、ToolSpec/ToolContext、事件信封、
  持久化 SessionEntry/Header/Snapshot、完成结果与不可变模型选择快照；通用值、哈希、
  脱敏、截断和共享不可变历史树查询。历史树作为共享事实查询保留，不放业务流程或持久化实现。
- ports：EventSink/Journal、Timing、HistoryJournal、ExecutableTool 等接口。
- services：依赖端口的事件投递编排 EventStream。
- adapters：系统时钟、文件锁、受控文件 I/O、内存 EventJournal 实现。

按主要业务归属归还算法：repair_tool_pairing 和 estimate_messages 回 context；
model_tool_text 回 models，并由 context 经 models 公开纯策略复用；Python Skill 参数路径解析回 skills。
压缩保留边界属于 context，session 通过明确依赖其公开纯验证策略保留写入校验。
ProjectedContext 暂作为共享投影交换事实保留，禁止放入任何 Skill/MCP 字段或实现；
不为了搬一个值对象制造反向依赖或空协议。
kernel 根不重导出具体 I/O adapters；其他 adapters 需要共享文件基础设施时显式使用其实现路径。
纯 domain/ports/services 仍禁止向外依赖 adapters 和框架。

## 检查与验收

1. 架构检查强制四层、无环、公开契约、功能模块入口边界，组合根无功能内部 adapter imports。
2. Skills/MCP 可单独创建、替换、禁用；不修改 Agent 内核。
3. 功能模块不 import app，也不 import 其他功能 module/adapters。
4. 模块局部 README 说明职责、输入/输出、资源寿命及最小装配示例，实际示例与接口一致。
5. 静态检查与独立设计/代码审查后，统一执行完整行为、模块替换/禁用和生命周期测试、发行、
   Seatbelt 及离线评测；费用和旧运行产物清理继续遵循原计划的明确授权要求。
6. 文件搬迁用 git mv，删除源码用 git rm；无旧包转发壳。旧目录仅缓存残留已严格核对后清理。

## 实施契约

### 应用入口

```python
compose_application(config: AppConfig, providers: tuple[ProviderConfig, ...] = (),
                    *, modules: ApplicationModules | None = None) -> Application
```

`None` 的 modules 表示创建默认模块集合；`default_modules(config)` 返回可替换的显式集合。
集合中的 `skills=None` / `mcp=None` 表示禁用对应运行能力，禁止读取其旧配置或建立旧连接；
不是“使用默认”。自定义模块必须满足根实际消费的端口，不能依赖全局 service locator。

Application 的实际出口：`status()`、`sandbox_status()`、`session_service()`、
`session_repository()`、`open_session(id, *, create=False, tools=(), consumers=(),
refresh_models=True, startup_hooks=True, connect_mcp=True, team_member=None,
prompt_suffix="")`、`open_view(id)`、`model_catalog()`、`model_control(store=None)`、
`memory_service(history, provider=None, consumers=())`、`storage(owned_session_id=None)`、
`worktrees()`、`console(output_format)`、`tui(session_id=None, tools=())`。
评测/维护还使用 `runtime(provider, **run_options)`、`background_model(provider, consumers,
session_id, run_id, purpose, **request_options)`、`memory_updater(provider, consumers=())`、
`summary_workflow(model)`。这些是有实际消费者的接口，不再公开其他内部构造碎片。

`providers=()` 支持 doctor、存储、本地记忆、历史查看及会话列表；只有真正执行模型的出口
校验 Provider。集合在组装时不连接外部服务，不创建活跃会话。

### 功能出口与依赖

| 功能入口 | 自有产物 | 显式外部输入 |
| --- | --- | --- |
| skills/module.py | SkillsModule；session service、run selection/resources/recorder、tools/routes/middleware、projection | schema、process runner、backend factory、沙箱 grant、工具 converter |
| mcp/module.py | MCPModule；session connections、run bindings/tools、projection、close | schema、credentials、输入额度 |
| hooks/module.py | Hook 配置、action runner、engine | process runner、sandbox、模型/委派 callback、executor factory |
| sandbox/module.py | native/required backend、Bash、runner、Skill grant | settings、outputs |
| snapshot/module.py | snapshot store | workspace、storage root、exclusions |
| models/module.py | catalog、model owner、requests、meter/fallback/selection、converter | credential mapping、database、timing、projector |
| tools/module.py | validator、backend、outputs、executor、强制 gate pipeline、settlement | snapshots、sandbox、publication、hooks、具体权限约束 callback |
| plans/module.py | plan service/files、exit tool、projection | history、backend factory、paths |
| memory/module.py | JSON store/log、memory service/updater、projection | history、model owner/facade factory、paths、trust |
| context/module.py | projector、summary/eviction、prompt source/workflow | contributors、model callbacks、records factory、paths |
| session/module.py | layout/repository、view、storage GC、application status | timing、各业务能力产物与launcher |
| agent/module.py | runtime + native bindings | model、tools、backend、ordered extension factories、converters |
| team/module.py | team、worktree manager、event relay | child-session callback、repository、snapshot factory、events |
| ui/module.py | console、TUI | InteractiveSessions、output stream |

每个入口必须实际拥有内部装配，不能只转发另一个 root 工厂。保留有意义的原生服务/策略
API，模块入口是应用装配边界，不要求业务层绕道 module.py 调用服务。

### 扩展顺序与强制边界

保持现有顺序：Skill 披露和作用域过滤完成后，history 边界取得最终工具 schema，执行
selector/预算校准，再请求模型。框架扩展按显式构造的有序序列安装；Agent 内核不含 Skill
别名表或资源组解析。统一权限和 worker 外层 gate 不参与可选扩展排序，始终不可被替换绕过。
结算保留原顺序：完成结果正式持久化/输出绑定/事实事件 → team ACK → executor.completed
及 Hook/完成通知。GraphInterrupt、取消与存储错误继续穿透。

### 产物与关闭责任

SkillsSession 拥有 catalog/service，不持有后台连接；SkillsRun 拥有 selection、resources、
recorder 及框架扩展。run 结束后不向下一 run 复用可变披露状态；资源工具的子进程仍由原生
owned runner 等待和取消，无额外火后即忘任务。

MCPSession 拥有唯一 connections；创建成功立即登记关闭动作，转交 session launcher 后由
session.aclose 唯一关闭。连接创建/启动失败，仍由构造端回收；run bindings 不关闭共享连接。
异步配置加载用已有 owned task + shield，取消时等待被拥有的工作结束再传播取消。
替换/禁用模块不能先连接默认实现再覆盖；本次根实例的每个产物只来自所选模块。

### 投影贡献

`ProjectionContributor.contribute(snapshot, path, entries, checkpoint) -> ProjectionContribution`。
输入为共享不可变事实/当前 entries 和只读 checkpoint；返回新增 entries、自己拥有的
checkpoint 更新及明确删除的自有 keys。每个 contributor 声明 `name` / `checkpoint_keys`，
ContextProjector 检查注册 key 无重叠，禁止写/删他人的 key；不能就地改共享字典或旧事实。

固定顺序保持 memory → plans → context 自身 system prompt → MCP → Skills。
key 所有权：memory=`memories`，plans=`plan`，context=`prompt_snapshot/system_prompt`，
MCP=`mcp`，Skills=`skills`。因此本轮 pin_memory/pin_plan 也移回自己的功能，避免留下同类缺口。
Skills 仍保留 compaction/fork 来源、原 read_file 调用配对、正文已在 entries 中时避免重复。

运行模块禁用与历史显示分开：历史查看可显式安装纯 projection contributor，不读取配置、不
开启模块连接或安装运行工具。应用默认历史显示保留全部已知原生事实；运行能力仅来自所选模块。

独立设计审查：2026-10-15 复审通过。实现通过与行为通过另行记录，不以设计结论替代。


## 实施补充

- 模型 owner 与低层 runtime 是 async 装配，失败时等待资源关闭，不留下清理任务。
- `ApplicationModules.scoped(config)` 调用已选模块自己的 scoped hook；不创建默认集合，
  不覆盖自定义 ModelsModule 的 credential mapping/callable。跨工作区只重绑所需配置。
- ProjectionContribution 可返回 `usage_invalidating_entry_ids`，host 校验属于当前 path；
  ProjectedContext 用单独元数据承载，不加入 checkpoint/config_hash，不改 JSONL。
  `context_config_system_prompt=False` 表示该投影消息不重复拼入 system fingerprint。
- 贡献器通过 `incremental_safe` 显式声明普通追加不影响既有贡献。仅全部声明安全时采用
  增量快路径；未知或 False 均重算，同 revision 仍缓存。当前 Skills 保守为 False，
  默认 SystemPrompt 的单消息增量与共享 immutable messages 保留；跨追加保留 usage invalidation IDs。
- Session/run 构造和关闭都尝试收拢全部已拥有资源；前序失败、重复取消和重复关闭有回归用例。

当前实际模块图见 `module-assembly-import-graph.json`；旧 `final-import-graph.json` 仅为首轮阶段四快照。
