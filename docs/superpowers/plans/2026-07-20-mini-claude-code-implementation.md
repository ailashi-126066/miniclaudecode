# MiniClaudeCode 实现计划

- 日期：2026-07-20
- 对应设计：`docs/superpowers/specs/2026-07-20-mini-claude-code-design.md`
- 目标分支：`main`
- 实施方式：按纵向切片执行；每个任务先写失败测试，再写最小实现，最后运行模块和全量验证

## 1. 实施原则

1. 所有生产 Agent 请求必须进入 LangGraph4j 图，禁止另写 while-loop 旁路。
2. LangChain4j 只承担模型、Embedding 和 MCP 基础设施；工具执行、权限、上下文和恢复由本项目控制。
3. 每项功能按 Red → Green → Refactor 执行。没有失败测试，不写对应生产实现。
4. 每个任务结束创建一个聚焦提交；不把多个大功能压进同一提交。
5. 任何模型密钥只用于手工 smoke test，不进入测试资源、日志或提交。
6. 默认测试不联网，Provider、Embedding 和 MCP 都有确定性替身。
7. 每完成一个纵向切片，运行 `mvn verify`，确保主链路始终可运行。

## 2. 依赖基线

首个任务必须通过真实 Maven 解析和编译验证这些版本；若发生 API 或传递依赖冲突，只在同一主版本稳定线内调整，并记录 ADR。

| 组件 | 计划版本 | 说明 |
| --- | --- | --- |
| Java | 21 | 编译使用 `maven.compiler.release=21` |
| Maven Wrapper | 3.9.11 | 与当前开发环境一致，CI 通过 wrapper 构建 |
| LangChain4j BOM | 1.18.0 | 稳定模块使用 1.18.0，BOM 管理的 beta 模块使用配套 beta28 |
| LangGraph4j BOM | 1.8.20 | 只使用稳定 1.8.x，不使用 SNAPSHOT |
| Lucene | 10.5.0 | Java 21+；文本与 KNN 向量使用同一个本地索引 |
| JavaParser | 3.28.2 | Java AST 与 Symbol Solver |
| JLine | 3.30.15 | 使用成熟 3.x API；暂不切换刚发布的 4.x |
| Picocli | 4.7.x 最新补丁 | 仅解析顶层子命令，交互 REPL 仍由 JLine 负责 |
| JUnit | 5.x BOM 最新稳定 | 不在第一版迁移 JUnit 6 |

来源核对：

- LangChain4j 1.18.0 发布页：https://github.com/langchain4j/langchain4j/releases
- LangGraph4j 仓库与 1.8.x 文档：https://github.com/langgraph4j/langgraph4j
- Lucene 10.5.0 文档：https://lucene.apache.org/core/10_5_0/
- JavaParser 仓库：https://github.com/javaparser/javaparser
- JLine 文档：https://jline.org/docs/intro/

根 POM 必须通过 `dependencyManagement` 统一版本，并启用 Enforcer 的 Java/Maven 版本、dependency convergence 和 banned dependencies 检查。如果 LangGraph4j 与 LangChain4j 的传递 Jackson/SLF4J 版本发生冲突，显式统一版本并用启动测试验证序列化，不以排除全部传递依赖的方式掩盖问题。

## 3. 任务清单

### 任务 1：建立 Maven 基线与兼容性门禁

**创建文件**

- `pom.xml`
- `.mvn/wrapper/maven-wrapper.properties`
- `mvnw`
- `mvnw.cmd`
- `.gitignore`
- `.gitattributes`
- `agent-domain/pom.xml`
- `agent-runtime/pom.xml`
- `agent-providers/pom.xml`
- `agent-tools/pom.xml`
- `agent-rag/pom.xml`
- `agent-extensions/pom.xml`
- `agent-persistence/pom.xml`
- `agent-cli/pom.xml`
- `agent-cli/src/test/java/dev/miniclaudecode/architecture/DependencyBaselineTest.java`
- `docs/adr/0001-dependency-baseline.md`

**Red**

1. 先创建八个 module POM 和 `DependencyBaselineTest`。
2. 测试加载 LangGraph4j `StateGraph`、LangChain4j `StreamingChatModel`、Lucene `IndexWriter`、JavaParser `JavaParser` 和 JLine `TerminalBuilder` 类。
3. 运行：

```powershell
.\mvnw.cmd -pl agent-cli -am test -Dtest=DependencyBaselineTest
```

初次应因根 POM、依赖或 wrapper 不完整而失败。

**Green**

1. 配置 Java 21、UTF-8、版本属性和两个 BOM。
2. 配置 Surefire、Failsafe、Compiler、Enforcer、JaCoCo、Spotless 和 SpotBugs。
3. 只把每个第三方依赖放进真正使用它的模块。
4. 生成 Maven Wrapper 3.9.11。
5. 在 ADR 记录最终解析版本、选择 JLine 3.x 的理由及已知兼容性约束。

