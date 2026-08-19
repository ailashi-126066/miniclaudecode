# Java源码解析：Agent Loop 核心循环

本文深入 MiniCode 项目的 Agent Loop，看看「LLM 交互 → 工具执行 → 错误恢复」这条主线在 Java 21 里是怎么实现的。

---

## 概述

Agent Loop 是整个 AI Agent 系统的"心脏"，负责驱动 LLM 与工具系统的交互。它是一个无限循环，每轮执行以下流程：

```
调用 LLM → 消费流式响应 → 执行工具 → 将结果反馈给 LLM → 继续下一轮
```

直到满足终止条件：LLM 不再调用工具、达到迭代上限、出现致命错误、或用户取消。

---

## 文件位置与调用链

```
项目结构：
src/main/java/com/mewcode/agent/
  ├── Agent.java                  ← Agent Loop 主循环（agentLoop 方法）
  ├── AgentEvent.java            ← 事件类型定义（sealed interface）
  ├── StreamingExecutor.java     ← 工具并发执行器
  └── StreamEvent.java           ← LLM 流式事件

调用链：
MewCode.main()
  ↓
Agent agent = new Agent(client, registry, protocol, queue, ...)
  ↓
agent.agentLoop()  ← 进入无限循环
  ↓
每个 event → putSafe(queue, event)  ← 事件发送到队列
  ↓
TUI 从 queue 消费事件并显示
```

**启动代码**：

```java
// com/mewcode/MewCode.java
public static void main(String[] args) {
    // 1. 创建依赖
    LlmClient client = createLlmClient(config);
    ToolRegistry registry = ToolRegistry.createDefault();
    BlockingQueue<AgentEvent> queue = new LinkedBlockingQueue<>();
    
    // 2. 创建 Agent
    Agent agent = new Agent(
        client, 
        registry, 
        "anthropic",  // 或 "openai"
        queue,
        workDir,
        50,  // maxIterations
        checker,
        hookEngine
    );
    
    // 3. 启动循环
    agent.agentLoop();  // 阻塞直到完成或错误
}
```

---

## 核心数据结构

### AgentEvent：事件类型系统

```java
// com/mewcode/agent/AgentEvent.java
public sealed interface AgentEvent {
    
    // 流式文本片段
    record StreamText(String text) implements AgentEvent {}
    
    // 思维链片段
    record ThinkingText(String text) implements AgentEvent {}
    
    // 工具调用事件
    record ToolUseEvent(
        String toolId, 
        String toolName, 
        Map<String, Object> args
    ) implements AgentEvent {}
    
    // 工具执行结果
    record ToolResultEvent(
        String toolId, 
        String toolName, 
        String output, 
        boolean isError, 
        double elapsed
    ) implements AgentEvent {}
    
    // 权限审批请求（异步通信）
    record PermissionRequestEvent(
        String toolName,
        String description,
        CompletableFuture<PermissionResponse> future
    ) implements AgentEvent {}
    
    // 重试通知
    record RetryEvent(String reason, long waitMs) implements AgentEvent {}
    
    // 轮次完成
    record TurnComplete(int iteration) implements AgentEvent {}
    
    // 循环完成
    record LoopComplete(int iteration) implements AgentEvent {}
    
    // 错误事件
    record ErrorEvent(String message) implements AgentEvent {}
    
    // Token 用量
    record UsageEvent(
        int inputTokens, 
        int outputTokens,
        int cacheReadTokens,
        int cacheCreationTokens
    ) implements AgentEvent {}
}
```

**设计特点**：

- **sealed interface**：Java 17+ 特性，限定只有这些类型可以实现接口，保证封闭性
- **record**：不可变数据类，自动生成构造函数、getter、equals、hashCode、toString
- **CompletableFuture**：用于权限审批的异步通信，Agent 发送请求后阻塞等待 TUI 响应

### Conversation：对话管理

```java
// com/mewcode/agent/Conversation.java
public class Conversation {
    private final List<Message> messages = new ArrayList<>();
    
    // 添加用户消息
    public void addUserMessage(String text) {
        messages.add(new Message("user", List.of(new TextBlock(text))));
    }
    
    // 添加 assistant 消息（包含工具调用）
    public void addAssistantFull(
        String text, 
        List<ThinkingBlock> thinking,
        List<ToolUseBlock> toolUses
    ) {
        var blocks = new ArrayList<ContentBlock>();
        if (!text.isEmpty()) blocks.add(new TextBlock(text));
        blocks.addAll(thinking);
        blocks.addAll(toolUses);
        messages.add(new Message("assistant", blocks));
    }
    
    // 添加工具结果
    public void addToolResultsMessage(List<ToolResultBlock> results) {
        messages.add(new Message("user", new ArrayList<>(results)));
    }
    
    // 构建 API 请求格式
    public List<Map<String, Object>> build() {
        return messages.stream()
            .map(Message::toMap)
            .toList();
    }
}
```

---

## 主循环详解

### 循环骨架

