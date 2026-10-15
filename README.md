# pycode

本地 Python 编码 Agent。唯一实施规范是项目根目录的 `第二个计划.md`。执行栈固定为
`deepagents==0.7.23`、`langchain[mcp]==1.4.4`、`langgraph==1.2.14`。
主循环由 `create_deep_agent` 执行，文件入口为 FilesystemMiddleware，Skill 加载由
SkillsMiddleware 执行，MCP 连接和协议由 LangChain 内置 MCPAdapter 持有。

## 安装与运行

Python 3.11+；开发验证环境是 Windows／Python 3.12.10。文件搜索需要 PATH 中的 `rg`，
Bash 使用 Git Bash 或系统 Bash。Git tree 快照和修改型 Worker 需要项目已经存在 Git 仓库。
本项目开发目录保持非 Git；相关测试只使用 `tests/` 中的临时仓库。

如果旧版本曾在某个虚拟环境中注册 `py` console script，请在该旧虚拟环境中卸载旧包或删除该虚拟环境，再安装当前版本；不要删除或改写系统 Python Launcher 的 `py` 命令。

```powershell
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements-dev.lock
.venv\Scripts\python.exe -m pip install --no-build-isolation --no-deps -e .
.venv\Scripts\pycode.exe --help
.venv\Scripts\python.exe -m pycode --help
.venv\Scripts\pycode.exe doctor --json
```

Provider 配置格式见 `docs/provider-config.example.json`。API 密钥通过环境变量引用，
也可来自原工作区的本地 `.env`；密钥不复制到评测／Worker 工作区，不写入 JSONL。

```powershell
pycode --workspace C:\project --memory-mode off run "Inspect this project" --providers providers.json --cached
pycode --workspace C:\project tui --providers providers.json
pycode --workspace C:\project session list
pycode --workspace C:\project session name SESSION_ID "项目分析"
pycode --workspace C:\project session open "项目分析"
```

## 独立质量与轨迹评估（开发目录）

在开发虚拟环境安装 `.[dev,eval]`，并使用 `requirements-dev.lock` 约束版本。
OpenEvals 固定为 0.2.0，AgentEvals 固定为 0.0.9。数据集和评分标准在
`tests/evals/quality-cases.json`，运行前固定；所有运行数据保存到 `tests/.runtime-tests/`。

```text
python tools/run_output_quality.py
python tools/run_trajectory_quality.py
python -m archive.tests_support.verify_live_inferred_rule_evaluation
python tools/run_rule_comparison_quality.py --source tests/.runtime-tests/live/RUN/result.json
```

输出评估实际调用记忆应用服务、摘要工作流和官方规则优化器。轨迹评估执行当前
AgentSession，但使用本地 HTTP 服务注入可控选择、重试、fallback 和中断；这些轨迹
不能替代真实远程服务验收。生成调用与裁判调用分开记录，裁判不执行业务工具。

每项都带错误输出／轨迹负控制。范围拒绝、作业未完成或裁判失败不能算质量通过。
评估脚本支持只恢复未完成项：`run_output_quality.py --resume-memory-from DIR
--only memory-preference --retry-memory`，`run_trajectory_quality.py --resume REPORT
--only tool-selection`，以及规则裁判的 `--resume-positive-grades REPORT`。
保留原始报告；`tools/summarize_quality_suite.py --help` 给出完整汇总参数。
模型评分是固定小数据集诊断，不替代确定性测试和推断规则的运行时采用凭据。

## 主模型工具选择

Provider 配置可包含 `tool_selector`。默认开启，候选可选工具超过 8 个且候选数达到 24
或声明估计达到 12000 token 时，使用官方 LLMToolSelectorMiddleware。读取、发现、回读、
提问与当前计划出口等必需工具在权限过滤后保留，不占可选工具的 8 个额度；Provider 专有
工具另外保留。候选较少时跳过选择请求。

```json
"tool_selector": {
  "enabled": true,
  "max_tools": 8,
  "candidate_threshold": 24,
  "schema_token_threshold": 12000,
  "input_limit": 16000,
  "output_limit": 1024,
  "always_include": [],
  "on_failure": "all"
}
```

