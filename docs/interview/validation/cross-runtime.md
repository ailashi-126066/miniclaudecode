# docs/interview 交叉源码审查（runtime + evaluation/operations）

> 版本说明：下文原有审计至“最终结论”为 **v1 的源码语义核对**。它曾验证技术表述，却不能证明旧稿容易阅读。符号数、调用链数和完整代码块数仅表示资料齐备，不能代替顺读体验。末尾另记本轮全面改写后的导读审核。

审查人：team_design。对象：当前主仓库 `1aafcaa` 的产品/评测源码和正在编辑的章节模板及链路 JSON。仅只读核对其他组；未运行产品测试、清理命令、发行安装或付费模型。符号存在不作为正确性充分条件，以下均追到函数体、注入位置与返回/关闭路径。

## 审查范围

- `01-application.md.in`、`02-kernel.md.in`、`03-models.md.in`、`04-context.md.in`、`05-agent.md.in`。
- `chains-runtime.json` 与 `inline-runtime.json`（工厂、LCEL、middleware、请求账本、摘要/记忆关系）。
- `17-evaluation.md.in`、`18-operations.md.in`、`chains-root.json`、`inline-root.json`。
- 反馈以 collaboration 消息发给 runtime 作者 memory_finish 或 root；未直接改这些文件。

## 已确认需修正 / 收窄的表述

### R1：catalog 缓存不落秘密，不等于运行时对象不持有凭据（已由作者修正）

原03组装表：`catalog(providers)` 一行写“缓存文件，不在对象里保存密钥”。

实际：`ModelsModule.catalog` 将 `self.credentials(providers)` 交给 `SDKModelDirectory`，后者 `__init__` 明确 `self._credentials = credentials`，`list_models` 运行时取具体 key 构造 SDK 客户端。正确保证是缓存/历史不序列化真实密钥；不能说目录对象不持有运行所需凭据。

作者已确认并改为“缓存不落真实密钥，目录适配器仅运行时持有”。已复读新模板确认。

### R2：SummaryWorkflow 一次逻辑调用可能有多个实际付费 attempt（已由作者修正）

原04核心7写“SummaryWorkflow只有一次计量模型请求”。

实际：`SummaryWorkflow.run` 只有一个逻辑 `_request_step`，但 `ConfiguredSummaryGenerator.generate` 的 owner 使用 Models 默认 `RetryPolicy()`；`ModelRequestRunner._invoke` 的 `ModelRetryMiddleware` 可多次调用 metered_attempt。它与记忆模块强制零重试不同。

作者已确认并改成“一次逻辑摘要调用，允许的重试attempt仍由Models计量”，对应链路说明亦修正。已复读新模板确认。

### R3：Agent 构图的初始声明与请求时最终披露要区分（已修正并回看）

05“唯一图与初始输入”节：“图创建时才把已披露的原生工具转为框架工具”。

实际：`AgentRuntime.build` 转换当时 `context.registry.model_specs()`；MCP 在模型前的 prepare callback 刷新，`HistoryMiddleware.awrap_model_call` 再补 registry 声明；Skills 资源由 SDK lazy resolver 按需披露。最终 selector/calibration 晚于这些步骤，不能把最终披露整体归到 build。

建议：构图转换初始 model_visible 声明；动态声明在模型前准备/资源 resolver 补入；最终披露后才选择和校准。已发 memory_finish，作者已修正文为初始 registry.model_specs、模型前 MCP、按需 Skills resolver 三个阶段；最终已回看。未改其文件。

### R4：评测结果写入失败仍会清理 owned 工作区（已收窄并回看）

17写“结果先保留，再删owned目录”“单题证据写完才进行owned清理”。

`EvaluationRunner.run` 实际是：

```python
try:
    await self.workspaces.save_result(workspace, result)
    results.append(result)
finally:
    if cleanup and workspace is not None:
        await self.workspaces.cleanup(workspace)
```

因此正常路径确实先保存再清理；但 `save_result` 抛错时也进入 cleanup，不能承诺失败时证据已保存。已请 root 区分正常顺序与存储失败边界；本次文档审查不擅自修改评测产品行为。root已在17核心设计、保存节、失败问答与链result明确该边界，最终已回看。

