# 当前工具发现流程

2026-10-22：模型工具选择器已删除，本地与 MCP 统一采用名称/一句话分类目录和 ToolSearch 按需加载。详见 [统一工具发现](tool-discovery.md)。

# 组件精简（2026-10-22）

当前目标是减少重复执行机制和无意义转发，让代码按功能可读。功能范围以本文为准；
旧 module-assembly 设计保留为此前迁移记录，未迁移的核心模块仍遵守其生命周期和存储契约。

## 已确定范围

保留 Team 邮箱、任务、子会话、worktree、审查/合并/清理。Skills 保留本地读取、创建、更新、
下载和框架加载；用户已明确脚本全部通过 Bash 执行，删除 Python 函数自动注册及专用启动器。
删除 Skill fork、agent/background Hook、MCP resources/prompts/elicitation；保留统一 ToolSearch，删除 MCPCall。

## 目录与入口

Skills、MCP、Hooks、Team 按职责平铺；不强制每个组件都有 domain/ports/services/adapters。
公开入口是组件 __init__.py，api.py 定义主要操作。module.py 保留必要构建，只有应用组合根调用。
历史投影、工作区合并等真实职责不因文件数量指标被挤入一个大文件。

| 组件 | 阅读入口 | 主要实现 |
| --- | --- | --- |
| Skills | skills/api.py | catalog.py、install.py、framework.py、history.py |
| MCP | mcp/api.py | connection.py、tools.py、framework.py |
| Hooks | hooks/api.py | config.py、actions.py、conditions.py |
| Team | team/api.py | engine.py、mailbox.py、tasks.py、worktrees.py、tools.py |

## 不变的正确性边界

模型工具仍经过 ToolSettlementMiddleware → ToolExecutor → 结果持久化 → 完成事件/Team ACK。
不存在公开的裸 executor 捷径。Hook 的同步外部动作保留权限、计划/只读约束和中断核对。
所有命令共用 sandbox 的进程执行器。Skill 不再创建自己的进程执行机制。

JSONL 仍是原始历史的持久记录；Graph 为当前运行执行状态；Context 只生成模型投影。
不升级依赖、不迁移历史格式、不恢复重启前的旧审批、不自动重跑旧工具。
历史中旧 Skill 资源工具的事实可以继续展示，但不会重新注册为可执行工具。

Skill load 只准备请求，AgentSession.load_skill 为兼容用户命令明确运行一次。
创建/更新和下载安装均不会启动模型或自动激活 Skill；更新必须提供正文 revision。

## 框架承担的工作

Deep Agents 驱动模型/工具图；SkillsMiddleware 读取/加载 Skill；MCPAdapter 处理协议和
原生工具转换。MCPRun 合并原有运行与原生调用包装。项目只保留配置、工作区/权限、
历史与输出持久化、界面适配和明确不同于默认行为的规则。

## 验证

实际测试证据与剩余事项见 simplification-progress.md。测试覆盖删除功能的明确拒绝、
Skill Bash 审批/拒绝、Skill 创建更新冲突、MCP 搜索分发、同步 Hook 取消、Team 邮箱与 worktree。
架构检查支持平铺组件，并继续禁止跨组件访问实现和循环依赖。真实服务与物理沙箱的结论
必须来自对应验证，不用本地固定模型测试替代。

## 应用入口

`app/bootstrap.py` 只构建 `Application`；应用操作位于 `app/api.py`。
五个短小跨组件构建文件合并为 `app/composition/factories.py`，组件内部装配仍由各自 module.py 持有。
其他核心组件全平铺是原计划的可选后续工程，本次没有为追求文件数继续大规模改写核心存储、模型和 UI。

## 组装参数后续整理

`app/composition/inputs.py` 的 `SessionResources` 与 `RunOptions` 分别表达会话能力和单次运行输入。
`build_runtime(config, *, modules, session, options)` 不再维护约三十个散落的参数；内部要求显式
传入已选择的模块集合，默认模块选择仍在应用入口。公开底层入口改为
`Application.runtime(SessionResources(...), RunOptions(...))`，已迁移维护的调用方。
两个数据类只存储引用，没有资源创建/释放方法；权限、工作目录仍以 AppConfig 为准。

## 后续决定：同步 Hook、单模型与严格格式

用户进一步明确：删除自动备用模型；Hook 仅同步提醒/检查/拦截，移除专门的中断核对入口，
保留记录但不自动重跑；删除旧格式转换。此决定取代本文前述关于兼容和核对入口的保留说明。
当前格式版本和拒绝策略见 docs/storage-format.md。现有文件不会被批量迁移或删除。
