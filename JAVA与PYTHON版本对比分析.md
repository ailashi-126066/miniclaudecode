# MewCode Java 与 Python 版本对比分析

分析日期：2026-08-23（RAG 状态已于同日更新）  
分析范围：

- Java：`C:\Users\21125\Desktop\java\ProjectForJava\miniclaudecode`
- Python：`C:\Users\21125\Desktop\java\ProjectForJava\miniclaudecode-python`

## 结论摘要

两个仓库实现的是同一类产品：带工具调用、权限控制、MCP、记忆、技能、工作树及多 Agent 协作能力的终端编码 Agent。它们不是简单的逐文件翻译，而是共享产品目标、在语言生态和部分功能深度上各自演进的两套实现。

Java 版的突出点是更完整的本地 RAG/知识检索实现、Java 21 虚拟线程模型、可打包的 Maven 发布物，以及更保守的远程服务默认安全设置。Python 版的突出点是基于 Textual 的异步交互界面、按功能拆分更细的命令/工具模块、Python 原生 SDK 集成，以及已迁入的私有知识库检索能力。

最需要优先处理的差距是：Python 远程模式默认暴露在 `0.0.0.0:18888`，当前未发现认证和绑定地址配置；Java 版默认绑定 `127.0.0.1`，并支持令牌认证。若 Python 远程模式会在真实网络环境使用，应先补齐该安全边界。

## 分析方式与规模

本报告基于源代码、构建描述和测试目录进行静态分析；未执行 LLM 调用、远程服务或完整测试套件。

- Java 主代码：191 个 `.java` 文件，约 25,142 行；测试：27 个文件。
- Python 主代码：140 个 `.py` 文件，约 18,781 行；测试：29 个文件。
- 行数受语言语法、注释密度与拆分风格影响，不能据此直接判断实现质量或功能成熟度。
- Java 根目录是 Git 工作区，最新提交为 `2026-08-22` 的知识命令/RAG 相关功能；Python 目录当前未识别为 Git 工作区，因此无法在该目录中比较提交历史。

## 共同能力

两版均已具备以下核心能力，整体产品骨架高度一致：

- 多 Provider 流式对话与工具调用：均支持 Anthropic、OpenAI 和 OpenAI 兼容协议，并处理流式文本、思考内容、工具调用和用量信息。
- Agent 循环：都包含上下文管理、工具执行、权限确认、失败恢复、上下文压缩与记忆召回。
- 本地工具：读写文件、编辑、搜索、Glob、命令执行、工具搜索、计划模式、询问用户等。
- 可扩展性：都具备 MCP 客户端/工具注册、Hook、Skill 加载与安装、用户命令。
- 工程协作：都支持 worktree、子 Agent、团队创建/销毁、任务管理、消息通信和进度反馈。
- 交互入口：都有终端交互模式、`-p` 非交互模式、`stream-json` 输出，以及浏览器远程控制模式。

## 主要差异

### 1. 技术栈与交付方式

Java 版要求 Java 21，使用 Maven 构建。`pom.xml` 配置了编译、JUnit 测试、Shade 可执行胖包和 assembly 分发包；最终入口为 `com.mewcode.MewCode`。依赖包含 JLine、Mordant、Javalin、LangChain4j、Lucene、Tree-sitter、PDFBox 和 Apache POI。

Python 版要求 Python 3.11+，使用 `pyproject.toml`、Hatchling 和 `uv.lock` 管理依赖，命令入口是 `mewcode = "mewcode.__main__:main"`。主要依赖为 Textual、Anthropic/OpenAI SDK、Pydantic、MCP、httpx 和 websockets。

影响：Java 更适合交付为单一可执行运行时，并借助 JVM 获得跨平台部署一致性；Python 更适合快速迭代、直接使用 Python 生态的模型 SDK 和异步网络库。两者的配置、部署脚本和依赖锁定格式不能直接复用。

### 2. 并发与 Agent 执行模型

Java 版以 Java 21 虚拟线程、`BlockingQueue` 和 `CompletableFuture` 为核心：`Agent`、模型流式响应和知识召回通过虚拟线程与事件队列衔接。其同步外观下包含轻量线程并发。

Python 版以 `asyncio` 为核心：`Agent.run()` 是异步迭代器，界面、MCP、远程服务、后台任务、并行工具执行和子 Agent 均使用 `asyncio.create_task`、`Queue`、`gather` 等机制。

影响：两者都能并发处理流式事件与工具调用，但二次开发时不能按相同的阻塞模型移植代码。Java 扩展通常需明确线程与队列边界；Python 扩展应避免在事件循环中执行阻塞操作，并正确取消后台任务。

### 3. 终端 UI 与命令组织

Java 版在 `com.mewcode.tui.tea` 中实现了自己的 Bubble Tea 风格 `Program`/`Model`/`Message` 事件循环，并用 `MewCodeModel` 集中维护聊天、权限、计划、回退、MCP、Skill、记忆和任务状态。

