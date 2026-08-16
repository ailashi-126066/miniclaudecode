# MiniClaudeCode

## 新增：从 Query Loop 到可进化 Agent

项目现已补齐一组面向复杂 Coding 任务的闭环能力：

- `skills:route_skill` 对 Skill 元数据先召回、再按名称/标签/触发词/示例重排，正文仍由
  `skills:load_skill` 按需加载。
- 用户以“记住：”/`remember:` 明确声明的偏好，以及带完成证据的验证结论，可写入长期记忆并由
  `memory:search` 跨会话复用；纯模型推断会被写入门拒绝。会话原文保持 JSONL，长期记忆存入工作区
  隔离的 SQLite FTS5 数据库，不使用 embedding。当前没有“待人工审批”的记忆状态，`/memory`
  提供查看、搜索、编辑、归档、导出和清理。
- 有副作用的任务遵循独立 Plan 状态机：`DISCOVER → CREATE_PLAN → SELECT_STEP →
  EXECUTE_STEP → VERIFY_STEP`，失败时有界地 `RETRY / REPLAN / BLOCKED`。Plan 是任务状态的
  唯一真源，工具执行还会校验 `planId`、`stepId` 和 `ToolEffect`。
- 大型代码检索和工具结果使用 `sha256:` 内容地址外置，`context:read_result` 支持按
  offset 分页取回，避免把完整结果反复塞回 Prompt。
- `agent:delegate` 可并发运行探索/审查/规划子 Agent；`agent:background` 提供可持久化的
  fork/isolated 后台任务、取消、等待、结果外置与完成通知；`agent:team` 提供 lead、成员、任务和
  结构化 mailbox。writer 成员在独立 worktree 中运行，主 Agent 保留审批和最终质量控制权。
- 所有工具结果经过提示注入风险扫描；可疑内容会附加风险级别和命中信号，并被明确
  标记为“不可信数据”。

MiniClaudeCode 是一个 Java 23 实现的中心化多 Agent 终端编程助手。它使用显式 `AgentLoop` 驱动可中断、可恢复的模型/工具循环，用 LangChain4j 统一接入 Anthropic、OpenAI-compatible 与 Ollama，并把只读子 Agent、代码检索、审批、跨会话记忆、MCP、Skills 和会话审计做成一条完整主链路。

项目定位不是 Claude Code 的界面仿制品，而是适合 Java Agent 实习/面试展示的工程项目：核心无 Spring、显式构造器装配、多模块 Maven、真实工具调用和跨进程一致性。

## 亮点

- 可插拔 Agent 流水线：模型 API、Prompt、Context、Provider、Tools 和输出协议均有独立
  模块或目录，不使用 `BaseAgent`/子类 Agent 继承树。
- 显式 Agent Loop：上下文准备、流式模型调用、工具路由、审批暂停、checkpoint 恢复、重试、`max_tokens` 续写和完成条件集中在一条直观循环中。
- 有界输出兜底：可按 Provider 选择自然语言或 JSON 终止协议；格式不稳定时反馈模型
  修复，达到上限后明确失败。
- LangChain4j Provider：Anthropic、OpenAI-compatible（OpenAI、DeepSeek、通义兼容网关等）和 Ollama；支持流式文本与 thinking 摘要。
- 安全 Coding Tools：命令 denylist/allowlist、风险审批和 OS 沙箱分层；文件变更先展示 unified diff。
- 可审计恢复：JSONL 会话事件、Agent Loop 文件 checkpoint、工具执行账本；区分“等待审批”和“不确定副作用”。
- 有含金量但可讲清的 RAG：JavaParser AST 分块（TYPE chunk 为结构骨架），Tree-sitter 为
  Python、Go、Rust、JS/TS、C/C++/C#、Ruby 提供真实语法树分块，其余已识别语言降级到模式化声明
  分块；PDF/Office/HTML/CSV 使用结构化抽取，Lucene BM25 使用 CJK 二元组分析器，
  OpenAI-compatible Embedding API（无本地模型依赖）、RRF 融合、size+mtime 增量指纹、explain 与
  评测指标；未配置 API 时可用 `fast` 哈希嵌入作为开发兜底。
- 扩展能力：真实 MCP stdio/Streamable HTTP Client，以及用户、`.claude/skills`、项目级 `SKILL.md` 的按需加载。
- TUI4J 全屏界面：流式文本、thinking、审批、Ctrl+C 取消，以及 session、Plan、usage、后台任务和
  team 状态区。
- 延迟工具 schema：启动时只暴露最小 eager 工具集和 `system:tool_search`；被明确发现的完整 schema
  在下一次模型调用中注入，并随 checkpoint、会话事件和压缩恢复附件恢复。

## 架构

```text
TUI4J / Picocli management commands (composition root)
        │
        ├── Prompt Pipeline ── Context Pipeline
        │          │
        │          └── Model API ── ServiceLoader Provider plugins
        │
        ├── Explicit AgentLoop ── Retry / Approval / Output Repair / Termination
        │          │
        │          └── Eager + Deferred Tool Registry / ToolSearch
        │                    ├── secure local coding tools
        │                    ├── Lucene hybrid code_search
        │                    ├── read-only delegated agents
        │                    ├── memory / externalized result retrieval
        │                    ├── MCP tools/resources
        │                    └── SKILL.md router + loader
        └── JSONL sessions / SQLite FTS5 memory / RecoveryAttachment / Checkpoint / Tool Ledger
```

