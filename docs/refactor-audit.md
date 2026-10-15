# 五阶段改造验收记录

后续 app 自身分区已补齐，当前布局与离线验收见 `app-boundary-results.json`。
下文保留原五阶段验收快照，真实付费结果仍对应原源码，不作为新布局重新调用API的证明。

五阶段代码和用户要求的模块装配修订已完成，独立设计与代码审查通过。主线离线验收通过；
真实模型验收也已全部通过，并已普通快进推送GitHub main，远程SHA与本地一致。机器可读结果见 `refactor-results.json`。
原开发提交接续 GitHub main 后的对应关系见 `git-history-map.json`。

## 实施要求与证据

| 要求 | 实施与验证结果 |
| --- | --- |
| 产品、评测、开发脚本、归档分离 | `src/pycode` 不依赖 evals/scripts/archive；严格架构、发行内容与安装检查通过 |
| 删除远程团队、HTTP Hook、旧迁移 | 阶段一删除，进程内 Team 替代；源码及命令检查通过 |
| LangMem 简化记忆、可信 AGENTS、召回/主动工具 | JSON BaseStore、原生 middleware、少量全召回/多量模型选择已实现；全量测试通过 |
| 整理、审计、undo、密钥/长度/总数过滤 | 单次后台 Store Manager、CAS/leases、取消与部分成功审计；全量测试通过；真实后台验收通过 |
| 后台 thinking 关闭、失败不重试 | 配置和回归测试通过；真实 DeepSeek 后台整理通过 |
| 进程内 readonly/worker、并发 1–4、任务板 | asyncio Team、工具与身份约束、依赖 DAG/并发 claim；全量测试通过 |
| 收件箱、lead 唤醒、消息限额、审批来源 | JSONL transition、持久化后 ACK、UI/CLI 接线及取消/重启边界；全量测试通过 |
| worker 隔离、review/逐次 merge 审批 | 严格外层 gate、不可变文件树/双端指纹、冲突/超时收拢与清理；Git 与物理沙箱验证通过 |
| fork Skill/Hook 与同步只读委派 | 使用 Team 适配，旧委派类删除；全量测试通过 |
| 单一应用组装口、功能模块独立可读 | `app/bootstrap.py:compose_application` +14 个功能 `module.py`；局部 README、替换/禁用与生命周期测试通过 |
| kernel 分层、无环、纯服务与公开 API | domain/ports/services/adapters 四层；业务算法归还主要所有者；严格架构及352模块导入通过 |
| 私有 composition 与端口实现对照 | 根不导入功能 adapter，子 scope 保留所选模块与凭据源；实际依赖图、端口映射和检查通过 |
| 五个指定复杂函数、三次失败纯策略 | 指定拆分保持 C901≤15；守卫与原三条件等价；检查与行为测试通过 |
| 本地类型检查 | pyright basic 352文件、0 errors/0 warnings；未禁用诊断 |
| README、短设计与历史归档 | 安装/配置/首条命令覆盖三平台；旧实施日志归档，当前模块设计/路径已更新 |
| 环境透传、正则/PATH 回归、测试专用 API | --pass-env 接 Bash/Hook/Python、凭据拒绝；回归通过；死 API 删除/用途说明完成 |
| Python3.12 环境与旧产物清理 | 新根 .venv 按锁安装 dev/eval、pip check 通过；用户确认后397项清理全成功，盘点3337516KiB，保留历史/基线/报告/发行包 |
| Git 原历史 | 原 main@4f61c2d 的47提交已是本地 main 祖先，19个重放提交逐个树等价、作者/作者日期保留 |
| GitHub main 交付 | 已普通快进推送并验证代码/证据提交 `1e9cd36`；原47提交保留，不开PR、不加入CI |

## 主线离线验证

