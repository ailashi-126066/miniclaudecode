# MiniClaudeCode

## 新增：从 Query Loop 到可进化 Agent

项目现已补齐一组面向复杂 Coding 任务的闭环能力：

- `skills:route_skill` 对 Skill 元数据先召回、再按名称/标签/触发词/示例重排，正文仍由
  `skills:load_skill` 按需加载。
- 成功任务会自动蒸馏为路径经验、错误修复、会话结果或用户偏好，并由
  `memory:search` 跨会话复用。会话原文保持 JSONL；长期记忆存入工作区隔离的 SQLite
  FTS5 数据库，候选记忆必须人工批准后才参与检索，不使用 embedding。
- 有副作用的任务遵循独立 Plan 状态机：`DISCOVER → CREATE_PLAN → SELECT_STEP →
  EXECUTE_STEP → VERIFY_STEP`，失败时有界地 `RETRY / REPLAN / BLOCKED`。Plan 是任务状态的
  唯一真源，工具执行还会校验 `planId`、`stepId` 和 `ToolEffect`。
- 大型代码检索和工具结果使用 `sha256:` 内容地址外置，`context:read_result` 支持按
  offset 分页取回，避免把完整结果反复塞回 Prompt。
- `agent:delegate` 可并发运行 1–4 个探索/审查/规划子 Agent。子 Agent 只有只读工具，
  主 Agent 始终保留规划、写入、审批和最终质量控制权。
- 所有工具结果经过提示注入风险扫描；可疑内容会附加风险级别和命中信号，并被明确
  标记为“不可信数据”。

设计取舍、源码落点和与需求图片的逐项映射见
[能力增强设计说明](docs/image-capability-upgrade.md)。

MiniClaudeCode 是一个 Java 21 实现的中心化多 Agent 终端编程助手。它用 LangGraph4j 驱动可中断、可恢复的主 Agent 状态图，用 LangChain4j 统一接入 Anthropic、OpenAI-compatible 与 Ollama，并把只读子 Agent、代码检索、安全审批、跨会话记忆、MCP、Skills 和会话审计做成一条完整主链路。

项目定位不是 Claude Code 的界面仿制品，而是适合 Java Agent 实习/面试展示的工程项目：核心无 Spring、显式构造器装配、多模块 Maven、真实工具调用和跨进程一致性。

## 亮点

- 可插拔 Agent 流水线：模型 API、Prompt、Context、Provider、Tools 和输出协议均有独立
  模块或目录，不使用 `BaseAgent`/子类 Agent 继承树。
- LangGraph4j 状态图：上下文准备、模型调用、工具路由、审批暂停、checkpoint 恢复、重试和完成节点。
- 有界输出兜底：可按 Provider 选择自然语言或 JSON 终止协议；格式不稳定时反馈模型
  修复，达到上限后明确失败。
- LangChain4j Provider：Anthropic、OpenAI-compatible（OpenAI、DeepSeek、通义兼容网关等）和 Ollama；支持流式文本与 thinking 摘要。
- 安全 Coding Tools：命令 denylist/allowlist、风险审批和 OS 沙箱分层；文件变更先展示 unified diff。
- 可审计恢复：JSONL 会话事件、LangGraph4j 文件 checkpoint、工具执行账本；区分“等待审批”和“不确定副作用”。
- 有含金量但可讲清的 RAG：JavaParser AST 分块（TYPE chunk 为结构骨架）、Lucene BM25、OpenAI-compatible Embedding API（无本地模型依赖）、RRF 融合、size+mtime 增量指纹、explain 与评测指标；未配置 API 时可用 `fast` 哈希嵌入作为开发兜底。
- 扩展能力：真实 MCP stdio/Streamable HTTP Client，以及用户、`.claude/skills`、项目级 `SKILL.md` 的按需加载。
- JLine CLI：历史、补全、流式输出、Ctrl+C 取消、审批菜单和会话命令。

## 架构

