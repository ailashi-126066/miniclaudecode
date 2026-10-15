# 状态组交叉审查记录

## 此前 v1：旧稿源码精确性审查

以下保留此前对旧稿的审查。它确认的是符号与业务边界，不代表已满足本轮“可以不展开代码顺读”的要求。

审查对象：miniclaudecode 当前源码 `1aafcaaedfac8a1d43d39486c3617b5be6cde3d6`，以及
`docs/interview/content/12-session.md.in`、`13-memory.md.in`、`14-plans.md.in`、
`15-team.md.in`、`16-ui.md.in`、`chains-state.json`、`inline-state.json`。

结论：**通过；发现的四项精确性问题已交作者修正并复读确认，无未决阻断。**

仅只读核对源码与文稿，不修改产品、不运行模型、不执行行为测试。正文修改由作者 stage_review 完成。
独立AST核对：22条链、177个节点（含5个inline定义），所有 file/name 均能定位真实Python定义。

| 章 | 已逐条核对的关键路径 | 结果 |
|---|---|---|
| 12 Session | 新任务/重复input/分支/run_start；当前图resume；input_queued/响应source leaf；重启补未知结果；branch/revert/compact并列入口；关闭所有权 | 通过，已修关闭顺序 |
| 13 Memory | AGENTS MemoryMiddleware、LangMem主动工具、召回结构化选择、Store Manager自动整理；completed run_end和adopt两触发；过滤、文件租约、before/after、CAS undo与部分成功 | 通过，已修off范围与AGENTS读取时机 |
| 14 Plans | enter及精确/plans/路径；内容review/hash/operation ID；原生task+interrupt；auto/review的门组合；finish/initialize批准失效；纯PlanProjection | 通过，auto表措辞另给非阻断精确化建议 |
| 15 Team | spawn claim/starting/owned opener；消息pending→delivering→input_queued→ACK；wait正式ToolResult ACK；FIFO wake；review/预览/逐次审批/merge重验与取消后结算；重启不自动运行 | 通过，已修不存在的方法名 |
| 16 UI | UIModule注入InteractiveSessions；Textual on_mount/message pump；_operation和_team_tasks自有asyncio；审批身份/子回答；inspect原生历史；output只读分页；stop/shutdown所有权 | 通过，确认不使用Textual Worker |

## 已修并确认的问题

1. **Memory off的范围。** 原文“禁用长期笔记操作”过宽。源码中select/settled/模型memory tools禁用，
   但list/edit/remove/undo/clear保留。作者已改为禁自动召回/整理及模型manage/search，保留用户管理。
   依据：`memory/services/memory.py:MemoryService.tool_operation/select/settled/edit/undo`、
   `memory/adapters/memory_tools.py:memory_tools`。
2. **AGENTS读取时机。** 原文容易误读为每次模型请求重读磁盘。作者已区分每run加载到私有图状态，
   每请求追加system提示；与JSON长期记忆模式独立。
   依据：`agent/adapters/agent.py:AgentRuntime.build` 的MemoryMiddleware装配及其注释，
   `context/module.py:ContextModule.instructions` 的信任/allowlist。
3. **Team调用归属。** 原文写成不存在的`HistoryMiddleware.flush_inbox`。作者已改为
   `HistoryMiddleware.abefore_model.deliver_steering → TeamMember.flush_inbox → TeamInbox.flush`。
   依据：`agent/adapters/history.py`、`team/services/team.py`、`team/services/team_inbox.py`。
4. **Session关闭顺序。** 原文笼统Team→Launcher会遮住shutdown只读delegate窗口。已改为
   `memory → stop_team → launcher(shutdown Hook) → close_team → view → store`。
   依据：`session/services/agent_session.py:AgentSession.aclose` 与
   `session/services/ownership.py:SessionCleanup.close/collect`。

## 核对过的数值和状态默认值

- Memory：全召回阈值20、模型筛选最多10、分批100、每scope最多200、新条目正文2000字符且完整value也限长。
- LangMem：Store Manager query_limit=20/max_steps=1；facade输入上限min(16000,预算)、max_calls=24；
  后台输出上限min(4096,原设置)，thinking为空映射转SDK disabled、reasoning_effort=None、runner重试0。
  一次manager流程不等于一个实际付费请求。