```java
// com/mewcode/agent/Agent.java (第 158-460 行)
public void agentLoop() {
    Conversation conv = new Conversation();
    boolean loopCompleted = false;
    
    try {
        for (int iteration = 1; ; iteration++) {
            // 1. 检查迭代上限
            // 2. 检查线程中断
            // 3. 消费通知队列（团队模式）
            // 4. 注入延迟工具清单
            // 5. 获取工具 schema，调用 LLM
            // 6. 消费流式响应
            // 7. 错误恢复
            // 8. max_tokens 恢复
            // 9. 保存 assistant 消息
            // 10. 没有工具调用 → 结束
            // 11. 执行工具 + 收集结果
            // 12. turn_end 通知
        }
    } finally {
        if (!loopCompleted) {
            putSafe(queue, new AgentEvent.LoopComplete(0));
        }
    }
}
```

这是一个**无限循环 + 多条件退出**的设计。`iteration` 从 1 开始递增，`finally` 块保证无论如何退出都会发送 LoopComplete 事件。

---

### 步骤 1：检查迭代上限

```java
// 第 159-163 行
if (maxIterations > 0 && iteration > maxIterations) {
    putSafe(queue, new AgentEvent.ErrorEvent(
        "Agent reached maximum iterations (%d)".formatted(maxIterations)
    ));
    break;
}
```

默认 `maxIterations = 50`，防止 Agent 失控运行。这是最早的安全检查，在每轮开头执行。

---

### 步骤 2：检查线程中断

```java
// 第 165 行
if (Thread.currentThread().isInterrupted()) break;
```

用户按 Ctrl+C 或 TUI 取消操作时，主线程会收到中断信号。这里立即退出循环。

---

### 步骤 3：消费通知队列

```java
// 第 167-172 行
if (notificationFn != null) {
    for (String note : notificationFn.get()) {
        conv.addSystemReminder(note);
    }
}
```

团队模式下，其他 Agent 或后台任务可以发送通知（如"任务 A 已完成"）。这些通知会被注入为 system reminder，让 LLM 知道最新状态。

---

### 步骤 4：注入延迟工具清单

```java
// 第 188-194 行
var deferredNames = registry.getDeferredToolNames();
if (!deferredNames.isEmpty()) {
    conv.addSystemReminder(
        "Available deferred tools (use ToolSearch to discover): " +
        String.join(", ", deferredNames)
    );
}
```

延迟工具（CodeSearch、MemorySearch）默认不出现在工具列表中。通过 system reminder 告诉 LLM：这些工具存在，但需要先用 ToolSearch 发现才能使用。

---

### 步骤 5：获取工具 schema，调用 LLM

```java
// 第 177-185 行
var iterToolSchemas = registry.getAllSchemas(protocol);

// 工具过滤（计划模式或权限限制）
if (toolNameFilter != null) {
    iterToolSchemas = iterToolSchemas.stream()
        .filter(schema -> {
            Object name = schema.get("name");
            return name == null || toolNameFilter.test(name.toString());
        })
        .toList();
}

// 第 206-210 行
putSafe(queue, new AgentEvent.IterationStart(iteration));

var streamQueue = new LinkedBlockingQueue<StreamEvent>();
client.stream(
    conv.build(),           // 对话历史
    systemPrompt,           // 系统提示词
    iterToolSchemas,        // 工具列表
    streamQueue             // 流式事件队列
);
```

**为什么每轮都重新生成工具列表？**

1. **延迟工具动态加载**：ToolSearch 发现工具后，下一轮列表会包含新工具
2. **计划模式过滤**：进入计划模式时只允许 ReadFile/CreatePlan 等工具
3. **MCP 动态连接**：外部 MCP 工具可能随时加入
4. **Prompt Caching 优化**：相同的工具 schema 命中缓存，实际 token 消耗很低

---

### 步骤 6：消费流式响应

```java
// 第 246-302 行
var text = new StringBuilder();
var thinkingBlocks = new ArrayList<ThinkingBlock>();
var toolCalls = new ArrayList<ToolCallInfo>();
String stopReason = "end_turn";
int turnInput = 0, turnOutput = 0;
boolean streamError = false;

while (true) {
    StreamEvent event;
    try {
        event = streamQueue.poll(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
    }
    
    if (event == null) {
        putSafe(queue, new AgentEvent.ErrorEvent("Stream timeout"));
        return;
    }
    
    switch (event) {
        case StreamEvent.TextDelta td -> {
            text.append(td.text());
            putSafe(queue, new AgentEvent.StreamText(td.text()));
        }
        
        case StreamEvent.ThinkingDelta td -> {
            // 处理思维链片段
            if (currentThinking == null) {
                currentThinking = new StringBuilder();
            }
            currentThinking.append(td.text());
            putSafe(queue, new AgentEvent.ThinkingText(td.text()));
        }
        
        case StreamEvent.ToolCall tc -> {
            toolCalls.add(new ToolCallInfo(
                tc.toolId(), tc.toolName(), tc.args()
            ));
            putSafe(queue, new AgentEvent.ToolUseEvent(
                tc.toolId(), tc.toolName(), tc.args()
            ));
        }
        
        case StreamEvent.StreamEnd se -> {
            stopReason = se.stopReason();
            turnInput = se.inputTokens();
            turnOutput = se.outputTokens();
            putSafe(queue, new AgentEvent.UsageEvent(...));
        }
        
        case StreamEvent.Error err -> {
            lastStreamException = err.exception();
            putSafe(queue, new AgentEvent.ErrorEvent(err.message()));
            streamError = true;
        }
    }
    
    if (event instanceof StreamEvent.StreamEnd || 
        event instanceof StreamEvent.Error) break;
}
```

