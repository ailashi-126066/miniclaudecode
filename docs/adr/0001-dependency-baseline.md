# ADR-0001：Java 21 与 AI 依赖基线

- 状态：Accepted
- 日期：2026-07-20

## 背景

MiniClaudeCode 同时使用 LangGraph4j、LangChain4j、Lucene、JavaParser 和 JLine。它们更新速度不同，并会通过 HTTP、JSON、Kotlin 等基础库产生传递依赖。项目需要一组可复现、能通过 Java 21 编译和依赖收敛检查的版本。

## 决策

- 编译目标固定为 Java 21，使用 Maven Compiler Plugin 的 `release=21`。
- 使用 Maven Wrapper 3.9.11；开发机可以运行更高版本 JDK，但 CI 直接使用 JDK 21。
- LangChain4j 使用 BOM 1.18.0；MCP 等仍处于 beta 版本线的模块由 BOM 自动选择配套的 beta28。
- LangGraph4j 使用 BOM 1.8.20，生产状态图只依赖稳定的 `langgraph4j-core`。
- Lucene 使用 10.5.0，JavaParser 使用 3.28.2。
- JLine 使用 3.30.15。4.x 在本项目建立基线时刚发布，CLI 暂不承担不必要的主版本迁移风险。
- 所有版本集中在根 POM；模块不得自行写不同版本。
- Maven Enforcer 强制 Java/Maven 版本、dependency convergence、重复依赖版本和危险日志实现检查。

## 兼容性验证

`DependencyBaselineTest` 从最终 CLI classpath 加载以下类型：

- `org.bsc.langgraph4j.StateGraph`
- `dev.langchain4j.model.chat.StreamingChatModel`
- `org.apache.lucene.index.IndexWriter`
- `com.github.javaparser.JavaParser`
- `org.jline.terminal.TerminalBuilder`

测试先在没有框架依赖时以 `ClassNotFoundException` 失败，接入各模块依赖后通过。

首次 convergence 检查发现 LangChain4j MCP 的 OkHttp/Okio 路径同时引入 Kotlin stdlib 1.8.21 与 1.9.10。项目没有关闭检查，而是导入 Kotlin BOM 1.9.10 统一版本。统一后九模块 Reactor 的 convergence 与兼容性测试均通过。

## 后果

- 依赖升级必须先修改根 POM，再运行完整 `mvn verify`。
- LangChain4j 或 LangGraph4j 升级若改变流式、Thinking、MCP 或 checkpoint API，必须新增或调整适配层测试。
- 未来切换 JLine 4.x 需要单独 ADR 和 Windows/Linux/macOS 终端回归测试。
- 不使用 SNAPSHOT，也不为了短期构建通过而禁用 dependency convergence。