不设置 `model` 时使用当前已配置模型；也可设置
`"model": {"provider": "已配置名称", "model_id": "已声明模型"}`。选择模型须支持 schema 工具，
使用独立的受控请求、最多 1024 输出 token，默认关闭选择请求的 thinking；主模型配置不变。
工具名／说明通过官方组件的结构化 schema 传入，未选择 Skill 正文不加载。

失败或无效选择默认返回已经通过权限过滤的声明集合，继续执行真实预算检查；
`on_failure: "error"` 则停止主请求。取消、程序缺陷和关键持久化错误向上传播。
选择后的实际 Schema 计入预算；重试复用相同选择与排列顺序；未声明或版本已变化的
工具不执行。JSONL 审计保存前后 Schema 估计，实际用量独立归属 `tool_selection`。

名称是显示 metadata。完整 session_id 仍是唯一身份；重名要求明确选择 ID。
TUI 支持 `/session name 名称`、`/session open ID-or-name`、`/session fork 新ID`、
`/plan`、`/memory`、`/storage status`、`/storage gc --dry-run` 和 `/skill:NAME 参数`。

## 主模型故障切换

每个 Provider 可声明有序的 `fallback_models`，每项只引用已经配置的 Provider 和模型：

```json
"fallback_models": [
  {"provider": "backup", "model_id": "declared-model"}
]
```

未配置时只运行原模型。主模型原有有限重试耗尽后，连接、超时、限流、可切换服务错误
与流式服务错误由官方 ModelFallbackMiddleware 尝试备用实例。每个候选独立按现有策略重试，
SDK 重试仍关闭；工具选择和已完成工具结果不重新执行。

取消、审批中断、配置／认证错误、程序缺陷和关键持久化失败不触发后续请求。工具、图片、
thinking 和 Provider 专有声明不兼容时记录跳过原因；缺失备用凭据不影响主模型成功路径。
实际 SDK、API 模式、输出额度、观察器与逐次用量跟随实际候选；窗口改变时重新计量并按
原摘要服务安全压缩。请求保留相同 logical_id、独立 request_id、candidate_index 和候选内
attempt。失败流式片段保留来源，不写成成功历史或拼接到下一请求。

运行内故障切换不改变显式首选；下一轮仍从首选模型开始。该初版仅用于主 Agent，
后台摘要／记忆、选择器和 Worker 不自动启用该切换。

## 工具错误与可靠结算

工具执行异常由官方 ToolErrorMiddleware 在现有权限与快照边界内部处理；
允许重试的只读临时错误先经过现有有限 ToolRetryMiddleware，写入／Shell／外部动作不扩大
重试范围。可恢复 ToolError 和执行 I/O 异常映射为稳定 error_code、原 call ID 与工具名；
已有错误结果保留，不重复包装。异常文本有界并脱敏，写入失败保留副作用不确定性和快照。

取消、审批中断、程序缺陷、关键同步 Hook、快照与输出／JSONL 保存失败不会当成普通
错误继续运行。只有完整输出、快照绑定与唯一正式 JSONL 结果保存后才发结束事件。
工作区文件后端与 Python Skill 路径检查使用同一个保留组件规则，阻止 `.PYCODE`／`.GIT`
等大小写别名访问内部状态。

## 运行与恢复

JSONL 保存原始完成消息、分支、工具事实、审批和产品 metadata；大输出只有一个完整对象。
模型投影可以采用摘要或省略旧输出，用户界面继续显示原始历史并从对象分段回读。
流式 partial 和事件只保留在当前进程。工具结束事件在正式 JSONL 结果之后发出。

主聊天 checkpoint 是内存对象。同一进程的权限／AskUser／Plan 等待可以回答；关闭、重启
或切换关闭的会话后，旧等待过期，缺失工具结果补 `tool_interrupted`，旧工具不会自动重跑。
非交互 CLI 返回结构化 `needs_input` 和退出码 2；另一个进程提交旧答案会被拒绝。
`resume` 无答案时只显示恢复后的历史。TUI 中的 `/resume` 用于仍存活的等待。

关闭框架自动摘要。pycode 在窗口 80% 触发后台摘要，容量紧张时等待安全采用；来源、
配置、分支和保留边界必须匹配。预算包含实际 native／动态工具 schema 和 harness 系统提示。
只读工具可并发，写入使用锁和屏障；相同参数连续失败三次后拒绝重复执行。