**流式响应处理流程**：

1. **非阻塞轮询**：`streamQueue.poll(30秒)` 从队列取事件
2. **类型匹配**：用 Java 17 的 pattern matching switch 分发处理
3. **立即转发**：每个事件都通过 `putSafe(queue, ...)` 转发给 TUI
4. **累积数据**：同时在本地累积完整的 text、toolCalls 等数据
5. **结束条件**：收到 StreamEnd 或 Error 事件就退出循环

**为什么需要两个队列？**

- `streamQueue`：LLM 客户端写入，Agent Loop 读取（单向）
- `queue`：Agent Loop 写入，TUI 读取（单向）
- 解耦了 LLM 流 和 UI 事件流，中间可以做过滤、转换、增强

---

### 步骤 7：错误恢复

```java
// 第 306-368 行
if (streamError) {
    var error = lastStreamException;
    
    // 7.1 上下文过长 → 压缩后重试
    if (error instanceof LlmException.ContextTooLongException) {
        if (contextRetries < 3) {
            contextRetries++;
            putSafe(queue, new AgentEvent.RetryEvent(
                "Context too long, compacting...", 0
            ));
            
            // 应用工具结果预算控制
            Path forceSessionDir = Paths.get(workDir, ".mewcode/session");
            List<ContentReplacementRecord> forceRecords =
                ToolResultBudget.apply(conv, forceSessionDir, replacementState);
            
            // 强制压缩对话历史
            conv.forceCompact(
                contextWindow,
                compactedSessionFile,
                iterToolSchemas,
                forceRecords
            );
            
            continue;  // 重新开始本轮
        }
    }
    
    // 7.2 限流错误 → 指数退避后重试
    if (error instanceof LlmException.RateLimitException rle) {
        if (rateLimitRetries < 3) {
            rateLimitRetries++;
            long waitMs = rle.retryAfterMs();
            if (waitMs <= 0) {
                waitMs = (long) (1000 * Math.pow(2, rateLimitRetries));
            }
            putSafe(queue, new AgentEvent.RetryEvent(
                "Rate limit exceeded, retrying in %dms...".formatted(waitMs),
                waitMs
            ));
            Thread.sleep(waitMs);
            continue;  // 重新开始本轮
        }
    }
    
    // 7.3 其他错误 → 退出循环
    break;
}
```

**错误恢复策略**：

1. **上下文过长**：
   - 先应用工具结果预算（大型工具输出替换为摘要）
   - 再压缩对话历史（保留系统消息和最近几轮）
   - 最多重试 3 次
   
2. **限流错误**：
   - 读取 API 返回的 `retry_after` 时间
   - 如果没有，使用指数退避：1s、2s、4s
   - 最多重试 3 次

3. **其他错误**（如认证失败、网络错误）：
   - 直接退出循环，不重试

---

### 步骤 8：max_tokens 恢复

```java
// 第 377-398 行
if ("max_tokens".equals(stopReason)) {
    // 8.1 第一次触发 → 扩大输出限制
    if (!maxTokensEscalated) {
        maxTokensEscalated = true;
        client.setMaxOutputTokens(MAX_TOKENS_CEILING);  // 8192
        conv.addUserMessage(
            "Output token limit hit. Resume directly from where you stopped."
        );
        putSafe(queue, new AgentEvent.RetryEvent("max_tokens escalation", 0));
        continue;
    }
    
    // 8.2 仍然触发 → 要求 LLM 分段输出
    else if (outputRecoveries < MAX_OUTPUT_RECOVERIES) {
        outputRecoveries++;
        conv.addAssistantFull(text.toString(), thinkingBlocks, List.of());
        conv.addUserMessage(
            "Output token limit hit. Resume directly from where you stopped. " +
            "Break remaining work into smaller pieces."
        );
        putSafe(queue, new AgentEvent.RetryEvent(
            "max_tokens recovery %d/%d".formatted(outputRecoveries, MAX_OUTPUT_RECOVERIES),
            0
        ));
        continue;
    }
    
    // 8.3 恢复次数用尽 → 放弃，继续执行
}
```

**max_tokens 恢复机制**：

1. **第一阶段**：扩大输出限制从 4096 到 8192
2. **第二阶段**：要求 LLM 分段输出（最多 3 次）
3. **第三阶段**：放弃恢复，按现有输出继续

这防止了 LLM 输出被截断导致的工具调用不完整。

---

### 步骤 9：保存 assistant 消息

```java
// 第 401-404 行
var toolUseBlocks = toolCalls.stream()
    .map(tc -> new ToolUseBlock(tc.toolId, tc.toolName, tc.args))
    .toList();

conv.addAssistantFull(text.toString(), thinkingBlocks, toolUseBlocks);
```

将本轮 LLM 的完整输出保存到对话历史：文本、思维链、工具调用。

---

### 步骤 10：没有工具调用 → 结束