**Verify**

```powershell
.\mvnw.cmd -q -DskipTests dependency:tree
.\mvnw.cmd verify
```

**提交**

```text
build: establish Java 21 multi-module baseline
```

### 任务 2：定义 Domain、事件与端口

**测试文件**

- `agent-domain/src/test/java/dev/miniclaudecode/domain/event/AgentEventTest.java`
- `agent-domain/src/test/java/dev/miniclaudecode/domain/tool/ToolContractsTest.java`
- `agent-domain/src/test/java/dev/miniclaudecode/domain/session/SessionStateTest.java`

**生产文件**

- `agent-domain/src/main/java/dev/miniclaudecode/domain/event/AgentEvent.java`
- `agent-domain/src/main/java/dev/miniclaudecode/domain/event/AgentEventType.java`
- `agent-domain/src/main/java/dev/miniclaudecode/domain/event/EventSink.java`
- `agent-domain/src/main/java/dev/miniclaudecode/domain/message/AgentMessage.java`
- `agent-domain/src/main/java/dev/miniclaudecode/domain/model/ModelClient.java`
- `agent-domain/src/main/java/dev/miniclaudecode/domain/model/ModelRequest.java`
- `agent-domain/src/main/java/dev/miniclaudecode/domain/model/ModelStreamEvent.java`
- `agent-domain/src/main/java/dev/miniclaudecode/domain/tool/AgentTool.java`
- `agent-domain/src/main/java/dev/miniclaudecode/domain/tool/ToolDescriptor.java`
- `agent-domain/src/main/java/dev/miniclaudecode/domain/tool/ToolCall.java`
- `agent-domain/src/main/java/dev/miniclaudecode/domain/tool/ToolResult.java`
- `agent-domain/src/main/java/dev/miniclaudecode/domain/approval/ApprovalRequest.java`
- `agent-domain/src/main/java/dev/miniclaudecode/domain/approval/ApprovalDecision.java`
- `agent-domain/src/main/java/dev/miniclaudecode/domain/session/AgentStatus.java`
- `agent-domain/src/main/java/dev/miniclaudecode/domain/session/SessionId.java`
- `agent-domain/src/main/java/dev/miniclaudecode/domain/session/TurnId.java`

**Red**

测试以下不变量：事件 ID/时间/会话字段必填；Tool Call 输入是不可变 JSON；审批必须绑定风险、目标和 diff/hash；状态不能从 completed 回到 running。

**Green**

使用 Java 21 record 和 sealed interface 定义稳定契约。Domain 不引用 LangChain4j、LangGraph4j、JLine、Jackson 或 Lucene 类型。

**Verify**

```powershell
.\mvnw.cmd -pl agent-domain test
```

**提交**

```text
feat(domain): define agent events and execution contracts
```

### 任务 3：实现用户目录、配置与密钥脱敏

**测试文件**

- `agent-persistence/src/test/java/dev/miniclaudecode/persistence/config/ConfigLoaderTest.java`
- `agent-persistence/src/test/java/dev/miniclaudecode/persistence/config/SecretRedactorTest.java`
- `agent-persistence/src/test/java/dev/miniclaudecode/persistence/path/UserDataLayoutTest.java`

**生产文件**

- `agent-persistence/src/main/java/dev/miniclaudecode/persistence/config/AppConfig.java`
- `agent-persistence/src/main/java/dev/miniclaudecode/persistence/config/ProviderProfile.java`
- `agent-persistence/src/main/java/dev/miniclaudecode/persistence/config/ConfigLoader.java`
- `agent-persistence/src/main/java/dev/miniclaudecode/persistence/config/SecretRedactor.java`
- `agent-persistence/src/main/java/dev/miniclaudecode/persistence/path/UserDataLayout.java`
- `agent-cli/src/main/resources/default-config.yaml`

**Red**

覆盖用户配置合并、环境变量覆盖、项目配置拒绝明文 `api-key`、Windows/Linux/macOS 用户目录、Key/Authorization header/URL query 脱敏。

**Green**

配置优先级：默认值 < 用户配置 < 项目非敏感配置 < CLI 参数 < 环境变量。`/config set-key` 的隐藏输入留到 CLI 任务实现，但存储接口现在定义。

**Verify**

```powershell
.\mvnw.cmd -pl agent-persistence -am test
```

**提交**

```text
feat(config): load provider profiles and redact secrets
```

### 任务 4：实现 JSONL Event Store

**测试文件**

