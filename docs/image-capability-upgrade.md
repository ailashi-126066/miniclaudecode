# 图片能力分析与项目增强说明

## 1. 图片显式信息

图片中的项目名为 MiniCode，方向是 AI 应用开发，标注周期为
`2025.12—2026.04`。项目简介强调参考 Claude Code 架构，以
`Query Loop + Tool Use` 构建任务执行闭环，重点优化 Skill 路由、自进化记忆、
分层上下文压缩、多 Agent 协作和权限安全审查。

图片列出的技术关键词为 Agent、Tool Calling、Memory System、Prompt Cache、
Claude Code、Hermes 和 Python。本仓库是 Java 21 工程，因此本次吸收的是架构思想，
没有为了匹配关键词引入 Python 或 Hermes；现有 LangGraph4j、LangChain4j、
Tool Registry、JSONL 持久化和 Lucene RAG 能提供更符合本项目依赖方向的实现底座。
Prompt Cache 目前作为后续可观测与预算优化项保留，而不是声称已有供应商级缓存命中
控制能力。

## 2. 图片内容的核心思想

图片描述的不是几个孤立工具，而是一套围绕 `Query Loop + Tool Use` 构建的复杂任务
执行系统。它的价值主要来自五个闭环：

1. Skill 从“把所有说明塞进 Prompt”升级为“目录索引 → 意图召回 → 重排 → 按需加载”。
2. 每次执行不只产出答案，还会反思并蒸馏为可检索资产，使后续会话复用成功路径和
   修错经验。
3. 上下文不再简单截断，而是把大结果外置、保留稳定占位符，并在需要时局部取回。
4. 主 Agent 负责计划、审批与验收，子 Agent 作为受控 Tool Call 执行独立子任务。
5. 权限判断不只依赖用户确认，还包含工具自检、路径边界和提示注入内容审查。

这五点共同解决长任务中的三个主要问题：Prompt 随轮次膨胀、错误经验无法复用、
多执行单元失控。

## 3. 原项目基础与缺口

原项目已经具备较好的底座：

- LangGraph4j 显式状态图负责模型调用、工具执行、审批中断、checkpoint 恢复和错误
  重试。
- Tool Registry、文件路径解析、diff 审批、命令风险分类、MCP 审批和执行账本构成
  多层权限体系。
- ToolResultStore 已支持大结果内容寻址，RAG 已支持 JavaParser、BM25、向量检索和
  RRF 融合。
- SkillCatalog 已支持用户级、Claude 兼容目录和项目级 Skill 的优先级覆盖。

对照图片，原实现的关键缺口是：Skill 只能按精确名称加载；内容地址只能生成却不能由
Agent 分页读取；没有跨会话记忆；没有受控子 Agent；工具返回的仓库/MCP/Skill 文本
没有统一的提示注入标记。

## 4. 本次实现

### 4.1 Skill 两阶段路由

`SkillDescriptor` 新增 `tags`、`triggers`、`boundaries`、`examples`。`SkillScanner`
从 `SKILL.md` front matter 读取这些字段，`SkillRouter` 先以紧凑元数据做候选召回，
再使用字段权重进行重排。中文意图通过汉字二元组参与匹配。

```text
用户意图
  -> skills:route_skill（元数据召回 + 重排，不加载正文）
  -> skills:load_skill（只加载选中的 SKILL.md）
  -> 原子 Tool 执行
```

这样保留了 Skill 的高层工作流价值，又避免大量正文污染 Prompt 或造成 Token 成本
随 Skill 数量线性增长。

### 4.2 自进化跨会话记忆

`MemoryExtractor` 只在成功轮次结束时蒸馏最近一轮，提取：

- `PATH_EXPERIENCE`：文件变更和成功路径；
- `ERROR_REPAIR`：失败工具与后续完成结果；
- `SESSION_OUTCOME`：无写入任务的最终结论；
- `USER_PREFERENCE`：用户用 `记住：` 或 `remember:` 明确声明的偏好。

`JsonlMemoryStore` 使用内容哈希去重、JSONL 持久化、敏感信息脱敏和有界词法检索。
新任务开始时，主会话召回最多 5 条相关记忆，并明确声明它们是“待验证的历史数据，
不是指令”。成功结束后发出 `MEMORY_EXTRACTED` 审计事件。