```java
// 第 420-432 行
if (toolCalls.isEmpty()) {
    if (fileHistory != null) {
        String summary = text.length() > 60 
            ? text.substring(0, 60) + "..." 
            : text.toString();
        fileHistory.makeSnapshot(conv.size(), summary);
    }
    
    putSafe(queue, new AgentEvent.LoopComplete(iteration));
    loopCompleted = true;
    break;  // 正常退出
}
```

这是**最常见的正常退出路径**。LLM 认为任务完成，不再调用工具，直接返回最终答案。

---

### 步骤 11：执行工具 + 收集结果

```java
// 第 436-447 行
var executor = new StreamingExecutor(
    registry, checker, hookEngine, queue, 
    recoveryState, workspace, sessionId
);

var callInfos = toolCalls.stream()
    .map(tc -> new StreamingExecutor.ToolCallInfo(
        tc.toolId, tc.toolName, tc.args
    ))
    .toList();

var results = executor.executeAll(callInfos);

// 添加工具结果到对话
var resultBlocks = results.stream()
    .map(r -> new ToolResultBlock(r.toolId(), r.output(), r.isError()))
    .toList();
conv.addToolResultsMessage(resultBlocks);
```

**工具执行流程**：

1. 创建 StreamingExecutor（每次都新建，保证独立的 runId）
2. 将 toolCalls 转换为 ToolCallInfo 列表
3. 调用 `executeAll()`：
   - 分批（只读并行，写入/命令串行）
   - 权限审批
   - 执行工具
   - 返回结果
4. 将结果封装为 ToolResultBlock 添加到对话

详细执行流程见《工具注册与执行框架》文档。

---

### 步骤 12：turn_end 通知

```java
// 第 459 行
putSafe(queue, new AgentEvent.TurnComplete(iteration));
```

本轮完成，通知 TUI 可以刷新界面、保存状态等。然后进入下一轮循环。

---

## 四个停止条件与退出机制

### 停止条件概览

| 条件 | 触发位置 | 退出方式 | 清理动作 |
|------|---------|---------|---------|
| LLM 不再调用工具 | 步骤 10 | `break` | LoopComplete(iteration) |
| 达到迭代上限 | 步骤 1 | `break` | ErrorEvent + LoopComplete(0) |
| 致命错误 | 步骤 7 | `break` | ErrorEvent + LoopComplete(0) |
| 用户取消 | 步骤 2 / 流消费 | `break` / `return` | LoopComplete(0) |

所有停止条件都通过 `break` 或 `return` 退出 `for` 循环，然后 `finally` 块保证发送 LoopComplete 事件。

---

### 1. LLM 不再调用工具（正常完成）

```java
// com/mewcode/agent/Agent.java (第 420-432 行)
if (toolCalls.isEmpty()) {                    // ← 判断条件
    if (fileHistory != null) {
        String summary = text.length() > 60 
            ? text.substring(0, 60) + "..." 
            : text.toString();
        fileHistory.makeSnapshot(conv.size(), summary);
    }
    
    putSafe(queue, new AgentEvent.LoopComplete(iteration));  // ← 发送完成事件
    loopCompleted = true;                      // ← 标记已完成
    break;                                     // ← 退出 for 循环
}

// 循环结束后的代码
// 第 460 行：for 循环结束
// 第 463 行：finally 块

} finally {
    if (!loopCompleted) {                      // ← 检查标记
        putSafe(queue, new AgentEvent.LoopComplete(0));
    }
}
// agentLoop() 方法返回，调用栈回到 main()
```

**执行流程**：

```
第 N 轮循环
  ↓
LLM 返回：只有文本，没有 tool_use
  ↓
toolCalls.isEmpty() == true
  ↓
发送 LoopComplete(iteration)
  ↓
loopCompleted = true
  ↓
break ← 退出 for 循环
  ↓
finally 块：检查 loopCompleted == true，不发送重复事件
  ↓
agentLoop() 方法返回
  ↓
调用栈回到 main() 或 TUI
  ↓
程序正常结束或等待下一个任务
```

**触发场景**：
```java
// 用户："帮我读取 main.py 的内容"

// 第 1 轮
LLM 返回：{"tool_use": {"name": "ReadFile", "args": {...}}}
  → toolCalls.size() == 1
  → 继续循环

// 第 2 轮
LLM 返回：{"text": "文件内容如下：\ndef main():\n  ..."}
  → toolCalls.size() == 0
  → break ← 退出
```

**这是最常见的正常退出路径。**

---

### 2. 达到迭代上限（防失控）

```java
// com/mewcode/agent/Agent.java (第 159-163 行)
for (int iteration = 1; ; iteration++) {     // ← 无限循环，iteration 递增
    if (maxIterations > 0 && iteration > maxIterations) {  // ← 判断条件
        putSafe(queue, new AgentEvent.ErrorEvent(
            "Agent reached maximum iterations (%d)".formatted(maxIterations)
        ));                                   // ← 发送错误事件
        break;                                // ← 退出 for 循环
    }
    
    // ... 其他步骤
}

// finally 块
} finally {
    if (!loopCompleted) {                     // ← loopCompleted == false
        putSafe(queue, new AgentEvent.LoopComplete(0));  // ← 发送完成事件
    }
}
```

**执行流程**：

