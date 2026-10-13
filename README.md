# pycode

本地 Python 编码 Agent。实施规范是父目录的 `pi计划.md`。执行栈固定为
`deepagents==0.7.23`、`langchain[mcp]==1.4.4`、`langgraph==1.2.14`。
主循环由 `create_deep_agent` 执行，文件入口为 FilesystemMiddleware，Skill 加载由
SkillsMiddleware 执行，MCP 连接和协议由 LangChain 内置 MCPAdapter 持有。

## 安装与运行

Python 3.11+；开发验证环境是 Windows／Python 3.12.10。文件搜索需要 PATH 中的 `rg`，
Bash 使用 Git Bash 或系统 Bash。Git tree 快照和修改型 Worker 需要项目已经存在 Git 仓库。
本项目开发目录保持非 Git；相关测试只使用 `tests/` 中的临时仓库。

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

名称是显示 metadata。完整 session_id 仍是唯一身份；重名要求明确选择 ID。
TUI 支持 `/session name 名称`、`/session open ID-or-name`、`/session fork 新ID`、
`/plan`、`/memory`、`/storage status`、`/storage gc --dry-run`、`/team list`、
`/team check ID`、`/team update ID 新指令`、`/team cancel ID` 和 `/skill:NAME 参数`。

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

Hooks 支持 command、prompt、HTTP 和真实只读子 Agent 动作；同步失败明确报错，后台任务
由会话收拢。MCP 支持 stdio、HTTP、direct／dispatch、资源与提示，共用 MCPAdapter 连接。
Windows／Linux 没有 Seatbelt；macOS Bash 可选 `--sandbox on|on-auto`，
`--sandbox-network` 显式允许网络。macOS 物理隔离仍须在目标平台验收。

长期记忆区分用户偏好、纠正反馈、项目知识，使用真实来源证据；默认 auto，另有 review／off。
候选冲突必须审阅。选择是有界快照，更新有事务恢复、版本保护和旧版本备份。
`/memory` 界面可编辑、换作用域、批准或拒绝。无 Provider 的 CLI 可以管理已存在记忆。

`team.json` 位于用户数据目录或受信任项目目录，声明 `name`、`description`、`graph_id`、
`url`、可选 `headers` 和 `modifies_workspace`。本地服务：

```powershell
pycode team --providers providers.json serve --port 2024
```

本地 graph_id 为 `readonly`、`worker` 或显式集成任务的 `integration`。
修改型 Worker 使用独立 worktree，文件写入限定
其中，Bash／Python／外部动作继续保留对应审批。任务 start／check／update／cancel／list
直接使用 Deep Agents Async Subagents；update 中断旧 run，在同一 thread 启动新 run。
pycode 管理依赖、引用与集成检查。默认活动并发上限 4；用户数据目录或受信任项目目录的
`team-settings.json` 可设置 `{"concurrency": 2}`，项目配置覆盖用户配置。
Worker 完成不自动提交／合并；只有确认已集成、验证通过、停止且干净后才移除 worktree。
本地服务退出会收拢 Worker；重启查询真实状态，不自动续跑旧工具。
使用 `team start ROLE "任务" --base REV --include-uncommitted` 显式选择本地修改型 Worker
的起点；默认从 HEAD 创建，不隐式复制未提交文件。选项经过原生 start API 的任务描述
传到本地服务，已有 Worker 的 update 保留原工作区。
`team check ID` 返回当前 Worker 的待审批信息；TUI 将审批转发给 Lead 界面。
CLI 使用 `team answer ID --answers-file answers.json` 提交当前操作的明确回答。
普通 update 或 cancel 会结束旧等待，旧答案失效；Python 工具仍逐次审批。
PLAN 中的 CLI／TUI Team 控制与模型工具共用策略，只允许只读 Worker。
集成任务在目标工作区执行明确指定的审查、测试和合并，修改仍需审批。
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
```

审查修复后的验证入口为 `docs/verification/post-review-fixes.json`；
`deepagents-migration.json` 与其他历史报告只证明各自记录时点，保留原始结果。
缓存显式设置 Git 文本换行，使检出文件对应冻结清单的精确哈希。
源码来源、授权和不可变清单见 `docs/source-map.md`。
本次 GitHub 提交与历史接入验证见 `docs/verification/github-submission-20261013/README.md`；
原开发验证报告保持原始结果。

长期记忆的手动编辑使用 `memory --session ID edit MEMORY_ID "新内容"`；
`/memory edit MEMORY_ID 新内容` 在 TUI 中调用相同服务，并保留用户修改的真实来源。
自动提取使用上次成功边界之后的有界来源；手动 `memory extract` 重新检查当前分支。
MCP elicitation 使用原生中断，可接受、拒绝或取消；图片及文件块保留在原结果／大结果对象中。
支持图片的模型接收原生媒体块。审批等待前后完成的其他工具照常结算，不阻塞整个 Agent。

固定评测取自 Exercism Python，完整 MIT 声明见 `docs/licenses/exercism-MIT.txt`。