### R5：发行 source_digest 的覆盖清单（已精确化并回看）

18“源码变化就要重新检查”等表述不应理解成整个仓库任意文件都被摘要覆盖。

`scripts/verify_release.py:source_digest` 实际覆盖 `src/**`（排除 pycache/pyc）、`pyproject.toml`、`requirements-dev.lock`、`docs/licenses/*.txt`；不覆盖 `scripts/`、`evals/`、`tests/` 或全部 docs。脚本前后摘要相等证明的是该清单，而不是所有发行 sdist 输入完全不变。

root已在18核心设计、追问及链notes限定该清单并明确不覆盖scripts/evals/tests，最终已回看；未改产品。

### R6：评测“完整JSONL轨迹”不等于保留每个输出对象（已补界限并回看）

`EvaluationWorkspaces.save_result` 明确 `trace_info["output_objects_retained"] = False`。它保存 JSONL gzip、patch、测试日志/JUnit、源码与依赖清单；owned 清理后不保留全部 tool-results 大正文。

17可继续称“完整轨迹”（有长度/hash/complete 校验），但建议说明不是所有 output_id 永久可回读，避免和工具章节运行期完整输出契约混淆。root已在17存储表、失败问答中明确output_objects_retained=False，最终已回看。

## 分章通过项与源码依据

### 01 Application：主要叙述通过

- `ApplicationModules` 14个 feature provider 字段（含可空 Skills/MCP），另有 `history_contributors`；kernel没有伪造 module。
- `default_modules` 的真实默认连接与表格相符：tools backend 给 Plans/Skills，sandbox runner/grant 给 Skills，Models request/stop callbacks 给 Context。
- application/session/run 三个寿命成立：compose 不发模型请求；`open_agent_session`取得 writer/recovery 后转交；Launcher消费注入 runtime_factory。
- `ApplicationModules.scoped` 使用所选实例的 scoped，并以 replace 保留替换类型，不恢复默认；worker的mcp=None与文档相符。
- RuntimeContainer 的 summary/hooks 责任经 Launcher owns_* 转移；RunAssembly 逆序 AsyncExitStack 仅处理组装未交付失败。
- CLI启动链区分门面、命令handler、Session、Launcher及注入工厂，未把赋 partial 当远端执行。

### 02 Kernel：通过

- SessionTree 的 iterable输入验证父先于子；Mapping仅复用存储已校验索引；path拒未知leaf，与文字边界一致。
- freeze/clone/plain_data不是SDK消息转换；深冻结发生在事实边界，frozen dataclass本身不保证嵌套dict。
- EventStream先journal再逐consumer await clone；BrokenPipe转OutputClosed，普通consumer异常禁用并记录，事件不是恢复账本。
- MemoryEventJournal是2048默认有界deque，序号按(session,run)保存；close仅清进程内状态。
- owned_io/shield 等待已拥有线程结束；atomic_write仅单文件发布，文档明确不冒充多文件事务。

### 03 Models：R1修正后通过

- ModelFactory以官方SDK构造，明确SDK重试0；Runner的 started 在 handler 前，settle 在usage/end事件前。
- `_invocation_lock`覆盖整个retry边界，观察状态不会被同owner并发组件相互覆盖。
- RetryMiddleware实际多次调用metered_attempt，退避由Timing按Retry-After等待一次；attempt和candidate_index区分。
- Fallback只在ALLOWED_FAILURES准备候选；实际by_model回调主模型时重抛原错误，不重复发主请求；候选重新calibrate并用自己的Runner。
- Selector可跳过/禁用；其异步owner finally关闭，provider字典tools保留；required names来自模块/root，不硬编码业务名单。
- calibration只遍历request.tools，resource_lookup显式注入，最终selected_tool_names/schema/policy与实际披露相绑定。
- origin purpose不是RequestRecord独立字段：它在usage.raw.response_metadata的pycode_origin_purpose中，UsageService按origin_purpose(record)分组；现正文并未误列独立字段。

### 04 Context：R2修正后通过