Python 版使用 Textual 的 `App`、`TextArea`、`Markdown`、`OptionList` 等组件。除 `mewcode/app.py` 外，命令处理还拆分在 `mewcode/commands/handlers/`：如 `session`、`memory`、`mcp`、`sandbox`、`trace`、`tasks`、`worktree`、`review` 等。

影响：Java 版减少了外部 UI 框架依赖、渲染控制更直接；Python 版能够复用成熟 TUI 组件，命令模块的可定位性更好，但依赖 Textual 生命周期与异步语义。

### 4. RAG/知识检索能力

这是两版当前仍存在功能深度差异的主要部分。

Java 版拥有完整的 `com.mewcode.rag` 模块，包含：

- `RagService`：工作区索引、统计、查询解释与评测入口。
- Lucene 索引、BM25 与向量检索、RRF/重排的混合检索链路。
- JavaParser、Tree-sitter、文档分块、PDF/Office/HTML 文本提取。
- 本地/远程嵌入模型、文件指纹增量索引、代码搜索工具和知识搜索工具。
- CLI：`index`、`rag stats`、`rag explain`、`rag eval`，以及知识命令和预取召回。

Python 版现已新增 `mewcode/rag/` 的私有知识库主链路，行为与 Java `KnowledgeRagService` 对齐：

- 知识来源固定为 `.mewcode/knowledge/`，不会扫描项目源码；索引和目录摘要保存到 `.mewcode/knowledge-index/`。
- 支持 Markdown/TXT/JSON/YAML/HTML/CSV/TSV，以及 DOCX/XLSX 文档；若环境安装了 `pypdf`，也会提取 PDF。
- 使用 `langchain-text-splitters` 按 Markdown 标题和递归文本规则形成知识片段；使用 `rank-bm25`、远程 OpenAI 兼容 Embedding API、本地余弦相似度和 RRF 进行混合检索。
- 提供 `/knowledge index`、`/knowledge status` 和 `/knowledge <问题>`；配置 `knowledge.enabled` 与独立 Embedding endpoint 后才注册 `KnowledgeSearch`。Agent 会在已有索引时注入资料目录摘要，提示模型在相关场景调用该工具。

Python 版没有代码索引、`CodeSearch`、`mewcode index` 或 `/rag` 命令。Java 版仍额外拥有 Lucene、远程 Embedding、更多文档提取器、检索评测与自动知识预取等增强能力。

影响：Python 已具备轻量私有知识库混合检索闭环，适合存放规格说明、业务规则、接口文档等资料；Embedding 只在建索引和查询时调用独立的远程 API，检索与 RRF 全部在本地运行。它与代码搜索保持隔离，不会因为工作区源码量大而污染知识库结果。

Python 启用知识库时需要在 `.mewcode/config.yaml` 配置独立的 Embedding 服务：

```yaml
knowledge:
  enabled: true
  embedding:
    base_url: https://your-openai-compatible-endpoint/v1
    api_key: ${EMBEDDING_API_KEY}
    model: your-embedding-model
```

资料放入 `.mewcode/knowledge/` 后，运行 `/knowledge index`；`/knowledge <问题>` 或 `KnowledgeSearch` 会在本地执行 BM25、余弦相似度和 RRF 融合。

### 5. 远程模式与安全边界

两版均提供 HTTP 页面与 WebSocket 事件桥接，但默认暴露范围不同：

- Java：`RemoteConfig` 默认 `bindAddress = "127.0.0.1"`；可设置 `authToken`。`RemoteServer` 会校验 `Authorization: Bearer ...` 或查询参数 token，不匹配时返回 401；当绑定到非本机地址时会输出暴露风险警告。
- Python：`RemoteServer` 默认地址为 `0.0.0.0`、端口为 `18888`；CLI `--remote` 直接使用该默认值。检查 `mewcode/remote.py` 和 `mewcode/config.py` 未发现远程认证、令牌校验或绑定地址配置实现。

影响：在共享网络、云主机、端口转发或容器发布环境中，Python 远程模式可能被非预期客户端连接，并触发 Agent、工具和文件操作。即使保留开发便利性，也应至少将默认绑定改为 `127.0.0.1`，并提供显式的外网绑定选项与强制令牌认证。

### 6. 沙箱实现

两版均有两层防护思路：路径/权限规则检查，以及 macOS Seatbelt、Linux Bubblewrap 的操作系统级命令包装。

Python 版的 `PathSandbox` 对项目根目录和临时目录设置允许范围，并默认拒绝写入 `.mewcode/config.yaml`、本地权限配置与技能目录。Java 版也有平台沙箱工厂，并额外声明了 `WindowsSandbox`。

但 Java 的 `WindowsSandbox` 是明确的占位实现，`isAvailable()` 恒为 `false`；Python 的 OS 级 `create_sandbox()` 在 Windows 上直接返回 `None`。因此，在当前 Windows 开发环境中，两版均不能提供真正的 OS 级命令隔离，只能依赖权限规则、路径校验和用户确认。