```
iteration = 1, 2, 3, ..., 49, 50, 51
                                  ↓
                            51 > 50 == true
                                  ↓
                      发送 ErrorEvent("达到最大迭代次数")
                                  ↓
                              break ← 退出 for 循环
                                  ↓
                  finally 块：loopCompleted == false
                                  ↓
                      发送 LoopComplete(0)
                                  ↓
                          agentLoop() 返回
```

**为什么发送两个事件？**

- `ErrorEvent`：告诉用户为什么停止（达到上限）
- `LoopComplete(0)`：告诉 TUI 循环已结束，可以清理资源

**触发场景**：

```java
// LLM 陷入循环
第 1 轮：ReadFile("config.yaml")
第 2 轮：WriteFile("config.yaml", ...)
第 3 轮：ReadFile("config.yaml")
第 4 轮：WriteFile("config.yaml", ...)
...
第 50 轮：ReadFile("config.yaml")
第 51 轮：iteration > maxIterations
  → 发送 ErrorEvent
  → break ← 退出
```

**默认限制 50 轮。**

---

### 3. 致命错误（无法恢复）

```java
// com/mewcode/agent/Agent.java (第 306-368 行)
if (streamError) {                            // ← 流式响应出错
    var error = lastStreamException;
    
    // 3.1 尝试恢复：上下文压缩
    if (error instanceof LlmException.ContextTooLongException) {
        if (contextRetries < 3) {
            contextRetries++;
            // ... 压缩对话历史
            continue;                         // ← 重新开始本轮，不退出
        }
    }
    
    // 3.2 尝试恢复：限流重试
    if (error instanceof LlmException.RateLimitException rle) {
        if (rateLimitRetries < 3) {
            rateLimitRetries++;
            Thread.sleep(waitMs);
            continue;                         // ← 重新开始本轮，不退出
        }
    }
    
    // 3.3 无法恢复的错误
    // - API 认证失败
    // - 网络完全断开
    // - LLM 返回无效格式
    // - 重试次数耗尽
    break;                                    // ← 退出 for 循环
}

// finally 块
} finally {
    if (!loopCompleted) {                     // ← loopCompleted == false
        putSafe(queue, new AgentEvent.LoopComplete(0));
    }
}
```

**执行流程**：

```
调用 LLM API
  ↓
抛出异常（如 401 Unauthorized）
  ↓
StreamEvent.Error(exception)
  ↓
streamError = true
  ↓
检查是否可恢复
  ↓
if (ContextTooLong && retries < 3)
  → 压缩对话
  → continue ← 不退出，重试
  ↓
else if (RateLimit && retries < 3)
  → 等待后重试
  → continue ← 不退出，重试
  ↓
else (无法恢复)
  → break ← 退出 for 循环
  ↓
finally 块：发送 LoopComplete(0)
  ↓
agentLoop() 返回
```

**为什么有些错误不退出？**

可恢复的错误（上下文过长、限流）通过 `continue` 跳回循环开头重试。只有无法恢复的错误才 `break` 退出。

**触发场景**：

```java
// 场景 1：API Key 过期
第 1 轮：调用 LLM
  → 401 Unauthorized
  → 无法恢复
  → break ← 退出

// 场景 2：上下文过长（可恢复）
第 10 轮：调用 LLM
  → 413 Context Too Long
  → 压缩对话历史
  → continue ← 重试第 10 轮
```

---

### 4. 用户取消（线程中断）

有**两个检查点**，任一触发都会退出：

#### 4.1 循环开头检查

```java
// com/mewcode/agent/Agent.java (第 165 行)
for (int iteration = 1; ; iteration++) {
    // 步骤 1：检查迭代上限
    
    // 步骤 2：检查线程中断
    if (Thread.currentThread().isInterrupted()) break;  // ← 检查点 1
    
    // ... 其他步骤
}
```

**触发时机**：
- 用户按 Ctrl+C
- TUI 调用 `thread.interrupt()`
- 外部线程请求取消

**执行流程**：

```
主线程运行 agentLoop()
  ↓
用户按 Ctrl+C
  ↓
JVM 设置线程中断标志
  ↓
下一轮循环开头
  ↓
Thread.currentThread().isInterrupted() == true
  ↓
break ← 立即退出 for 循环
  ↓
finally 块：发送 LoopComplete(0)
  ↓
agentLoop() 返回
```

#### 4.2 流消费中检查

```java
// com/mewcode/agent/Agent.java (第 254-262 行)
while (true) {                                // ← 内层循环：消费流式响应
    StreamEvent event;
    try {
        event = streamQueue.poll(30, TimeUnit.SECONDS);  // ← 阻塞等待
    } catch (InterruptedException e) {        // ← 检查点 2
        Thread.currentThread().interrupt();   // ← 恢复中断标志
        return;                               // ← 立即退出 agentLoop()
    }
    
    if (event == null) {                      // ← 超时也退出
        putSafe(queue, new AgentEvent.ErrorEvent("Stream timeout"));
        return;                               // ← 立即退出 agentLoop()
    }
    
    // ... 处理事件
}
```

**为什么有两种退出方式？**

- **循环开头检查**：在轮次之间取消，干净退出
- **流消费检查**：在等待 LLM 响应时取消，立即退出

**执行流程（流消费中取消）**：

