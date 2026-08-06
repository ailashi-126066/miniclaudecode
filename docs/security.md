# Security model

## 不可信内容与受控委派

仓库文件、Web、MCP、Skill、历史记忆和外置工具结果都属于不可信数据。
`RegistryToolExecutor` 会扫描成功的工具结果，识别指令覆盖、密钥外传、系统 Prompt
暴露和强迫工具调用等提示注入信号。命中内容会携带风险等级与信号列表，但扫描本身
不会扩大权限，也不能替代审批和沙箱。

`agent:delegate` 创建的子 Agent 只拥有只读工具白名单，不能写文件、运行命令、调用
MCP 或向用户申请权限。规划、写入、审批、验证和最终答复始终由中心 Agent 完成。

## 文件系统

所有本地文件工具接收工作区相对路径。解析同时检查 lexical normalization、真实路径与符号链接，工作区外路径会拒绝。写入使用临时文件和原子替换；审批请求绑定目标、before hash 和 diff hash，避免 TOCTOU 后应用过期审批。

## 命令与网络

命令先经过 `CommandPolicy`：deny fragment 命中时硬拒绝，审批不能绕过；allow prefix
仅快速放行非高危命令；未命中项在严格 allowlist 模式下拒绝，否则交给风险分类和审批。
安全策略只能来自用户配置，项目配置不能覆盖 `security`。

通过策略后，命令由 `ProcessBuilder` 启动：Windows 选择 PowerShell，Linux/macOS
选择 POSIX shell。分类器识别删除、权限、系统管理、网络下载和其他高风险模式；安全
测试/构建命令可以直接执行，高风险命令要求审批。

### OS 级沙箱

#### Windows 模式边界

Windows 原生运行时当前没有可用的 OS 级文件系统沙箱后端。三种策略的行为是明确且可预测的：

| `MINICLAUDE_SANDBOX` | Windows 原生行为 | 适用场景 |
|---|---|---|
| `auto`（默认） | 显示 `NONE`/降级提示，在命令分类与人工审批后直接运行 PowerShell | 本地开发、可信仓库 |
| `required` | 因无隔离后端而拒绝所有 shell 命令，不会静默退回直跑 | 必须 fail-closed 的自动化 |
| `off` | 明确关闭沙箱，仍保留 denylist、风险分类和审批 | 已有外层隔离的环境 |

高安全任务不要把 Windows `auto` 当成安全边界。推荐把工作区放进 WSL2 并安装
`bubblewrap`，或在容器/受控虚拟机内运行；如果必须原生运行，则使用 `required`，并接受
shell 工具不可用。WSL2 与容器中的进程按其 Linux 环境探测沙箱，不按宿主 Windows 处理。

分类与审批决定命令是否运行；沙箱限制它运行后的影响范围。Linux 使用 bubblewrap（`bwrap`：PID/UTS/IPC namespace 隔离、`--new-session` 防 TIOCSTI 终端注入、系统目录与 `$HOME` 只读、工作区和工具链缓存 `~/.m2`/`~/.npm`/`~/.gradle` 可写——缓存可写是显式放宽，为了让构建保持增量），macOS 使用系统自带的 `sandbox-exec`（写入限制在工作区、`$TMPDIR`、临时目录和 `~/.m2`/`~/.npm`）。Windows 没有纯 Java 可用的隔离原语，会显式降级。

两个刻意的取舍：**网络默认放行**（`mvn`/`npm install` 需要网络，一个会被用户关掉的沙箱不如一个被保留的弱沙箱——文件系统隔离才是主要收益）；**探测不到后端时降级而不是拒绝执行**（工具保持可用），但降级是可见的——审批提示会写明当前沙箱状态。通过 `MINICLAUDE_SANDBOX` 环境变量选择 `auto`（默认）、`required`（无后端时拒绝执行）或 `off`。

诚实的边界说明：这不是完整的安全边界。沙箱内的进程仍可访问网络、读取大部分文件系统（macOS 策略刻意允许读），bubblewrap 的用户 namespace 在部分发行版默认关闭。真实 Claude Code 的 sandbox 更完善；本项目的防御纵深是「分类 → 审批 → 沙箱」三层叠加，而非任何单层。

Web Fetch 仅允许 HTTP(S)，手动处理并重新校验最多五次重定向，限制连接/请求时间和响应字节。localhost、私网和 link-local 地址需要审批，已知云元数据地址硬阻断。DNS 在每次重定向后重新分类。

## MCP 与 Skills

stdio MCP 会启动本地进程，因此必须在用户配置中显式设置 `launch-approved: true`；项目配置不会加载 MCP。每次 MCP 工具调用仍由本地 Registry 生成审批，服务端声明不能直接提升权限。Streamable HTTP 使用非 legacy SSE transport。

Skill 仅把指令文本送给模型，按需读取时再次验证真实路径和符号链接。Skill 不执行代码，也不会改变工具权限。

## Secrets 与审计

JSONL codec 会按字段名清理 authorization、api-key、token、secret，并替换当前配置已解析出的 Key。不要把真实密钥放进项目配置、prompt、Skill 或命令参数；异常仍可能包含第三方库生成的上下文，因此生产演示前应检查日志。

## 审批范围

审批菜单只展示当前工具真正会消费的范围，各工具族支持的范围不同：

| 工具族 | once | turn | file | permanent |
|---|---|---|---|---|
| 文件变更（workspace:write/edit/apply_patch） | ✓ | ✓ | ✓ | ✓ |
| Shell（shell:run） | ✓ | ✓ | — | ✓ |
| Web Fetch、MCP 工具 | ✓ | — | — | — |

选择工具不支持的编号会被拒绝并要求重选，而不是静默降级为 once。永久规则写入用户目录
`permissions.json`，文件修改与 Shell 共用同一个持久化规则存储。TURN 规则同时绑定
session id 和 turn id，切换或恢复其他会话不会继承旧会话的临时授权。建议面试演示默认
使用 once；只有明确理解目标范围时才使用 permanent。
