# 五阶段改造验收记录

本表区分代码审查与行为验证。阶段二、三按约定只做静态检查；最终 pytest、沙箱、发行和评测
完成前，不能用这些静态记录代替行为通过。当前仍在实施，未推送 GitHub。

## 实施要求

| 要求 | 当前证据 | 待完成 |
| --- | --- | --- |
| 产品与评测/脚本/归档分离 | 阶段一 `a16c4fc`；evals 独立包、scripts、archive 与发行配置 | 最终发行安装复验 |
| 删除远程团队、HTTP Hook 与旧迁移 | 阶段一删除；阶段三仅进程内 asyncio 团队 | 最终源码/命令扫描 |
| 简化记忆并继续 LangMem | 阶段二 `3f80ef1`，独立实现审查通过 | 统一行为测试 |
| AGENTS.md 信任边界与原生 middleware | S2 信任/大小写测试已编写；用户默认、项目仅 trust | 统一行为测试 |
| 少量全召回、多量模型选择 | S2 全部候选参与选择，有条目/输入上限 | 统一行为测试 |
| 主动记忆与单次后台整理 | LangMem manage/search 和 Store Manager；压缩 retained tail 已补 | 统一行为测试与真实服务 |
| 后台 thinking 关闭、失败不重试 | S2 装配保留，S3 rebase 后核心记忆文件无差异 | 真实 DeepSeek 后台整理 |
| 原子 JSON、密钥/长度/总数过滤、日志与 undo | S2 跨作用域 CAS/leases、取消后日志与部分成功审计已审查 | 统一行为测试 |
| 进程内 readonly/worker、并发配置 1–4 | S3 `f21db32`，设计、实现与 rebase 均经独立审查 | 统一行为测试 |
| spawn/send/wait/cancel/list 与任务板工具 | S3 工具、固定 actor 身份、依赖 DAG、并发 claim | 统一行为测试 |
| 模型前收件箱、空闲 lead 唤醒、消息限额 | S3 JSONL transition、wait 结算后 ACK、取消及重启边界已审查 | 统一行为测试 |
| 子审批统一转发、来源标注 | S3 TUI/CLI 接线及回归用例已写 | 统一 UI/CLI 测试 |
| worker 写隔离及逐次 merge 审批 | 强制外层 gate、严格沙箱、immutable tree + source/target 指纹 | 临时 Git 与物理沙箱测试 |
| 冲突处理、重启中断、取消收拢与成功清理 | S3 Git 适配器多轮审查；超时/迟到资源有记录与测试 | 统一行为测试 |
| 替换旧同步只读委派，保留 fork Skill/Hook 语义 | S3 删除旧类，以团队适配同步委派 | 统一行为测试 |
| 功能目录、公开 API、无环、无框架服务层 | S4 首轮 `d740c16`；用户模块化修订新增统一 bootstrap、14 个 feature module，静态通过 | 修订整体审查/统一行为测试 |
| 拆 composition、集中 gates、端口实现对照、测试迁移 | 首轮迁移后按 module-assembly.md 重构：根无 feature adapter imports，子作用域保留选择，kernel 四层 | 统一行为测试 |
| 五个复杂函数拆分 | S5 已拆；四个文件所有函数 C901≤15，inventory 扫描系列亦≤15 | 搬迁后复核及行为测试 |
| 失败三次守卫迁入纯策略 | S5 repeated_tool_failure 与原三条件等价，独立预审通过 | 搬迁后行为测试 |
| 本地 pyright basic | S5 装配修订后 352 文件零错误、零警告；未关闭诊断 | main 最终复验 |
| CI | 用户已明确取消，未加入 GitHub Actions | 无 |
| 入门 README、短设计、历史实施日志归档 | S5 已编写 macOS/Linux/PowerShell 安装配置和首条命令 | 最终命令/链接与布局复核 |
| .env.example 正则和评测 PATH 回归 | S5 已补测试 | 统一行为测试 |
| 显式环境透传与 Bash environment 说明 | S5 --pass-env 接 Bash/Hook/Python，凭据形态拒绝 | 统一行为测试与最终审查 |
| 仅测试使用 API 清理 | 删除 substitute_arguments；next_turn 仅保留生产队列选择；list_events 说明诊断用途 | 搬迁后引用扫描 |
| Python 3.12 开发环境迁出旧产物目录 | 根目录 .venv，锁文件安装与 editable dev/eval，pip check 已过 | 最终环境/命令复核 |
| tests/.runtime-tests 清理 | 尚未删除；历史准备目录和测试证据保留 | 最终清单/大小、用户确认后清理 |
| 原 Git 历史接续 | origin/main 4f61c2d 的 47 个提交保留；交付分支已接至阶段三 | 接阶段四/五，树和祖先复核 |
| GitHub main 交付 | 用户指定 ailashi-126066/miniclaudecode 并授权推送 main | 所有验收后普通快进推送 |

## 最终统一验证门槛

所有五阶段代码和独立审查完成后，在合并结果上依次执行；修复后按影响重跑必要检查。

| 门槛 | 状态 |
| --- | --- |
| 完整 pytest，PATH 包含 rg | 待运行 |
| ruff check / format --check / architecture | 待最终合并复验 |
| compileall、全产品模块导入、本地 pyright basic | 待最终合并复验 |
| macOS scripts/verify_seatbelt.py | 待运行 |
| scripts/verify_release.py 的 wheel/sdist 与全新环境安装 | 待运行 |
| python -m evals coding --help 与离线 smoke（不调用云模型） | 待运行 |
| DeepSeek 12 题完整编码评测两轮，与原 16384 输出额度 12/12 基线对比 | 待离线验收后执行/授权 |
| scripts/verify_live_provider.py 与一次真实后台记忆整理 | 待离线验收后执行/授权 |
| 最终 GitHub main 推送与远程 SHA/文件树核对 | 待运行 |

历史静态数据位于开发工作目录的 `/private/tmp/pycode-pyright-s5-*.json` 等文件，
不是最终发行证据。最终验收需保存与实际最终提交关联的命令、退出码和必要日志摘要。


模块化修订静态证据：352 产品 Python 文件导入、pyright basic 零错误零警告、649 文件格式、
ruff、严格架构、compileall 已通过；78 个旧测试文件迁至实际入口，1319 导入及3124签名核对通过。
新增根装配/贡献/关闭/模块生命周期用例已编写，仅做导入编译，尚未执行测试函数。
`module-assembly-import-graph.json` 记录实际源码及无环图；旧阶段四快照不作为当前最终证据。

模块化修订正式独立代码审查通过，所有阻断修复并复审。此结论仅为代码/静态检查通过，统一行为测试尚待执行。