```
正在等待 LLM 响应
  ↓
streamQueue.poll(30秒) ← 阻塞中
  ↓
用户按 Ctrl+C
  ↓
poll() 抛出 InterruptedException
  ↓
catch 块：恢复中断标志
  ↓
return ← 直接退出 agentLoop()，跳过 for 循环剩余代码
  ↓
finally 块：发送 LoopComplete(0)
  ↓
agentLoop() 返回
```

**为什么用 `return` 而不是 `break`？**

```java
for (...) {          // ← 外层循环
    while (...) {    // ← 内层循环（流消费）
        try {
            event = streamQueue.poll(...);
        } catch (InterruptedException e) {
            // break 只退出 while 循环，还会继续 for 循环
            // return 直接退出 agentLoop() 方法
            return;  // ← 必须用 return
        }
    }
}
```

**`break` vs `return` 的区别**：

```java
// 使用 break
catch (InterruptedException e) {
    break;  // 只退出 while 循环
}
// 代码继续执行：保存 assistant 消息、执行工具...
// 不符合"立即取消"的语义

// 使用 return
catch (InterruptedException e) {
    return;  // 直接退出 agentLoop() 方法
}
// 代码不再执行，立即退出
// 符合"立即取消"的语义
```

**超时也是一种取消**：

```java
event = streamQueue.poll(30, TimeUnit.SECONDS);

if (event == null) {  // ← 30 秒内没有收到任何事件
    putSafe(queue, new AgentEvent.ErrorEvent("Stream timeout"));
    return;           // ← 视为异常，立即退出
}
```

如果 LLM 30 秒都不响应，视为超时错误，直接退出。

---

### finally 块的作用

```java
// com/mewcode/agent/Agent.java (第 463-467 行)
try {
    for (int iteration = 1; ; iteration++) {
        // ... 12 个步骤
    }
} finally {
    if (!loopCompleted) {
        putSafe(queue, new AgentEvent.LoopComplete(0));
    }
}
```

**为什么需要 finally 块？**

| 退出方式 | loopCompleted | finally 行为 |
|---------|---------------|--------------|
| 正常完成（无工具调用） | `true` | 不发送（已在步骤 10 发送） |
| 迭代上限 | `false` | 发送 LoopComplete(0) |
| 致命错误 | `false` | 发送 LoopComplete(0) |
| 用户取消 | `false` | 发送 LoopComplete(0) |
| 异常抛出 | `false` | 发送 LoopComplete(0) |

**保证无论如何退出，TUI 都会收到 LoopComplete 事件，可以清理资源（关闭文件、保存状态等）。**

---

### 退出后的调用栈

```
agentLoop() 返回
  ↓
回到调用方（main 或 TUI）
  ↓
程序继续执行或等待下一个任务

// 示例 1：CLI 模式
main() {
    agent.agentLoop();  // ← 阻塞直到完成
    System.exit(0);     // ← 程序退出
}

// 示例 2：TUI 模式
void runAgent() {
    new Thread(() -> {
        agent.agentLoop();  // ← 在后台线程运行
    }).start();
}

// TUI 从 queue 消费事件
while (true) {
    AgentEvent event = queue.take();
    if (event instanceof AgentEvent.LoopComplete) {
        System.out.println("任务完成");
        break;  // ← TUI 的事件循环退出
    }
}
```

---

### 小结：四种退出机制对比

| 停止条件 | 关键字 | 跳出范围 | 清理方式 |
|---------|-------|---------|---------|
| 无工具调用 | `break` | for 循环 | loopCompleted=true，不触发 finally |
| 迭代上限 | `break` | for 循环 | loopCompleted=false，finally 发送事件 |
| 致命错误 | `break` | for 循环 | loopCompleted=false，finally 发送事件 |
| 用户取消（循环开头） | `break` | for 循环 | loopCompleted=false，finally 发送事件 |
| 用户取消（流消费中） | `return` | agentLoop() | loopCompleted=false，finally 发送事件 |
| 超时 | `return` | agentLoop() | loopCompleted=false，finally 发送事件 |

**核心机制**：
1. **正常退出**：`break` + `loopCompleted=true`，跳过 finally 的重复发送
2. **异常退出**：`break`/`return` + `loopCompleted=false`，finally 保证发送 LoopComplete
3. **立即取消**：`return` 比 `break` 更彻底，直接退出方法

---

## 事件流示意图

```
Agent Loop                     StreamingExecutor               TUI
    │                                 │                        │
    ├─ IterationStart(1) ────────────────────────────────────→│
    │                                 │                        │
    ├─ 调用 LLM                        │                        │
    │                                 │                        │
    ├─ StreamText("Let me read") ────────────────────────────→│
    ├─ StreamText(" the file") ──────────────────────────────→│
    │                                 │                        │
    ├─ ToolUseEvent(ReadFile) ───────────────────────────────→│
    │                                 │                        │
    ├─ StreamEnd ────────────────────────────────────────────→│
    │                                 │                        │
    ├─ executeAll([ReadFile]) ───────→│                        │
    │                                 ├─ 权限检查               │
    │                                 ├─ PermissionRequest ────→│
    │                                 │  ← future.complete() ───│
    │                                 ├─ 执行工具               │
    │                                 ├─ ToolResultEvent ──────→│
    │                                 │                        │
    │  ← results ──────────────────────┤                        │
    │                                 │                        │
    ├─ TurnComplete(1) ──────────────────────────────────────→│
    │                                 │                        │
    ├─ IterationStart(2) ────────────────────────────────────→│
    │                                 │                        │
    ├─ 调用 LLM                        │                        │
    │                                 │                        │
    ├─ StreamText("Here is") ────────────────────────────────→│
    ├─ StreamText(" the content:") ──────────────────────────→│
    │                                 │                        │
    ├─ StreamEnd ────────────────────────────────────────────→│
    │                                 │                        │
    ├─ toolCalls.isEmpty() → break    │                        │
    │                                 │                        │
    ├─ LoopComplete(2) ──────────────────────────────────────→│
```