- projector默认贡献器顺序 memory→plans→system→MCP→Skills由root选择，host不import功能adapter。
- keys与usage失效ID检查、保守incremental_safe与分支保留符合源码；没有把对话JSONL原地改写。
- ContextMeter有config hash和有效位置的Provider usage锚点，只估tail；无锚点为estimate，未伪造账单。
- InputBudget input_limit/window/cache_trigger关系正确，安全余量为max(64,ceil(window*.005))。
- SummaryService生成固定source，ready对象与adopt事实分开；adopt重验source/config，再模拟压缩后容量，最后追加compaction。
- 记忆回调关系真实：root绑定on_compacted=memory.compacted；adopt成功后通知；Memory.compacted→settled(reason=compaction)自行判模式/忙碌并安排有owner任务。
- SummaryWorkflow无Agent工具循环，先正常stop/no tool验证再StrOutputParser/clean；Context本身未直接执行LangMem。

### 05 Agent：除R3术语精确化外通过

- 实际build列表、HarnessProfile排除默认summary/通用子Agent、filesystem只读安装与MemoryMiddleware可信instructions一致。
- History模型前顺序确为准备器→summary→eviction→steer→投影；模型边界feature filters→readonly/Plan filter→selector→calibration。
- RuntimeBindings显式注入，Agent没有直接MCPService/SkillResources字段；question/native/tool扩展各自拥有业务。
- 完成assistant先save_message，后call_order；Tools只接受拥有它的call IDs。
- ready_results按call_order提交，publisher完成后extension.after_persist→team ACK→executor.completed，与说明相符。
- needs_input保留活图；resume验证真实interrupt/checkpoint；队列继续使用官方jump_to=model，重启不复活旧图。

### 17 Evaluation：判分与正常证据流程通过，R4/R6需限定

- gate仅许可solution名单的写入和固定pytest命令，solver使用产品Application/Session；memory/off、delegation关闭、project trust关闭、connect_mcp=False符合文字。
- baseline要求exit 0/1、存在JUnit且tests非空；未实现模板失败不直接阻止评测。
- solver内部timeout为task.timeout*3，超时边界外读取结算request记录；最终finally close会话。
- verified_at来自最后一个成功Bash且晚于最后编辑；gate将Bash限定到测试调用；独立测试在前后两次unchanged核对之间执行。
- evaluation_outcome真实优先级：tampered→timeout→agent不完成→no_patch→repeated_tool_failure→claim_mismatch→no_verification→passed/tests_failed。
- claims_tests_passed只是带相邻否定排除的文本规则；章节未夸成语义理解模型。
- compare两目录、每题JSON/JSONL、trace长度/hash、system prompt、题目prompt、源码依赖/锁/配置/manifest/预算均有实际校验。
- 正文没有把历史真实12/12强说成当前后续布局已重新付费验收；这个限定通过。

### 18 Operations：GC流程通过，R5需限定摘要清单

- doctor无provider亦可运行，读取WorkspaceInspector/沙箱状态/which，不自动安装环境或启动Agent。
- StorageLayout确为resolve→normcase→sha256前24位，不按末级目录名隔离。
- GC.collect先inventory/mark，protected优先；dry_run不apply；未引用先quarantine，30天保留期后才purge。
- FileStorageObjects.apply持storage lease；snapshot另取snapshot lease；quarantine/purge前重新inventory+mark并检查active/protected，迟到引用不会仅因旧决策删除。
- memory不是普通待回收object：checked排除memory_roots，额外已知工作区的项目memory根也加入排除；不是笼统按mtime删文件。
- GC审计成功/失败均await，失败抛出；未声称后续动作仍全部执行。
- release确建新venv、清PYTHONPATH/PYTHONHOME、安装wheel并跑本地模拟minimal-run；与真实付费验收分别陈述。
- 物理Seatbelt验证是独立macOS向量，未把Windows Job/其他平台说成相同文件隔离。

## 本组反向审查反馈的自修

- 09完整例子顺序已改为prepare_result标protected_content→publisher保存/绑定read_file→after_persist写skill_load。
- 07 on模式表改为命令launch前不可用就拒绝；status仅返回unavailable，未声称module构造或status本身会抛。
- 07环境继承问答补充off不提供磁盘凭据文件读取隔离，避免把环境白名单误解为OS文件边界。

## 结论