| 门槛 | 最终结果 |
| --- | --- |
| 完整 pytest | 1192 passed、1 skipped，259.42秒；唯一跳过为 Windows/Linux 专用向量 |
| ruff check / format --check / architecture | 全通过；649文件格式 |
| 编译、导入、pyright basic | 维护范围编译通过；352产品模块导入，类型零错误/零警告 |
| macOS Seatbelt | 真实物理隔离通过：外部写入/私有读取/.env重命名拒绝，网络开关和on-auto符合预期 |
| wheel/sdist 与全新 venv 安装 | with-evals 17项全部通过，含doctor、依赖、最小模型会话及help |
| 评测入口和离线 coding smoke | help通过；三题本地模型协议3 passed，17.65秒；未调用云模型 |

首次全量 pytest 为17 failed、1163 passed、1 skipped。修复 Team 工具选择、Skills 重读/recent
上下文、拒绝分类与完整输出、消息 owner 边界及两个夹具；补增量投影安全和只读拒绝验证回归，
独立复审后第二轮全量通过。定向测试有交集，不与全量成绩相加。

初次根目录 compileall 误遍历已忽略的历史故障注入文件而失败；随后明确维护范围，源码、测试、
脚本和评测编译通过。原失败与修正均留存，不将历史测试产物当产品编译失败。

离线测试原提交 `584237f`，历史接续后的等价提交 `1936162`；发行源摘要
`58946e5cecb675654d5bf98ca8e173ae5d562e6c2c985fe40a00e07e3e9500ae`。
原历史接续与文档更新均未改变该源码。完整日志在保留的
`tests/.runtime-tests/module-refactor-final-20261015/` 与 `module-refactor-release-20261015/`。

## 真实验收与交付

用户已授权 DeepSeek-flash、16384 输出额度，两轮各12道完整编码题，及真实服务与后台记忆整理。
编码成绩逐题由独立 pytest 判定：两轮均12/12通过，每轮233项独立测试，无篡改测试；原两轮也均12/12。
原/新四轮的provider配置、题目提示、manifest及16384输出预算完全一致；新版两轮的源码、依赖、
锁和规范化系统提示也一致。官方比较脚本已验证全部轨迹及产物哈希。第一轮319.958秒/129请求，
第二轮357.119秒/150请求，所有279次编码请求均completed、无API错误。两次观测不构成统计可靠性保证。
完整可携带摘要为 `live-acceptance-results.json`，原始轨迹留在本地验收目录。

真实服务独立探测通过（现有脚本4096输出上限，编码评测仍16384）：工具调用与最终回复正常，
两次请求均completed、无错误，共9060tokens。后台真实会话自动触发一次LangMem整理，
主请求thinking enabled/16384，后台thinking disabled/4096；后台runner/SDK重试均0，实际两请求
attempt0/completed、无400，共5767tokens，持久化两条测试长期偏好和session_end整理审计。
初版验收断言误要求tool_choice非空，已保留原报告并按真实自动选择接口纠正；没有改产品或追加付费调用。

清理于2026-10-15按用户确认清单执行：397/397项成功、0失败；按盘点3337516KiB（约3.183GiB）。
新根.venv、evals/cache、原Git材料、两轮16K基线、当前报告与发行包均保留；清理明细见当前证据目录
`cleanup-results.json`。GitHub main已推送并核对 `1e9cd36`，原main祖先验证通过。后续仅追加本次交付验证记录。

## app 自身职责补齐

原先 app 只建立统一入口，自身 CLI、配置、生命周期与框架适配仍混放。现根仅保留 bootstrap
和包说明，cli/configuration/composition/lifecycle 四区由架构检查强制依赖方向。TeamDelegate
归回 team 适配器；运行组装从656行降至385行，资源关闭体实际迁移且不改变顺序。
47个主符号和4个补充符号经独立AST等价对照，类型363文件零错误/警告；全量1244通过、1跳过，
新入口发行与全新安装17项通过。未追加付费API调用，历史真实评测版本保持原样。