- `agent-persistence/src/test/java/dev/miniclaudecode/persistence/event/JsonlEventStoreTest.java`
- `agent-persistence/src/test/java/dev/miniclaudecode/persistence/event/JsonlTailRecoveryTest.java`

**生产文件**

- `agent-domain/src/main/java/dev/miniclaudecode/domain/session/SessionEventStore.java`
- `agent-persistence/src/main/java/dev/miniclaudecode/persistence/event/JsonlEventStore.java`
- `agent-persistence/src/main/java/dev/miniclaudecode/persistence/event/EventJsonCodec.java`

**Red**

验证 append 后立即可读、事件顺序稳定、并发 append 串行化、尾部半行被忽略并产生 warning、未知新事件版本可跳过但不可破坏历史。

**Green**

采用每会话单写入锁、UTF-8、一事件一行和 flush 边界。所有事件写入前经过 `SecretRedactor`。

**Verify**

```powershell
.\mvnw.cmd -pl agent-persistence -am test
```

**提交**

```text
feat(persistence): add auditable JSONL session store
```

### 任务 5：建立 Provider 统一流模型与 FakeModelClient

**测试文件**

- `agent-providers/src/test/java/dev/miniclaudecode/providers/FakeModelClientTest.java`
- `agent-providers/src/test/java/dev/miniclaudecode/providers/StreamEventAssemblerTest.java`

**生产文件**

- `agent-providers/src/main/java/dev/miniclaudecode/providers/StreamEventAssembler.java`
- `agent-providers/src/testFixtures/java/dev/miniclaudecode/providers/FakeModelClient.java`

**Red**

脚本化 Fake Model 输出 thinking delta、text delta、并行 tool calls、usage、完成和失败；测试事件顺序、tool arguments 拼接和取消传播。

**Green**

Fake Model 成为后续图场景测试的唯一默认模型。生产端口只暴露项目自己的 `ModelStreamEvent`。

**Verify**

```powershell
.\mvnw.cmd -pl agent-providers -am test
```

**提交**

```text
test(providers): add deterministic streaming model harness
```

### 任务 6：实现最小 LangGraph4j 生产状态图

**测试文件**

- `agent-runtime/src/test/java/dev/miniclaudecode/runtime/AgentGraphFinalAnswerTest.java`
- `agent-runtime/src/test/java/dev/miniclaudecode/runtime/AgentGraphToolRoutingTest.java`
- `agent-runtime/src/test/java/dev/miniclaudecode/runtime/AgentGraphLimitTest.java`

**生产文件**

- `agent-runtime/src/main/java/dev/miniclaudecode/runtime/state/MiniClaudeState.java`
- `agent-runtime/src/main/java/dev/miniclaudecode/runtime/state/StateSchema.java`
- `agent-runtime/src/main/java/dev/miniclaudecode/runtime/AgentGraphFactory.java`
- `agent-runtime/src/main/java/dev/miniclaudecode/runtime/node/PrepareContextNode.java`
- `agent-runtime/src/main/java/dev/miniclaudecode/runtime/node/CallModelNode.java`
- `agent-runtime/src/main/java/dev/miniclaudecode/runtime/node/ExecuteToolsNode.java`
- `agent-runtime/src/main/java/dev/miniclaudecode/runtime/node/FinishNode.java`
- `agent-runtime/src/main/java/dev/miniclaudecode/runtime/route/ResponseRouter.java`
- `agent-runtime/src/main/java/dev/miniclaudecode/runtime/TurnLimits.java`

**Red**

使用 Fake Model 验证 `START -> prepare_context -> call_model -> finish`；Tool Call 路由到 execute；超过模型/工具最大步数进入终止错误。

**Green**

使用 LangGraph4j `StateGraph`、typed state accessor、conditional edges 和 compiled graph。此时 Tool Executor 可以是测试 stub，但所有请求必须确实经过图节点。

**Verify**

```powershell
.\mvnw.cmd -pl agent-runtime -am test
```

**提交**

```text
feat(runtime): add LangGraph4j single-agent execution graph
```

### 任务 7：实现 Anthropic、OpenAI-compatible 与 Ollama Adapter

**测试文件**

- `agent-providers/src/test/java/dev/miniclaudecode/providers/anthropic/AnthropicModelClientTest.java`
- `agent-providers/src/test/java/dev/miniclaudecode/providers/openai/OpenAiCompatibleModelClientTest.java`
- `agent-providers/src/test/java/dev/miniclaudecode/providers/ollama/OllamaModelClientTest.java`
- `agent-providers/src/test/java/dev/miniclaudecode/providers/ProviderFactoryTest.java`

**生产文件**