Plan 在 `.pycode/plans/SESSION_ID/PLAN_ID.md` 中维护正文，只有当前计划路径可写。
审批有自动执行、逐项审批、反馈修改三种选择；批准后内容变化必须重新审阅。

## Skills、Hooks、MCP、记忆与团队

Skill 来源按内置、用户、受信任项目 `.agents/skills`、项目 `.pycode/skills` 覆盖。
超预算索引通过 SkillSearch 分层，实际正文和工具披露仍由 native SkillsMiddleware 完成。
GitHub 安装采用 staging、校验与旧版本备份。Python 工具只在选择后验证、执行前逐次审批，
显示脚本／schema 哈希及隔离状态。fork Skill 使用原生 task，支持 full／recent／none。

Hooks 支持 command、prompt 和真实只读子 Agent 动作；同步失败明确报错，后台任务
由会话收拢。MCP 支持 stdio、HTTP、direct／dispatch、资源与提示，共用 MCPAdapter 连接。
Windows／Linux 没有 Seatbelt；macOS Bash 可选 `--sandbox on|on-auto`，
`--sandbox-network` 显式允许网络。macOS 物理隔离仍须在目标平台验收。
沙箱只允许写 workspace（`.git`、`.pycode` 和任意 `.env`／`.env.*` 除外，`.env.example` 可写）；
读权限限于 workspace、系统目录
（`/usr`、`/bin`、`/sbin`、`/System`、`/Library`、`/opt/homebrew`、`/private/etc` 等）、临时目录、
当前 Python 的 prefix／site-packages 与导入路径，以及 git 全局配置文件。`~/.ssh`、`~/.aws`、
`~/.gnupg`、`~/.config`、`~/.pycode` 和任意位置的 `.env`／`.env.*` 不可读，也不能改名或链接后再读
（workspace 内的 `.env.example` 可读）。
注意：HOME 下的其他内容在沙箱内同样不可读，例如 pyenv shims、`~/.local/bin`、`~/.cargo`、
`~/.nvm`、uv／pip 缓存，以及 `.gitconfig` 里 `[include]`／`core.excludesfile` 指向的文件；
依赖这些工具链的构建或测试在 `on`／`on-auto` 下可能失败，此时请用 `--sandbox off`（逐条审批）运行。
`on-auto` 为白名单自动批准：命令每一段都是只读命令（`ls`、`pwd`、`cat`、`head`、`tail`、`wc`、
`rg`、`grep`、不带 `-exec`／`-delete` 的 `find`、`git status|diff|log|show|branch` 等只读子命令、
`echo`、`which`）才免审批；含重定向、`$()`／反引号、`sudo`、任一非白名单片段，或 `find`／`rg`／`git`
参数里有未加引号的通配符／花括号（如 `-de*`）的命令仍需审批。
危险命令黑名单只是纵深防御，始终先于审批生效。

长期记忆使用每作用域一个 `memory.json`，区分语义事实、历史案例和程序规则；默认 auto，
另有 off。官方 LangMem 管理／搜索、后台整理和规则优化共用 Store、真实来源校验与可靠提交，
无需记忆人工审批。无 Provider 的 CLI 可以查看、编辑、移动和删除已存在记忆。

`memory edit ID "正文" --scope user|project` 指定目标作用域；`memory move ID user|project`
只移动，ID、原创建时间和已有来源保留。同 ID 在两侧都存在时，用 `--from-scope` 指定来源。
TUI 使用 `/memory move ID user|project`，也支持 `--from-scope`。移动先保存目标再删除未变化的源，
每个文件独立原子发布，并用持久凭据恢复；两文件之间不宣称原子事务。冲突保留已有内容并报错。
迁入不同作用域的用户偏好／项目知识标为明确作用域纠正；迁入规则由该次用户动作明确采用，
修改案例正文时取消原“已验证”标记。迁移备份与旧格式原件仍用于恢复。

多 Agent 将在后续阶段以进程内方式重新实现。
`worktree inspect ID` 查看保留路径与差异；完成集成后使用
`worktree confirm-integration ID --validated --worker-stopped`。
该命令重新核对目标分支包含关系及干净状态，再删除工作区和对应分支。

## GC 与评测