runtime的框架回调、14模块/3scope、请求计量/selector/fallback、Context压缩和Memory通知主线与源码相符。已发现并反馈的表述均有具体源码依据；R1–R6均已由各自作者修正/限定，最终模板与链路数据已回看；当前无未决阻断。不对未运行的产品/发行/付费行为新增验收结论。

最终结论：01–05 runtime五章及17/18 root两章，含对应chains/inline，交叉源码审查通过。该结论为文档与当前源码语义一致性，不新增产品测试/性能/发行/付费验收结论。


## V2：全面改成导读后的复读审核

本轮用户要求改的是阅读方式，不是把v1通过记录换个日期。已只读复读root新版17-evaluation、18-operations，并回看03-models、04-context中此前需要收窄的事实；相关函数仍以当前产品/评测源码为依据，未运行测试、清理或付费服务。

### 能否跟着一次任务往下读

17现在从“parser.py修完，怎样知道真的做对”引入，依次解释固定题目、正常产品会话解题、独立测试、保存比较。每步先说明已有材料为何不足，再引入下一函数：例如模型自报不够，所以检查文件并独立pytest；判分还不够，所以保存可重新核对的材料。工厂准备solver与runner以后才实际逐题solve，说明没有把创建对象画成已经请求模型。通过。

18从“任务跑不起来/数据越来越多”切入，先doctor区分工作目录与外部工具，再解释StorageLayout如何分项目、GC为什么先找引用，最后另列交付检查。三项独立脚本没有被写成doctor自动执行的后续步骤；表格放在故事后供查找，未用前置API名词表阻断主线。通过。

代码卡片有上游来源、为什么现在需要它、输入处理返回以及下一步，而不只列出表达式。helpers说明在父流程哪里需要；赋工厂、后台任务、框架回调与后续独立命令继续明确区分。这里的判断来自实际复读段落与代码关系，不以代码覆盖数证明可读性。

### v1指出的事实是否在简化语言时丢失

- 评测保存失败：17第四步仍明确save_result抛错时finally可能清理owned目录，完整证据和summary不一定留下。没有为了故事顺畅又改回“所有情况都先保存成功再删除”。通过。
- 完整轨迹：17仍明确JSONL.gz完整不等于所有大输出对象永久保留，保留output_objects_retained=False。通过。
- 发行摘要：18仍精确列src、pyproject.toml、requirements-dev.lock、docs/licenses，并明确不覆盖任意scripts/evals/tests修改。通过。
- 目录与凭据：03用顺读段落保留“目录适配器和SDK运行内存可持有凭据，缓存和历史不写真实密钥”，没有改回“所有对象都不存密钥”。通过。
- 摘要与重试：04仍区分一次逻辑摘要调用与多次允许attempt；不把记忆模块的零重试政策错误套给摘要。通过。
- 摘要与记忆：04仍在成功追加compaction之后，通过已传入的on_compacted调用Memory.compacted，由记忆模块判断是否安排后台整理；没有写成框架写完摘要就自动完成长期记忆。通过。

### 本轮发现并已关闭的一处函数归属问题

17第四步原段落把日志、JUnit、补丁写入全归给save_result。实际日志/JUnit在workspaces.test、补丁在changes先写；save_result压缩轨迹、整理来源与依赖信息、写逐题JSON及追加results。已反馈root，root按前后承接修正，最终复读确认关闭。未改产品代码。

### 本组06–11自读说明

本组也已沿修parser.py、跑测试、撤回误改、按review Skill检查、借外部MCP服务、执行前后Hook串写。框架与组装在具体步骤后解释，表格只作回查；首次遇到schema、metadata、runnable改为先说明参数格式、消息附加字段、供框架稍后调用的方法。

每张卡片的guide是面向读者的完整自然段；notes用代码表达式证明因果。机械检查确认guide存在、字数和符号可定位，只是发现漏项，不作为阅读质量通过的替代。关键旧边界仍保留：未提供工具不审批执行、结果先保存再通知、只读validate_call不提前调用before_call、错误脱敏、真实沙箱与进程组清理、快照部分恢复、Skill双摘要和recent空集合、MCP不确定效果、Hook先call后result与人工对账。

V2审核结论：本轮已核范围内无未决问题，root17/18导读及旧事实保留通过。它不宣称全书无需读者反馈，更不把本轮文档修改视作新的产品、发行或付费验收。