### 7. 工具、任务与团队协作的组织方式

Java 版把部分同类能力集中到单一文件或类中，例如 `TaskTools` 与 `TeamTools` 内部包含多个 Tool 实现；团队后端包含 tmux、iTerm 与文件邮箱实现。

Python 版更细粒度地一工具一文件，例如 `task_create.py`、`task_stop.py`、`team_create.py`、`team_delete.py`、`send_message.py`，并额外有 `synthetic_output.py`、`agents/task_manager.py` 和 `teams/spawn_inprocess.py` 等模块。

影响：Java 的同域代码集中，阅读调用链更短；Python 的职责分离利于局部替换、单元测试和按需导入。两者能力范围高度重叠，但 Python 的任务停止和进程内 teammate 路径更显式，Java 则保留了更多与其 TUI/线程模型结合的状态管理。

### 8. 模型客户端抽象

Java 使用 LangChain4j 作为 Provider 抽象，同时保留本地 Agent loop；其依赖还列出了 Anthropic、OpenAI 与 Ollama 实现。

Python 直接在 `mewcode/client.py` 封装官方 Anthropic SDK 与 OpenAI SDK，区分 Responses API 和 OpenAI Chat Completions 兼容模式，并使用 `httpx` 进行部分模型上下文窗口解析。

影响：Java 依赖 LangChain4j 的适配层与版本演进；Python 对 API 事件格式的控制更贴近官方 SDK。若要增加新 Provider，Java 更适合沿 LangChain4j 扩展点接入，Python 更适合在客户端事件转换层实现。

## 核心模块映射

- Java `com.mewcode.MewCode` ↔ Python `mewcode.__main__`：CLI 与启动分流。
- Java `agent/Agent` ↔ Python `agent.py`：Agent 主循环、工具执行、恢复与压缩。
- Java `llm/*` ↔ Python `client.py`：模型协议适配与流事件统一。
- Java `tui/*` ↔ Python `app.py`、`driver.py`、各类 dialog：终端交互。
- Java `tool/*` ↔ Python `tools/*`：文件/命令/交互等工具注册与实现。
- Java `mcp/*` ↔ Python `mcp/*`：MCP 连接、工具包装与管理。
- Java `memory/*`、`session/*`、`compact/*` ↔ Python `memory/*`、`context/*`、`conversation_pairing.py`：记忆、会话与上下文维护。
- Java `teams/*`、`subagent/*` ↔ Python `teams/*`、`agents/*`：团队协作与子 Agent。
- Java `rag/KnowledgeRagService`、`rag/tool/KnowledgeSearchTool`、`KnowledgeCommands` ↔ Python `rag/knowledge.py`、`tools/knowledge_search.py`、`commands/handlers/knowledge.py`：均围绕工作区私有知识库、目录摘要、混合检索和 Agent 工具构建；Java 仍额外包含 Lucene、更多文档解析与评测。

## 建议的后续优先级

1. 优先加固 Python 远程模式：默认绑定本机；新增配置项；外网绑定时要求 token；对 HTTP 与 WebSocket 同时校验认证；补充认证失败和本机/外网绑定测试。
2. 为 Python 的私有知识库补充使用说明和性能边界：说明 `.mewcode/knowledge/` 的资料放置方式、`/knowledge index` 的重建时机，以及 PDF 依赖 `pypdf` 的可选性。
3. 建立跨实现的行为契约：统一配置字段、`-p` 输出事件格式、工具 JSON Schema、权限模式及远程协议，再用共享用例验证两版表现。
4. 按 Windows 使用场景补足命令隔离：目前两版均没有可用的 Windows OS 级沙箱。可评估 WSL + Bubblewrap、Windows AppContainer 或单独的受限执行服务。
5. 补齐 Python 仓库的 Git 元数据或确认其发布方式，方便追踪与 Java 版之间的功能同步、回归和版本差异。

## 关键证据文件

- Java 构建与入口：`pom.xml`、`src/main/java/com/mewcode/MewCode.java`。
- Python 构建与入口：`pyproject.toml`、`mewcode/__main__.py`。
- Java RAG：`src/main/java/com/mewcode/rag/RagService.java` 及 `src/main/java/com/mewcode/rag/`。
- 远程模式：`src/main/java/com/mewcode/config/RemoteConfig.java`、`src/main/java/com/mewcode/remote/RemoteServer.java`、`mewcode/remote.py`。
- 沙箱：`src/main/java/com/mewcode/sandbox/`、`mewcode/permissions/sandbox.py`、`mewcode/sandbox/`。
- UI 与 Agent：`src/main/java/com/mewcode/tui/MewCodeModel.java`、`src/main/java/com/mewcode/agent/Agent.java`、`mewcode/app.py`、`mewcode/agent.py`。