- `agent-providers/src/main/java/dev/miniclaudecode/providers/ProviderFactory.java`
- `agent-providers/src/main/java/dev/miniclaudecode/providers/anthropic/AnthropicModelClient.java`
- `agent-providers/src/main/java/dev/miniclaudecode/providers/openai/OpenAiCompatibleModelClient.java`
- `agent-providers/src/main/java/dev/miniclaudecode/providers/ollama/OllamaModelClient.java`
- `agent-providers/src/main/java/dev/miniclaudecode/providers/ThinkingSupport.java`

**Red**

MockWebServer/WireMock 覆盖：自定义 base URL、API Key header、SSE 流、Thinking/reasoning 摘要、工具调用、usage、429、无效模型和用户取消。Ollama 验证无 Key 本地配置。

**Green**

使用 LangChain4j 的 Provider builder。保留供应商必要 reasoning metadata；不将隐藏思维链写入事件。Provider 不支持 Thinking 时返回明确 capability。

**Verify**

```powershell
.\mvnw.cmd -pl agent-providers -am test
```

**提交**

```text
feat(providers): support Anthropic OpenAI-compatible and Ollama
```

### 任务 8：实现 Tool Registry 与只读工作区工具

**测试文件**

- `agent-tools/src/test/java/dev/miniclaudecode/tools/registry/ToolRegistryTest.java`
- `agent-tools/src/test/java/dev/miniclaudecode/tools/fs/WorkspacePathResolverTest.java`
- `agent-tools/src/test/java/dev/miniclaudecode/tools/fs/ReadToolTest.java`
- `agent-tools/src/test/java/dev/miniclaudecode/tools/fs/GlobAndGrepToolTest.java`

**生产文件**

- `agent-tools/src/main/java/dev/miniclaudecode/tools/registry/DefaultToolRegistry.java`
- `agent-tools/src/main/java/dev/miniclaudecode/tools/fs/WorkspacePathResolver.java`
- `agent-tools/src/main/java/dev/miniclaudecode/tools/fs/ReadTool.java`
- `agent-tools/src/main/java/dev/miniclaudecode/tools/fs/ListTool.java`
- `agent-tools/src/main/java/dev/miniclaudecode/tools/fs/GlobTool.java`
- `agent-tools/src/main/java/dev/miniclaudecode/tools/fs/GrepTool.java`
- `agent-tools/src/main/java/dev/miniclaudecode/tools/result/ToolResultStore.java`

**Red**

覆盖工具命名空间冲突、工作区内读自动允许、`..`、绝对路径、符号链接逃逸、二进制文件、超大文件截断和行号输出。

**Green**

大结果写入 Tool Result Store，模型上下文只获取稳定摘要与引用。grep 优先使用纯 Java 实现保证跨平台，不要求系统存在 `rg`。

**Verify**

```powershell
.\mvnw.cmd -pl agent-tools -am test
```

**提交**

```text
feat(tools): add safe workspace read and search tools
```

### 任务 9：实现 diff、写工具与权限决策

**测试文件**

- `agent-tools/src/test/java/dev/miniclaudecode/tools/approval/PermissionEngineTest.java`
- `agent-tools/src/test/java/dev/miniclaudecode/tools/fs/EditToolTest.java`
- `agent-tools/src/test/java/dev/miniclaudecode/tools/fs/AtomicFileWriterTest.java`
- `agent-persistence/src/test/java/dev/miniclaudecode/persistence/permission/PermissionRuleStoreTest.java`

**生产文件**

- `agent-tools/src/main/java/dev/miniclaudecode/tools/approval/PermissionEngine.java`
- `agent-tools/src/main/java/dev/miniclaudecode/tools/approval/RiskClassifier.java`
- `agent-tools/src/main/java/dev/miniclaudecode/tools/diff/UnifiedDiffService.java`
- `agent-tools/src/main/java/dev/miniclaudecode/tools/fs/WriteTool.java`
- `agent-tools/src/main/java/dev/miniclaudecode/tools/fs/EditTool.java`
- `agent-tools/src/main/java/dev/miniclaudecode/tools/fs/ApplyPatchTool.java`
- `agent-tools/src/main/java/dev/miniclaudecode/tools/fs/AtomicFileWriter.java`
- `agent-persistence/src/main/java/dev/miniclaudecode/persistence/permission/JsonPermissionRuleStore.java`

**Red**

断言写入前只生成 diff 不改文件；allow once 后修改；源文件 hash 变化会让旧审批失效；拒绝反馈返回模型；永久规则必须限定 workspace/路径/工具。

**Green**

审批请求绑定 `beforeHash + diffHash`。实际写入使用同目录临时文件和原子移动。敏感文件自动升为高风险。

**Verify**

```powershell
.\mvnw.cmd -pl agent-tools,agent-persistence -am test
```

**提交**

```text
feat(security): require diff-bound approval for file changes
```

### 任务 10：实现 LangGraph4j 审批暂停、Checkpoint 与工具账本