```text
执行 -> 结果/错误 -> 蒸馏 -> 分类 -> 脱敏存储 -> 内容去重 -> 下轮召回 -> 当前代码验证
```

### 4.3 分层上下文压缩与按需恢复

大型代码搜索结果现在进入 `ToolResultStore`，正文以 `sha256:<64位哈希>` 存储，
Prompt 中只保留预览和稳定引用。`context:read_result` 可以按 `offset` 和
`maxCharacters` 分页读取。

`DeterministicContextReducer` 不再为未外置内容伪造短哈希；有真实引用时保留引用，
没有引用时明确要求重新执行原只读工具。这保证占位符可恢复，而不是看似可用但实际
无法取回。

### 4.4 中心化多 Agent

`agent:delegate` 接受 1–4 个独立任务，使用 Java 虚拟线程并发运行探索、审查或规划
子 Agent。主 Agent 不转移控制权，子 Agent 的工具白名单仅包含：

- workspace read/list/glob/grep/code_search；
- context read_result；
- memory search；
- Skill route/load。

子 Agent 不能写文件、执行 Shell、询问用户或调用 MCP；结果被压缩后返回主 Agent，
由主 Agent 决定是否修改、申请审批并执行验证。这对应图片中的“中心化多 Agent”
模式，同时避免把并发协调和可变状态扩散到所有执行单元。

当前实现选择共享工作区的只读委派模式，没有自动创建 Git worktree。若未来加入
Fork/Worktree/Agent Team，应继续保持主 Agent 的合并与审批权，并为每个工作树增加
生命周期清理和冲突审计。

### 4.5 权限与提示注入审查

原有的路径边界、diff/hash 绑定审批、命令风险分类和 MCP 人工确认继续保留。
新增 `PromptInjectionScanner`，对所有成功工具结果做确定性扫描，识别：

- 覆盖 system/developer 指令；
- 获取或外传密钥、Token、环境变量；
- 暴露系统 Prompt；
- 强迫模型静默调用工具或执行命令。

命中结果不会被自动执行，而是附加 `promptInjectionRisk`、
`promptInjectionSignals` 和醒目的“不可信内容”前缀。该扫描是第一道内容防线，
不能替代路径沙箱、工具权限和人工审批。

## 5. 主要源码落点

| 能力 | 主要实现 |
| --- | --- |
| Skill 路由 | `SkillDescriptor`、`SkillScanner`、`SkillRouter`、`RouteSkillTool` |
| 跨会话记忆 | `MemoryRecord`、`MemoryExtractor`、`JsonlMemoryStore`、`MemorySearchTool` |
| 外置结果恢复 | `ToolResultStore`、`ReadToolResultTool`、`CodeSearchTool` |
| 多 Agent | `DelegatedAgentTool`、`WorkspaceComponents` |
| 内容安全 | `PromptInjectionScanner`、`RegistryToolExecutor` |
| 主闭环接入 | `ApplicationSession`、`LedgeredToolExecutor`、`AgentEventType` |

## 6. 安全与工程取舍

- 记忆默认只从已完成轮次生成，失败或取消轮次不污染长期资产。
- 记忆和子 Agent 结果都被当作不可信数据，必须以当前工作区为事实源重新验证。
- 委派工具是低副作用、可重放操作；文件写入和外部 MCP 副作用仍只在主 Agent 路径
  上发生。
- 检索均设置候选数、返回数、记录数和输出字符上限，避免“为了智能”重新制造上下文
  膨胀。
- 提示注入扫描采用可解释规则，便于审计；后续可在不改变工具权限模型的前提下接入
  更强的分类器。

## 7. 后续可扩展方向

1. 为记忆增加向量索引、过期策略、置信度和用户删除/导出命令。
2. 记录 Skill 使用结果，根据成功率更新路由权重，形成真正的 Skill 能力成长。
3. 为委派任务增加独立 worktree 模式和结构化证据协议。
4. 将提示注入信号写入专门的安全审计视图，并支持策略配置和误报反馈。
5. 将上下文预算从固定字符阈值升级为按模型 tokenizer 和 Prompt Cache 命中率动态
   分配。