```text
JLine / Picocli CLI (composition root)
        │
        ├── Prompt Pipeline ── Context Pipeline
        │          │
        │          └── Model API ── ServiceLoader Provider plugins
        │
        ├── LangGraph4j Query Loop ── Output Protocol / Repair / Termination
        │          │
        │          └── Unified Tool Registry
        │                    ├── secure local coding tools
        │                    ├── Lucene hybrid code_search
        │                    ├── read-only delegated agents
        │                    ├── memory / externalized result retrieval
        │                    ├── MCP tools/resources
        │                    └── SKILL.md router + loader
        └── JSONL sessions / SQLite FTS5 memory / Plan events / Checkpoint / Tool Ledger
```

依赖方向和状态图详见 [docs/architecture.md](docs/architecture.md)，本次模块化改造和扩展方法
见 [Agent 架构重构说明](docs/modular-agent-architecture.md)。想逐文件读懂代码，从分章教程
[docs/tutorial/00-index.md](docs/tutorial/00-index.md) 开始。

## 5 分钟启动

要求 JDK 21。仓库自带 Maven Wrapper。

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

默认配置使用本地 Ollama `qwen2.5-coder:7b`。Provider、`base-url` 和 API Key 配置见 [docs/configuration.md](docs/configuration.md)。

## 使用

```text
miniclaude config                    交互式配置 Provider、Base URL、模型和 API Key
miniclaude [--workspace PATH]       启动交互 CLI
miniclaude run -w PATH "修复测试"   运行单次非交互任务
miniclaude index -w PATH            建立或增量更新代码索引
miniclaude rag -w PATH stats        查看索引统计
miniclaude rag -w PATH explain Q    展示 BM25、Vector 和 RRF 解释
miniclaude rag -w PATH eval FILE    计算 Recall@5/10、MRR、P50/P95
```

`miniclaude run` 的退出码适合脚本调用：`0` 表示成功，`2` 表示 Agent 或配置失败，
`3` 表示需要交互审批，`130` 表示任务被取消。

交互命令包括 `/status`、`/usage`、`/provider`、`/model`、`/thinking on|off`、`/tools`、`/compact`、`/sessions`、`/resume`、`/mcp`、`/skills`、`/config`、`/config setup` 和 `/exit`。`/usage` 展示会话 Token、Provider Prompt Cache 读写量与命中率；`/config setup` 使用掩码输入 Key，原子更新用户配置，下一次启动生效。`index` 与 `rag` 是顶层诊断命令，不会模仿成 Claude Code 的斜杠命令。

## 安全模型

- 工作区内读取自动允许；路径逃逸和符号链接逃逸被拒绝。
- Write/Edit/Patch 先生成 diff，再把文件哈希和 diff 哈希绑定到审批决定；文件变化后旧审批失效。
- 危险命令、私网 Web URL、MCP 调用需要审批；云元数据端点硬阻断。
- Shell denylist 永远优先于 allowlist 和审批；可开启严格 allowlist。项目配置不能覆盖
  `security`，避免仓库自行弱化全局策略。
- API Key 可以明文写在用户配置中以方便使用，但风险更高；项目配置禁止明文 Key。JSONL 会对已知 Key 和敏感字段脱敏。

完整威胁边界见 [docs/security.md](docs/security.md)。

## 构建与验证

```powershell
.\mvnw.cmd spotless:check
.\mvnw.cmd verify
java -jar agent-cli\target\mini-claude-code.jar --version
```

`package` 同时生成 fat JAR、zip 和 tar.gz 发行包。GitHub Actions 在 Windows、Ubuntu 和 macOS 的 JDK 21 上执行验证。
JaCoCo 对每个模块强制执行最低 50% 行覆盖率；每周安全工作流额外执行
`verify -Psecurity-scan` 和 OWASP Dependency-Check。

## 面试讲解主线

1. 为什么用 LangGraph4j 明确表示审批中断，而不是 `while` 循环里阻塞等待。
2. 为什么副作用账本需要区分 `PENDING`、`AWAITING_APPROVAL`、`UNKNOWN` 和 `COMPLETED`。
3. 为什么 Code RAG 是模型按需调用的工具，而不是每轮强制检索节点。
4. BM25 擅长精确符号，向量路由补充标识符变体，RRF 避免直接比较异构分数。
5. 为什么 Skill 只提供指令，MCP 只提供能力，两者都不能提升本地权限。

RAG 设计与实验方法见 [docs/rag-evaluation.md](docs/rag-evaluation.md)，MCP/Skills 配置见 [docs/mcp-and-skills.md](docs/mcp-and-skills.md)。