**测试文件**

- `agent-runtime/src/test/java/dev/miniclaudecode/runtime/ApprovalResumeGraphTest.java`
- `agent-persistence/src/test/java/dev/miniclaudecode/persistence/checkpoint/FileCheckpointSaverTest.java`
- `agent-persistence/src/test/java/dev/miniclaudecode/persistence/ledger/ToolExecutionLedgerTest.java`

**生产文件**

- `agent-runtime/src/main/java/dev/miniclaudecode/runtime/node/AwaitApprovalNode.java`
- `agent-runtime/src/main/java/dev/miniclaudecode/runtime/AgentThreadRunner.java`
- `agent-persistence/src/main/java/dev/miniclaudecode/persistence/checkpoint/FileCheckpointSaver.java`
- `agent-persistence/src/main/java/dev/miniclaudecode/persistence/ledger/JsonToolExecutionLedger.java`
- `agent-domain/src/main/java/dev/miniclaudecode/domain/tool/ToolExecutionRecord.java`

**Red**

场景：模型请求 edit → 图暂停 → 进程对象销毁 → 从磁盘恢复 → 用户允许 → edit 恰好执行一次。另测已完成调用复用、只读中断重试、未知外部副作用要求用户确认。

**Green**

实现 LangGraph4j `CheckpointSaver` 的文件适配，thread ID 使用 session ID。暂停不能通过阻塞线程模拟。Tool Ledger 在副作用前写 pending，完成后写 completed 和 result reference。

**Verify**

```powershell
.\mvnw.cmd -pl agent-runtime,agent-persistence -am test
```

**提交**

```text
feat(runtime): persist checkpoints and resume approvals safely
```

### 任务 11：实现跨平台命令工具与取消

**测试文件**

- `agent-tools/src/test/java/dev/miniclaudecode/tools/process/ShellSelectorTest.java`
- `agent-tools/src/test/java/dev/miniclaudecode/tools/process/RunCommandToolTest.java`
- `agent-tools/src/test/java/dev/miniclaudecode/tools/process/ProcessCancellationTest.java`

**生产文件**

- `agent-tools/src/main/java/dev/miniclaudecode/tools/process/ShellSelector.java`
- `agent-tools/src/main/java/dev/miniclaudecode/tools/process/ProcessRunner.java`
- `agent-tools/src/main/java/dev/miniclaudecode/tools/process/RunCommandTool.java`
- `agent-domain/src/main/java/dev/miniclaudecode/domain/runtime/CancellationToken.java`

**Red**

覆盖 PowerShell/POSIX 选择、UTF-8、工作目录、超时、stdout/stderr 合并策略、输出上限、Ctrl+C 取消和子进程树清理。危险命令分类进入审批。

**Green**

ProcessBuilder 参数由 Shell Selector 生成；Windows 使用 PowerShell 的稳定编码参数，POSIX 使用 `/bin/sh -lc`。取消先优雅终止，再限时强制终止子进程树。

**Verify**

```powershell
.\mvnw.cmd -pl agent-tools -am test
```

**提交**

```text
feat(tools): execute cancellable cross-platform commands
```

### 任务 12：实现上下文预算、Compact 与错误恢复

**测试文件**

- `agent-runtime/src/test/java/dev/miniclaudecode/runtime/context/ContextPlannerTest.java`
- `agent-runtime/src/test/java/dev/miniclaudecode/runtime/context/CompactContextNodeTest.java`
- `agent-runtime/src/test/java/dev/miniclaudecode/runtime/retry/RetryPolicyTest.java`

**生产文件**

- `agent-runtime/src/main/java/dev/miniclaudecode/runtime/context/ContextPlanner.java`
- `agent-runtime/src/main/java/dev/miniclaudecode/runtime/context/DeterministicContextReducer.java`
- `agent-runtime/src/main/java/dev/miniclaudecode/runtime/node/CompactContextNode.java`
- `agent-runtime/src/main/java/dev/miniclaudecode/runtime/node/RecoverErrorNode.java`
- `agent-runtime/src/main/java/dev/miniclaudecode/runtime/retry/RetryPolicy.java`

**Red**

验证大型旧工具结果替换为引用、最近 tool pair 保留、用户约束/改动文件/失败尝试进入 compact 摘要、context overflow 路由 compact、429/502/503 指数退避且最多三次、auth/config 不重试。

**Green**

先执行确定性清理，再在阈值超限时调用模型生成结构化摘要。退避时钟和随机 jitter 可注入，保证测试确定。

**Verify**

```powershell
.\mvnw.cmd -pl agent-runtime -am test
```

**提交**

```text
feat(runtime): manage context and bounded error recovery
```

### 任务 13：实现 JLine REPL、斜杠命令与流式渲染