`storage gc --dry-run` 显示引用标记和决策。无引用对象先隔离 30 天，重新引用可以恢复；
会话删除必须明确操作。所有分支、fork、摘要、Plan、记忆和团队引用都参与保护。
Git GC 只针对独立快照仓库。worktree 清理是独立的 30 天扫描。

固定 Python 评测清单见 `tests/evals/manifest.json`：12 题、固定 3 题 smoke、精确提交和文件哈希。
源码缓存、工作目录、结果目录分别在 `tests/evals/cache|work|results`，不进入发行包。
每题串行执行，Agent 只能修改 solution，测试由独立进程执行，捕获修改、错误、超时及真实 usage。
默认清理工作区，结果保留；评测不创建 commit。可选 Langfuse 只上传摘要指标。
每题具有独立的 baseline／workspace，创建仅用于 tree 快照的临时 Git 仓库，不创建 commit。
结果目录必须新建或为空，避免覆盖旧证据。保存源码清单、依赖和配置摘要、独立 JUnit、
有界测试日志、压缩 JSONL 与 patch，并生成 `results.jsonl` 和 `summary.json`。
无修改、禁止文件变化、未验证最新修改、明确的错误通过声明等分别分类，不能只看退出码。
macOS 的评测命令默认启用 Seatbelt 并关闭网络；Windows 明确记录没有 OS 隔离。
`pip install 'pycode[eval]'` 安装可选的锁定 Langfuse SDK。配置后提交 dataset／experiment、
模型与工具摘要 observation 和确定性 score；不上传代码、提示词、参数正文或大输出。
遥测异常不改变本地成绩，SDK 提交完成也不等于独立确认远程落库成功。

```powershell
pycode --memory-mode off eval --providers providers.json --work tests/evals/work/RUN --results tests/evals/results/RUN
```

固定模型控制 smoke 验证实际协议与测试流水线，不能代表真实模型编码质量。
从新克隆的仓库运行完整测试前，安装开发依赖并准备固定评测缓存。首次准备缓存需要网络；
缓存保持在被忽略的 `tests/evals/cache/` 中，不提交或打入发行包。

```powershell
python -m pip install -r requirements-dev.lock
git clone --filter=blob:none --no-checkout https://github.com/Aider-AI/polyglot-benchmark.git tests/evals/cache/polyglot
git -C tests/evals/cache/polyglot config core.autocrlf true
git -C tests/evals/cache/polyglot sparse-checkout set python
git -C tests/evals/cache/polyglot checkout --detach 7e0611e77b54e2dea774cdc0aa00cf9f7ed6144f
python -m pytest -q
python scripts/check_architecture.py
python scripts/verify_release.py
```

`scripts/` 是开发脚本：`check_architecture.py`（分层依赖与品牌检查）、`verify_release.py`
（lint、格式、架构、构建、包内容、全新 venv 安装与本地最小会话；`--with-evals` 额外安装
`[eval]` 并检查评测入口）、`verify_seatbelt.py`、`verify_live_provider.py`、
`verify_skills_online.py`、`render_tui_demo.py`、`lock_dependencies.py`。
历史验证证据与旧流程脚本在 `archive/`，不维护、不测试、不打包。
审查修复后的验证入口为 `archive/verification/post-review-fixes.json`；
`deepagents-migration.json` 与其他历史报告只证明各自记录时点，保留原始结果。
缓存显式设置 Git 文本换行，使检出文件对应冻结清单的精确哈希。
源码来源、授权和不可变清单见 `docs/source-map.md`。
本次 GitHub 提交与历史接入验证见 `archive/verification/github-submission-20261013/README.md`；
原开发验证报告保持原始结果。

长期记忆的手动编辑使用 `memory --session ID edit MEMORY_ID "新内容"`；
`/memory edit MEMORY_ID 新内容` 在 TUI 中调用相同服务，并保留用户修改的真实来源。
自动提取使用上次成功边界之后的有界来源；手动 `memory extract` 重新检查当前分支。
MCP elicitation 使用原生中断，可接受、拒绝或取消；图片及文件块保留在原结果／大结果对象中。
支持图片的模型接收原生媒体块。审批等待前后完成的其他工具照常结算，不阻塞整个 Agent。

固定评测取自 Exercism Python，完整 MIT 声明见 `docs/licenses/exercism-MIT.txt`。
