# MiniClaudeCode

MiniClaudeCode 是一个 Java 21 实现的单 Agent 终端编程助手。它用 LangGraph4j 驱动可中断、可恢复的 Agent 状态图，用 LangChain4j 统一接入 Anthropic、OpenAI-compatible 与 Ollama，并把代码检索、安全审批、MCP、Skills 和会话审计做成一条完整主链路。

项目定位不是 Claude Code 的界面仿制品，而是适合 Java Agent 实习/面试展示的工程项目：核心无 Spring、显式构造器装配、多模块 Maven、真实工具调用和跨进程一致性。

## 亮点

- LangGraph4j 状态图：上下文准备、模型调用、工具路由、审批暂停、checkpoint 恢复、重试和完成节点。
- LangChain4j Provider：Anthropic、OpenAI-compatible（OpenAI、DeepSeek、通义兼容网关等）和 Ollama；支持流式文本与 thinking 摘要。
- 安全 Coding Tools：读、列目录、Glob、Grep、Write/Edit/Apply Patch、跨平台命令、Web Fetch、Todo、Ask User；文件变更先展示 unified diff。
- 可审计恢复：JSONL 会话事件、LangGraph4j 文件 checkpoint、工具执行账本；区分“等待审批”和“不确定副作用”。
- 有含金量但可讲清的 RAG：JavaParser AST 分块（TYPE chunk 为结构骨架）、Lucene BM25、可插拔嵌入（默认离线哈希，可切换 OpenAI-compatible 远程模型）、RRF 融合、size+mtime 增量指纹、explain 与评测指标。
- 扩展能力：真实 MCP stdio/Streamable HTTP Client，以及用户、`.claude/skills`、项目级 `SKILL.md` 的按需加载。
- JLine CLI：历史、补全、流式输出、Ctrl+C 取消、审批菜单和会话命令。

## 架构

```text
JLine / Picocli CLI (composition root)
        │
        ├── LangGraph4j Agent Runtime ── JSONL / Checkpoint / Tool Ledger
        │          │
        │          ├── LangChain4j Provider adapters
        │          └── Unified Tool Registry
        │                    ├── secure local coding tools
        │                    ├── Lucene hybrid code_search
        │                    ├── MCP tools/resources
        │                    └── SKILL.md loader
        └── index / rag diagnostics
```

依赖方向和状态图详见 [docs/architecture.md](docs/architecture.md)。

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

交互命令包括 `/status`、`/usage`、`/provider`、`/model`、`/thinking on|off`、`/tools`、`/compact`、`/sessions`、`/resume`、`/mcp`、`/skills`、`/config`、`/config setup` 和 `/exit`。`/usage` 展示会话 Token、Provider Prompt Cache 读写量与命中率；`/config setup` 使用掩码输入 Key，原子更新用户配置，下一次启动生效。`index` 与 `rag` 是顶层诊断命令，不会模仿成 Claude Code 的斜杠命令。

## 安全模型

- 工作区内读取自动允许；路径逃逸和符号链接逃逸被拒绝。
- Write/Edit/Patch 先生成 diff，再把文件哈希和 diff 哈希绑定到审批决定；文件变化后旧审批失效。
- 危险命令、私网 Web URL、MCP 调用需要审批；云元数据端点硬阻断。
- API Key 可以明文写在用户配置中以方便使用，但风险更高；项目配置禁止明文 Key。JSONL 会对已知 Key 和敏感字段脱敏。

完整威胁边界见 [docs/security.md](docs/security.md)。

## 构建与验证

```powershell
.\mvnw.cmd spotless:check
.\mvnw.cmd verify
java -jar agent-cli\target\mini-claude-code.jar --version
```

`package` 同时生成 fat JAR、zip 和 tar.gz 发行包。GitHub Actions 在 Windows、Ubuntu 和 macOS 的 JDK 21 上执行验证。

## 面试讲解主线

1. 为什么用 LangGraph4j 明确表示审批中断，而不是 `while` 循环里阻塞等待。
2. 为什么副作用账本需要区分 `PENDING`、`AWAITING_APPROVAL`、`UNKNOWN` 和 `COMPLETED`。
3. 为什么 Code RAG 是模型按需调用的工具，而不是每轮强制检索节点。
4. BM25 擅长精确符号，向量路由补充标识符变体，RRF 避免直接比较异构分数。
5. 为什么 Skill 只提供指令，MCP 只提供能力，两者都不能提升本地权限。

RAG 设计与实验方法见 [docs/rag-evaluation.md](docs/rag-evaluation.md)，MCP/Skills 配置见 [docs/mcp-and-skills.md](docs/mcp-and-skills.md)。