**测试文件**

- `agent-cli/src/test/java/dev/miniclaudecode/cli/SlashCommandParserTest.java`
- `agent-cli/src/test/java/dev/miniclaudecode/cli/AgentCompleterTest.java`
- `agent-cli/src/test/java/dev/miniclaudecode/cli/StreamingRendererTest.java`
- `agent-cli/src/test/java/dev/miniclaudecode/cli/ApprovalMenuTest.java`

**生产文件**

- `agent-cli/src/main/java/dev/miniclaudecode/cli/MiniClaudeCode.java`
- `agent-cli/src/main/java/dev/miniclaudecode/cli/Repl.java`
- `agent-cli/src/main/java/dev/miniclaudecode/cli/SlashCommandParser.java`
- `agent-cli/src/main/java/dev/miniclaudecode/cli/AgentCompleter.java`
- `agent-cli/src/main/java/dev/miniclaudecode/cli/StreamingRenderer.java`
- `agent-cli/src/main/java/dev/miniclaudecode/cli/ApprovalMenu.java`
- `agent-cli/src/main/java/dev/miniclaudecode/cli/commands/*.java`

**Red**

覆盖所有已批准斜杠命令、历史文件、路径/Provider/model completion、Thinking/进度/正文不同样式、审批五种选择、Ctrl+C 取消当前轮、空输入 Ctrl+D 退出。

**Green**

JLine 只在 CLI 线程使用；异步模型事件通过队列交给单一 renderer。Picocli 解析 `miniclaude`、`run`、`index`、`rag` 顶层命令。

**Verify**

```powershell
.\mvnw.cmd -pl agent-cli -am test
```

**提交**

```text
feat(cli): add interactive JLine coding-agent console
```

### 任务 14：实现代码分块与 Lucene 增量索引

**测试文件**

- `agent-rag/src/test/java/dev/miniclaudecode/rag/chunk/JavaAstChunkerTest.java`
- `agent-rag/src/test/java/dev/miniclaudecode/rag/chunk/StructuredTextChunkerTest.java`
- `agent-rag/src/test/java/dev/miniclaudecode/rag/index/LuceneCodeIndexTest.java`
- `agent-rag/src/test/resources/fixtures/java-project/**`

**生产文件**

- `agent-rag/src/main/java/dev/miniclaudecode/rag/chunk/CodeChunk.java`
- `agent-rag/src/main/java/dev/miniclaudecode/rag/chunk/JavaAstChunker.java`
- `agent-rag/src/main/java/dev/miniclaudecode/rag/chunk/StructuredTextChunker.java`
- `agent-rag/src/main/java/dev/miniclaudecode/rag/chunk/FallbackChunker.java`
- `agent-rag/src/main/java/dev/miniclaudecode/rag/index/WorkspaceScanner.java`
- `agent-rag/src/main/java/dev/miniclaudecode/rag/index/FileFingerprintStore.java`
- `agent-rag/src/main/java/dev/miniclaudecode/rag/index/LuceneCodeIndex.java`
- `agent-rag/src/testFixtures/java/dev/miniclaudecode/rag/FakeEmbeddingModel.java`

**Red**

验证 Java class/method/constructor/field 分块、package/type owner、精确行号、解析失败回退、Markdown 标题分块、忽略 target/.git、hash 增量更新和删除同步。

**Green**

Lucene document 同时保存 BM25 字段、metadata、stored snippet 与 `KnnFloatVectorField`。索引更新使用 commit 后快照；失败批次不能破坏上一完整索引。

**Verify**

```powershell
.\mvnw.cmd -pl agent-rag -am test
```

**提交**

```text
feat(rag): index Java symbols and workspace documents incrementally
```

### 任务 15：实现 BM25 + Vector + RRF、Code Search 与评测

**测试文件**

- `agent-rag/src/test/java/dev/miniclaudecode/rag/search/HybridCodeSearcherTest.java`
- `agent-rag/src/test/java/dev/miniclaudecode/rag/search/RrfFusionTest.java`
- `agent-rag/src/test/java/dev/miniclaudecode/rag/eval/RagEvaluatorTest.java`
- `agent-rag/src/test/resources/eval/java-fixture.jsonl`

**生产文件**

- `agent-rag/src/main/java/dev/miniclaudecode/rag/search/Bm25Retriever.java`
- `agent-rag/src/main/java/dev/miniclaudecode/rag/search/VectorRetriever.java`
- `agent-rag/src/main/java/dev/miniclaudecode/rag/search/RrfFusion.java`
- `agent-rag/src/main/java/dev/miniclaudecode/rag/search/HybridCodeSearcher.java`
- `agent-rag/src/main/java/dev/miniclaudecode/rag/search/Reranker.java`
- `agent-rag/src/main/java/dev/miniclaudecode/rag/tool/CodeSearchTool.java`
- `agent-rag/src/main/java/dev/miniclaudecode/rag/eval/RagEvaluator.java`
- `agent-cli/src/main/java/dev/miniclaudecode/cli/commands/IndexCommand.java`
- `agent-cli/src/main/java/dev/miniclaudecode/cli/commands/RagCommand.java`