- 整理来源：摘要8000字符、最近对话40条/24000字符，并复用retained_context_entries保留压缩前tail。
- Memory.undo：所有scope租约下全批预检after；逐scope原子发布，完整undoes与部分attempted_undo区分，无跨文件事务承诺。
- Team：活跃starting/running/needs_approval均占1–4上限；每run发送最多30、每收件人普通pending/delivering最多20。
  新显式消息进入wake_eligible；restart不为旧pending自动创建wake资格。
- Plan：32位UUID hex路径；默认planning；auto只对read/write/command返回放行；review继续父gate；
  外层worker/team硬门仍在，重启使旧auto/review回planning。
- UI：自有asyncio任务而非Textual Worker；consume只post_message；完成显示依赖inspect，partial不是完成事实。

## 非阻断建议，已发作者

- Plan auto表可直接说明“Plan门允许时跳过普通parent审批，外层scope/worker/team不被跳过”，
  避免“在原gate范围内”被误解为仍逐项走父审批。
- 如继续展开off语义，可补用户显式`memory_extract`仍是在线手工调用；目前准确列出的五项离线管理不受off禁止。

## 审查边界

以上是文稿对当前源码的交叉审查，不代替运行期验收、生产容量或远程服务保证。
根代理另行负责生成器、所有链接/符号构建、离线展示与浏览交互验证。


## 本轮 v2：00导读与01组装主线的顺读复审

审查人：`stage_review`。审查对象为当前的`content/00-overview.md.in`和`content/01-application.md.in`，重点顺读01前四节，同时检查第五节关闭说明与模块表。只读核对文稿和源码；本次仅追加本审查记录，不修改这些章节，不执行产品、模型或付费调用。

**结论：主线可在不展开代码的情况下读懂，未发现仍缺失的主要调用环节或业务错误。** 读者能沿“用户给出修复要求，但还缺目录与模型设置 → 应用选择模块 → 打开并整理会话 → 保存用户输入与本轮开始 → 创建运行对象并返回 → launcher真正调用运行方法 → 建图与启动 → 工具结果交回模型 → 保存结束并显示”的顺序继续阅读。函数名跟在具体动作之后，用来定位实现，而不再要求先理解一组类名才能猜下一步。

### 前次指出的四个断点如何闭合

1. **为什么示例只有`-p`，后面却调用`execute_run`。** 01第一步现在解释`main`将该选项的文字转入`args.prompt`并选择`run`；第四步再说明按命令选择处理器。来源与去向已经对应。源码依据：`app/cli/main.py:main`、`app/cli/commands.py:command/execute_run`。
2. **创建对象之后，究竟谁让模型开始执行。** 01第四步现在明确`build_runtime`创建`AgentRuntime`并随`RuntimeContainer`返回，此刻还未建图；launcher再调用`container.runtime.run`，运行时依次进入`build`、`invoke`及`graph.ainvoke`。读者不必展开函数也能区分“准备对象”与“启动执行”。源码依据：`app/composition/runtime.py:build_runtime`、`app/lifecycle/session.py:ConfiguredRuntimeLauncher.execute`、`agent/adapters/agent.py:AgentRuntime.run/build/invoke`。
3. **AGENTS说明与JSON笔记为何不是同一模块的一件事。** 01模块表把`MemoryModule`收窄为已保存笔记，表后接着解释`ContextModule.instructions`选择允许的路径、`AgentRuntime.build`安装框架`MemoryMiddleware`。00也沿用这一分工，未再把同名Memory概念合并。源码依据：`context/module.py:ContextModule.instructions`、`agent/adapters/agent.py:AgentRuntime.build`、`memory/module.py`。
4. **主助手完成是否自动通知所有子助手。** 00第五节已明确先保存结果并展示；团队消息另外经过发送与接收，不因主任务完成而自动广播。与团队的显式消息保存、交付和确认规则一致。源码依据：`team/services/team_inbox.py`及`team/services/team.py`。

### 段内时间顺序建议：已修正并复读确认

复审时曾发现01第一步先解释`-p`转换、再讲参数解析，可能让读者把解释顺序当成执行顺序。作者已按源码顺序改为先`parser.parse_args(argv)`，再建立配置，随后执行`args.command, args.prompt = "run", args.p`；本审查已复读确认。该项已关闭，当前没有未决修改建议。

### 本结论的范围

此结论针对能否顺读以及文稿是否与真实调用相符，不声称每个读者无需背景就能理解全部框架细节，也不替代生成器源码引用检查、浏览器交互验证或产品行为测试。00作为路线图可读，01把路线落实到创建、调用和返回；后续各功能章仍需按同一标准解释实际处理过程，不能仅靠表格或展开源码补齐正文。