---

## 性能优化机制

### 1. Prompt Caching（工具列表缓存）

```java
// 每轮都调用 getAllSchemas()
var iterToolSchemas = registry.getAllSchemas(protocol);
```

虽然每轮都生成工具列表，但 Anthropic API 会自动缓存：

```
第 1 轮：10 个工具 → 2000 tokens → 全价计费
第 2 轮：11 个工具（加了 CodeSearch）
  - 前 10 个工具 schema 完全相同 → 命中缓存，90% 折扣
  - 只有新增的 1 个工具按全价计费
```

**结论**：每轮重新生成工具列表不会造成显著的 token 浪费。

---

### 2. 工具结果预算控制

```java
// 在上下文压缩前应用
List<ContentReplacementRecord> forceRecords =
    ToolResultBudget.apply(conv, forceSessionDir, replacementState);
```

大型工具输出（如 Grep 返回 1000 行匹配）会被替换为摘要：

```
原始输出（10,000 tokens）：
  file1.py:10:match1
  file1.py:20:match2
  ... (1000 行)

替换后（100 tokens）：
  [Result of Grep spilled to disk (10000 chars).
   Read .mewcode/session/tool_results/toolu_abc123 to see full output.]
```

**节省了 99% 的 token，同时保留了完整输出供查阅。**

#### 工具结果文件的生命周期

**存储位置**：
```
工作目录/
  └── .mewcode/
      └── session/
          └── tool_results/           ← 工具结果溢出目录
              ├── toolu_abc123        ← 以 toolUseId 命名
              ├── toolu_def456
              └── toolu_ghi789
```

**文件命名**：
- 文件名就是 `toolUseId`（LLM 生成的工具调用 ID，如 `toolu_01ABC123XYZ`）
- 无扩展名，纯文本文件
- 一次工具调用对应一个文件

**写入时机**：
```java
// com/mewcode/toolresult/ToolResultBudget.java (第 217-228 行)
private static String spillAndPreview(Path spillDir, ToolResultBlock tr) {
    Files.createDirectories(spillDir);
    Path file = spillDir.resolve(tr.toolUseId());  // ← 文件名就是 toolUseId
    
    // 如果文件已存在且大小匹配，直接复用
    if (Files.exists(file) && Files.size(file) == tr.content().length()) {
        return buildSpillPreview(tr.content(), file);
    }
    
    // 否则写入新文件
    Files.writeString(file, tr.content());
    return buildSpillPreview(tr.content(), file);
}
```

**触发条件**：

1. **单个结果超限**：`content.length() > 50,000` 字符
2. **消息聚合超限**：一条消息中所有工具结果总和 > 200,000 字符

```java
// com/mewcode/toolresult/ToolResultBudget.java
public static final int SINGLE_RESULT_LIMIT = 50_000;
public static final int MESSAGE_AGGREGATE_LIMIT = 200_000;
```

**文件生命周期管理**：

✅ **有自动清理机制**

```java
// com/mewcode/session/SessionManager.java (第 282-310 行)
private static final long EXPIRY_DAYS = 14;  // ← 过期阈值：14 天

public static void cleanExpiredSessions(String workDir) {
    // 清理工具结果目录
    Path toolResultsDir = Path.of(workDir, ".mewcode", "session", "tool_results");
    if (Files.isDirectory(toolResultsDir)) {
        long cutoffMs = System.currentTimeMillis() - EXPIRY_DAYS * 24 * 60 * 60 * 1000L;
        try (Stream<Path> paths = Files.list(toolResultsDir)) {
            paths.filter(Files::isRegularFile)
                 .forEach(p -> {
                     try {
                         long mtime = Files.getLastModifiedTime(p).toMillis();
                         if (mtime < cutoffMs) {
                             Files.deleteIfExists(p);  // ← 删除过期工具结果
                         }
                     } catch (IOException ignored) {}
                 });
        } catch (IOException ignored) {}
    }
}
```

**触发时机**：

```java
// com/mewcode/tui/MewCodeModel.java (第 578 行)
// TUI 启动时自动清理
com.mewcode.session.SessionManager.cleanExpiredSessions(workDir);
```

**清理机制**：

1. **工具结果文件清理**：删除 `.mewcode/session/tool_results/` 下超过 14 天的所有文件
2. **会话文件保留**：`.mewcode/sessions/*.jsonl` 会话文件**不会被删除**，永久保留
3. **过期时间**：14 天未修改的工具结果文件自动清理
4. **清理时机**：TUI 启动时自动触发
5. **容错机制**：单个文件删除失败不影响其他文件