**Red**

验证两路召回、RRF 公式、路径/符号 boost、去重、top-k/token 预算、explain 内容、Recall@5/10、MRR、P50/P95 和 BM25/vector/hybrid 对比。

**Green**

第一次 code search 懒建索引，后续增量更新。默认不启用 reranker；只保留接口和可选配置点。

**Verify**

```powershell
.\mvnw.cmd -pl agent-rag,agent-cli -am test
.\mvnw.cmd -pl agent-cli -am package -DskipTests
java -jar agent-cli\target\mini-claude-code.jar rag eval agent-rag\src\test\resources\eval\java-fixture.jsonl
```

**提交**

```text
feat(rag): add explainable hybrid code retrieval and evaluation
```

### 任务 16：实现 MCP stdio/Streamable HTTP 扩展

**测试文件**

- `agent-extensions/src/test/java/dev/miniclaudecode/extensions/mcp/McpConfigTest.java`
- `agent-extensions/src/test/java/dev/miniclaudecode/extensions/mcp/McpToolAdapterTest.java`
- `agent-extensions/src/test/java/dev/miniclaudecode/extensions/mcp/McpStdioIntegrationTest.java`
- `agent-extensions/src/test/java/dev/miniclaudecode/extensions/mcp/McpHttpIntegrationTest.java`
- `agent-extensions/src/test/resources/mcp/test-server/**`

**生产文件**

- `agent-extensions/src/main/java/dev/miniclaudecode/extensions/mcp/McpManager.java`
- `agent-extensions/src/main/java/dev/miniclaudecode/extensions/mcp/McpServerConfig.java`
- `agent-extensions/src/main/java/dev/miniclaudecode/extensions/mcp/McpToolAdapter.java`
- `agent-extensions/src/main/java/dev/miniclaudecode/extensions/mcp/McpResourceTools.java`
- `agent-extensions/src/main/java/dev/miniclaudecode/extensions/mcp/McpPromptCatalog.java`

**Red**

覆盖 stdio 启动审批、Streamable HTTP 连接、tool/resource/prompt 发现、命名空间冲突、超时、断连隔离、大结果引用，以及 MCP 工具仍进入本地 Permission Engine。

**Green**

使用 LangChain4j `DefaultMcpClient`、`StdioMcpTransport` 和 `StreamableHttpMcpTransport`。不启用已废弃的 legacy SSE transport。

**Verify**

```powershell
.\mvnw.cmd -pl agent-extensions -am verify
```

**提交**

```text
feat(extensions): support secured MCP clients and capabilities
```

### 任务 17：实现 SKILL.md 发现与按需加载

**测试文件**

- `agent-extensions/src/test/java/dev/miniclaudecode/extensions/skill/SkillScannerTest.java`
- `agent-extensions/src/test/java/dev/miniclaudecode/extensions/skill/SkillCatalogTest.java`
- `agent-extensions/src/test/resources/skills/**`

**生产文件**

- `agent-extensions/src/main/java/dev/miniclaudecode/extensions/skill/SkillDescriptor.java`
- `agent-extensions/src/main/java/dev/miniclaudecode/extensions/skill/SkillScanner.java`
- `agent-extensions/src/main/java/dev/miniclaudecode/extensions/skill/SkillCatalog.java`
- `agent-extensions/src/main/java/dev/miniclaudecode/extensions/skill/LoadSkillTool.java`

**Red**

覆盖用户目录、项目目录、`.claude/skills` 优先级，frontmatter/标题解析，重复名称，目录逃逸，大 Skill 截断，以及系统提示只注入索引、不注入全文。

**Green**

通过 `load_skill` 工具按需加载全文；Skill 只提供指令，不能修改权限或直接执行动作。

**Verify**

```powershell
.\mvnw.cmd -pl agent-extensions -am test
```

**提交**

```text
feat(extensions): discover and load local skills on demand
```

### 任务 18：补齐 Web、Todo、Ask User 与完整图场景

**测试文件**

- `agent-tools/src/test/java/dev/miniclaudecode/tools/web/WebFetchToolTest.java`
- `agent-tools/src/test/java/dev/miniclaudecode/tools/task/TodoToolTest.java`
- `agent-runtime/src/test/java/dev/miniclaudecode/runtime/CompleteCodingAgentScenarioTest.java`

**生产文件**