依赖方向严格单向，`agent-cli` 是唯一 composition root（显式构造器装配，无 Spring）。生产主链路为
`prepare → compact → stream model → execute tools → append results → plan/verify → finish`；
压缩、记忆、预算和追踪由独立服务及事件监听器完成。`MiddlewareChain` 已接在 turn、model 和 tool
边界，但生产装配当前传入空列表，因此它只是扩展点，不应被理解为已有生产中间件策略。

## 5 分钟启动

要求 JDK 23。仓库自带 Maven Wrapper。

```powershell
.\mvnw.cmd clean package
Copy-Item examples\config.example.yaml "$HOME\.mini-claude-code\config.yaml"
java -jar agent-cli\target\mini-claude-code.jar --help
java -jar agent-cli\target\mini-claude-code.jar --workspace F:\your-project
```

Linux/macOS：

```bash
./mvnw clean package
mkdir -p ~/.mini-claude-code
cp examples/config.example.yaml ~/.mini-claude-code/config.yaml
java -jar agent-cli/target/mini-claude-code.jar --workspace /path/to/project
```

默认配置使用本地 Ollama `qwen2.5-coder:7b`。Provider、`base-url` 和 API Key 的完整字段见
[examples/config.example.yaml](examples/config.example.yaml)，或用 `miniclaude config` 交互式生成。

## 使用

```text
miniclaude config                    交互式配置 Provider、Base URL、模型和 API Key
miniclaude [--workspace PATH]       启动 TUI4J 全屏界面
miniclaude index -w PATH            建立或增量更新代码索引
miniclaude rag -w PATH stats        查看索引统计
miniclaude rag -w PATH explain Q    展示 BM25、Vector 和 RRF 解释
miniclaude rag -w PATH eval FILE    计算 Recall@5/10、MRR、P50/P95
```

交互命令包括 `/status`、`/plan`、`/usage`、`/background`、`/team`、`/memory`、`/provider`、
`/model`、`/thinking on|off`、`/tools`、`/compact`、`/sessions`、`/resume`、`/mcp`、`/skills`、
`/config`、`/config setup` 和 `/exit`。`/usage` 展示会话 Token、Provider Prompt Cache 读写量与
命中率；`/config setup` 使用掩码输入 Key，原子更新用户配置，下一次启动生效。`index` 与 `rag`
是顶层诊断命令，不会模仿成 Claude Code 的斜杠命令。

旧行式 REPL 和非交互 Agent `run` 已删除。无参数启动只进入全屏 TUI；stdin/stdout 没有交互式
TTY 时会给出明确错误，不会退回批处理 Agent。`config`、`index`、`rag` 仍可用于管理和诊断。

压缩时，大工具结果先进入 `ToolResultStore`，对话保留内容地址；`RecoveryAttachment` 记录目标、
Plan/证据、已读文件 hash 与范围、修改/验证、Skills、已发现工具、审批、后台/team 摘要、结果引用和
Provider usage，并用 compact boundary id 写入会话事件。后台任务和 team 分别持久化为工作区用户数据
目录下的 `background-agents.json` 与 `teams.json`。

## 安全模型

- 工作区内读取自动允许；路径逃逸和符号链接逃逸被拒绝。
- Write/Edit/Patch 先生成 diff，再把文件哈希和 diff 哈希绑定到审批决定；文件变化后旧审批失效。
- 危险命令、私网 Web URL、MCP 调用需要审批；云元数据端点硬阻断。构建/测试/lint 这类验证
  命令在参数受限的前提下直接执行，避免把审批变成闭眼确认。
- Shell denylist 永远优先于 allowlist 和审批；可开启严格 allowlist。项目配置不能覆盖
  `security`，避免仓库自行弱化全局策略。
- API Key 可以明文写在用户配置中以方便使用，但风险更高；项目配置禁止明文 Key。JSONL 会对已知 Key 和敏感字段脱敏。
- 命令执行后仍进入 OS 沙箱（Linux `bwrap`、macOS `sandbox-exec`，Windows 无可用后端故显式降级，
  由 `MINICLAUDE_SANDBOX` 控制 auto/required/off）。分类、审批、沙箱是三层不同防线，任何一层
  都不是完整边界。
- 工具结果会扫描提示注入信号并标注为不可信数据；只有强信号（指令覆盖、密钥外传、角色冒充）
  会把本轮后续的写入、命令和网络访问升级为强制审批。

## 构建与验证

```powershell
.\mvnw.cmd spotless:check
.\mvnw.cmd verify
java -jar agent-cli\target\mini-claude-code.jar --version
```

`package` 同时生成 fat JAR、zip 和 tar.gz 发行包。GitHub Actions 在 Windows、Ubuntu 和 macOS 的 JDK 23 上执行验证。
JaCoCo 对每个模块强制执行最低 50% 行覆盖率；每周安全工作流额外执行
`verify -Psecurity-scan` 和 OWASP Dependency-Check。

## 面试讲解主线

1. 为什么用显式 `AgentLoop` 把模型、工具、审批暂停和恢复边界集中表达，而不是维护第二套图编排运行时。
2. 为什么副作用账本需要区分 `PENDING`、`AWAITING_APPROVAL`、`UNKNOWN` 和 `COMPLETED`。
3. 为什么 Code RAG 是模型按需调用的工具，而不是每轮强制检索节点。
4. BM25 擅长精确符号，向量路由补充标识符变体，RRF 避免直接比较异构分数。
5. 为什么 Skill 只提供指令，MCP 只提供能力，两者都不能提升本地权限。

RAG 评测方法与基线见 [benchmarks/rag/miniclaudecode-v2/README.md](benchmarks/rag/miniclaudecode-v2/README.md)。