**目录结构**：

```
工作目录/
  └── .mewcode/
      ├── session/                          ← 工具结果目录（单数）
      │   └── tool_results/
      │       ├── toolu_abc123              ← 14 天后自动删除
      │       ├── toolu_def456              ← 14 天后自动删除
      │       └── toolu_ghi789
      └── sessions/                         ← 会话文件目录（复数）
          ├── 20241201-143022-a3f2.jsonl    ← 永久保留
          └── 20241202-090815-b7e4.jsonl    ← 永久保留
```

**实际影响**：

```
假设场景：
- 每天 10 个会话
- 每个会话 5 个大型工具输出
- 14 天过期清理
  ↓
活跃工具结果：10 × 14 × 5 = 700 个文件
平均大小：100 KB/文件
总占用：700 × 100 KB = 70 MB

会话文件：永久保留，但单个文件通常只有几十 KB

14 天后自动清理工具结果，磁盘占用稳定在 70 MB 左右。
```

**手动清理命令**：

```bash
# 清理所有过期工具结果（14 天前）
find .mewcode/session/tool_results -type f -mtime +14 -delete

# 清理所有工具结果文件（立即）
rm -rf .mewcode/session/tool_results

# 手动清理会话文件（如果需要）
find .mewcode/sessions -name "*.jsonl" -mtime +30 -delete

# 清理整个会话相关目录（立即，包括会话文件和工具结果）
rm -rf .mewcode/sessions .mewcode/session
```

**特殊情况：ReadFile 回读优化**

如果 LLM 调用 `ReadFile` 读取之前溢写的工具结果文件，系统会直接返回原始内容，而不是再次截断：

```java
// com/mewcode/toolresult/ToolResultBudget.java (第 43-52 行)
private static boolean isSpillReadback(ToolResultBlock tr, ...) {
    var tu = toolUseIndex.get(tr.toolUseId());
    if ("ReadFile".equals(tu.toolName())) {
        String path = tu.arguments().get("file_path");
        if (path.startsWith(absSpillDir)) {
            return true;  // ← 跳过截断，保留完整内容
        }
    }
    return false;
}
```

这样 LLM 可以通过 `ReadFile(".mewcode/session/tool_results/toolu_abc123")` 重新访问完整输出。

---

### 3. 并发工具执行

```java
// StreamingExecutor.executeAll()
var batches = partitionToolCalls(calls);

for (var batch : batches) {
    if (batch.concurrent && batch.calls.size() > 1) {
        // 虚拟线程池并行执行
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = batch.calls.stream()
                .map(call -> executor.submit(() -> executeSingle(call)))
                .toList();
            for (var future : futures) {
                results.add(future.get());
            }
        }
    } else {
        // 串行执行
        for (var call : batch.calls) {
            results.add(executeSingle(call));
        }
    }
}
```

**优化效果**：

```
串行执行 3 个 ReadFile：300ms + 300ms + 300ms = 900ms
并行执行 3 个 ReadFile：max(300ms, 300ms, 300ms) = 300ms
```

**节省了 67% 的执行时间。**

---

## 常见问题

### Q1：为什么是无限循环而不是递归？

**A**：递归会消耗栈空间，50 次迭代就需要 50 层调用栈。无限循环只需要常量栈空间，并且更容易控制退出条件。

---

### Q2：为什么要两个队列（streamQueue 和 queue）？

**A**：

- `streamQueue`：LLM 客户端（可能在另一个线程）写入流式事件
- `queue`：Agent Loop 读取流式事件，转换后写入 AgentEvent
- **解耦**：中间可以做过滤、转换、增强，不依赖 LLM 客户端的实现细节

---

### Q3：为什么每次都新建 StreamingExecutor？

**A**：每次新建保证独立的 `runId`，用于区分不同轮次的工具执行。崩溃恢复时可以根据 runId 判断哪些记录是上次留下的脏数据。

---

### Q4：如果工具执行很慢，会阻塞循环吗？

**A**：会。`executor.executeAll(callInfos)` 是同步调用，必须等所有工具执行完才继续。但工具内部可以并行执行（只读工具），所以总时间取决于最慢的那个工具。

---

### Q5：max_tokens 恢复机制真的有用吗？

**A**：非常有用。LLM 生成长文本时经常触发 max_tokens，如果直接截断，可能导致工具调用的 JSON 不完整。恢复机制让 LLM 有机会继续输出，或者分段输出。

---

## 小结

Agent Loop 的设计体现了几个核心原则：

1. **无限循环 + 多条件退出**：清晰的控制流，每个退出条件独立检查
2. **事件驱动**：通过 BlockingQueue 解耦 Agent 和 UI，支持异步交互
3. **错误恢复优先**：可恢复的错误（上下文过长、限流）自动重试，不轻易退出
4. **性能优化内建**：Prompt Caching、并发执行、结果预算控制
5. **可观测性**：每个关键步骤都发送事件，UI 可以实时展示进度

这是一个**生产级、容错、高性能**的 Agent 运行时。代码总量约 500 行，但覆盖了 LLM 交互、工具执行、错误恢复、性能优化等多个维度，形成了完整的智能体控制循环。