- `agent-tools/src/main/java/dev/miniclaudecode/tools/web/WebFetchTool.java`
- `agent-tools/src/main/java/dev/miniclaudecode/tools/task/TodoTool.java`
- `agent-tools/src/main/java/dev/miniclaudecode/tools/user/AskUserTool.java`

**Red**

Web 测试覆盖协议、重定向、大小、超时、云元数据和私网审批。完整场景严格执行：code_search → read → edit → approval pause → resume → run tests → final。

完整场景断言：

- 图节点序列正确。
- 审批前文件不变。
- 恢复后 edit 恰好一次。
- JSONL 顺序正确。
- Thinking 与 final 分离。
- 会话状态 completed。

**Green**

把全部工具注册到 composition root，完成主链路。Web Fetch 使用 JDK HttpClient 并执行 SSRF 校验。

**Verify**

```powershell
.\mvnw.cmd -pl agent-runtime,agent-tools,agent-cli -am verify
```

**提交**

```text
feat(agent): complete the secured coding-agent tool loop
```

### 任务 19：发行包、跨平台 E2E、CI 与项目文档

**测试与配置文件**

- `agent-cli/src/test/java/dev/miniclaudecode/cli/NonInteractiveE2ETest.java`
- `agent-cli/src/test/java/dev/miniclaudecode/cli/SessionResumeE2ETest.java`
- `.github/workflows/ci.yml`
- `agent-cli/src/assembly/distribution.xml`
- `scripts/miniclaude.ps1`
- `scripts/miniclaude`

**文档文件**

- `README.md`
- `docs/architecture.md`
- `docs/configuration.md`
- `docs/security.md`
- `docs/rag-evaluation.md`
- `docs/mcp-and-skills.md`
- `examples/config.example.yaml`

**Red**

从打包产物启动 `miniclaude run`，使用 Fake Provider 完成一轮；验证含空格和中文的工作区路径；验证 session resume；在 Windows/Ubuntu/macOS matrix 运行。

**Green**

生成可执行 fat jar 和 zip/tar 发行包，提供 PowerShell/POSIX 启动脚本。README 包含架构、5 分钟启动、Provider 配置、明文 Key 风险、RAG 指标示例和演示脚本。

**Verify**

```powershell
.\mvnw.cmd spotless:check
.\mvnw.cmd spotbugs:check
.\mvnw.cmd verify
java -jar agent-cli\target\mini-claude-code.jar --version
java -jar agent-cli\target\mini-claude-code.jar --help
```

**提交**

```text
docs: package and document MiniClaudeCode v1
```

## 4. 每次提交后的统一检查

每个任务至少运行受影响模块；每完成 2–3 个任务运行：

```powershell
.\mvnw.cmd verify
git status --short
```

提交前确认：

- 没有 API Key、Authorization header 或真实会话数据。
- 没有 `.m2`、Lucene index、session JSONL、checkpoint、tool-result 或 IDE 文件。
- 新生产行为有先失败后通过的测试。
- 工具副作用已进入 Permission Engine 与 Tool Ledger。
- 新 Provider 输出已映射为统一事件。
- 没有绕过 LangGraph4j 的 Agent 主循环。

## 5. 里程碑与演示结果

### 里程碑 A：框架主链（任务 1–7）

可演示：JDK 21 多模块工程，LangGraph4j 图驱动 Fake Model 和三个真实 Provider Adapter，支持流式文本、Thinking 和 Tool Call 路由。

### 里程碑 B：安全 Coding Agent（任务 8–13）

可演示：在 JLine 中读取项目、生成 diff、暂停审批、恢复后只执行一次、运行跨平台命令、持久化会话和 compact。

### 里程碑 C：高含金量 RAG（任务 14–15）

可演示：Java AST 增量索引、BM25 + Vector + RRF、可解释结果和 Recall/MRR/延迟评测。

### 里程碑 D：扩展与发行（任务 16–19）

可演示：真实 MCP stdio/Streamable HTTP、按需 Skill、完整工具链、三平台 CI 和可分发 CLI。

## 6. 最终完成条件

只有以下命令与场景全部通过，才可以声称 v1 完成：

```powershell
.\mvnw.cmd clean verify
java -jar agent-cli\target\mini-claude-code.jar --help
java -jar agent-cli\target\mini-claude-code.jar rag eval agent-rag\src\test\resources\eval\java-fixture.jsonl
```

另需保存三项人工验收证据：

1. 一次真实 Provider 的流式 Tool Call + Thinking smoke test，日志已脱敏。
2. 一次 CLI 重启后从审批 checkpoint 恢复的录屏或终端记录。
3. 一份三路 RAG 对比报告，能够解释 hybrid 的收益和失败案例。

这些证据将直接支撑项目 README、简历描述和面试讲解。
