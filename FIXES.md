# 教程文档与现有源码对比，以及 LLM 错误处理修改计划

> **状态更新 (2026-08-19)**: ✅ 所有核心改进已完成并测试通过
> 
> - ✅ 异常分类体系（4 种语义化异常类型）
> - ✅ 流式事件传递（类型化 `StreamEvent.Error`）
> - ✅ Agent 恢复策略（上下文压缩、限流重试、认证快速失败）
> - ✅ 完整测试覆盖（144 个测试全部通过）
> 
> 详见：[docs/错误处理改进完成报告.md](docs/错误处理改进完成报告.md)

## 1. 对比范围

本文对比以下三部分：

1. **早期教程文本**  
   主题为“Java 源码解析：LLM 客户端与流式响应”，其中描述了手写 HTTP、Anthropic SSE、OpenAI Responses API 等实现。

2. **当前教程文档**

```text
docs/LLM客户端与流式响应.md
```

该版本已经部分按照 LangChain4j 重构后的代码进行了修订。

3. **当前源码**

主要涉及：

```text
src/main/java/com/mewcode/llm
src/main/java/com/mewcode/conversation
src/main/java/com/mewcode/agent
```

---

# 2. 总体结论

当前项目经历过一次较大的 LLM 层重构：

```text
旧教程描述：
手写 HTTP + SSE 解析 + 独立 AnthropicClient/OpenAiClient

当前源码：
LangChain4j + StreamingChatResponseHandler
+ LangChainClient 统一适配
```

因此：

- 旧教程中的架构思想仍有参考价值；
- 旧教程中的具体类名、方法名和执行流程已经不完全适用；
- 当前新版教程已经修正了大部分通信层描述；
- 错误处理部分仍然需要单独维护“当前实现”和“目标设计”的边界。

---

# 3. 文件结构差异

## 教程中的描述

早期教程认为 LLM 层共有约 11 个文件，并包含：

```text
AnthropicClient.java
OpenAiClient.java
ModelResolver.java
ConversationManager.java
Message.java
ThinkingBlock.java
ToolUseBlock.java
ToolResultBlock.java
```

## 当前源码实际情况

当前相关文件共有 12 个：

```text
llm/
├── AnthropicClient.java
├── LangChainClient.java
├── LlmClient.java
├── LlmException.java
├── OpenAiClient.java
├── OpenAiCompatClient.java
└── StreamEvent.java

conversation/
├── ConversationManager.java
├── Message.java
├── ThinkingBlock.java
├── ToolResultBlock.java
└── ToolUseBlock.java
```

主要差异：

- 当前新增了 `LangChainClient.java`
- 当前新增了 `OpenAiCompatClient.java`
- `ModelResolver.java` 已经删除
- `AnthropicClient.java` 和 `OpenAiClient.java` 已经不是独立传输实现
- 文件数量和代码规模已经与旧教程不一致

---

# 4. `LlmClient` 工厂逻辑差异

## 早期教程描述

```java
static LlmClient create(ProviderConfig cfg, String systemPrompt) {
    return switch (cfg.getProtocol()) {
        case "anthropic" -> new AnthropicClient(cfg, systemPrompt);
        case "openai" -> new OpenAiClient(cfg, systemPrompt);
        default -> throw new IllegalArgumentException();
    };
}
```

这个描述表示每种 Provider 有自己独立的客户端实现。

## 当前源码实际情况

当前实现是：

```java
static LlmClient create(
        ProviderConfig cfg,
        String systemPrompt
) {
    return switch (cfg.getProtocol()) {
        case "anthropic",
             "openai",
             "openai-compat",
             "ollama" ->
                new LangChainClient(cfg, systemPrompt);

        default ->
            throw new IllegalArgumentException(
                    "Unknown protocol: " + cfg.getProtocol()
            );
    };
}
```

当前所有协议统一返回：

```java
LangChainClient
```

支持的协议包括：

```text
anthropic
openai
openai-compat
ollama
```

另外，当前 `LlmClient` 不只包含 `stream()`：

```java
BlockingQueue<StreamEvent> stream(...);

default void setMaxOutputTokens(int tokens);

void setSystemPrompt(String prompt);

static LlmClient create(...);
```

因此“接口只有一个方法”的说法不再完全准确。

---

# 5. `AnthropicClient` 和 `OpenAiClient` 的实际角色

当前：

```java
public final class AnthropicClient
        extends LangChainClient
```

```java
public final class OpenAiClient
        extends LangChainClient
```

这两个类的注释已经说明：

```text
Compatibility name retained for the MewCode tutorials;
transport is LangChain4j.
```

它们主要是兼容层，而不是完整的 API 通信实现。

当前真实关系是：

```text
AnthropicClient
OpenAiClient
OpenAiCompatClient
        ↓
   LangChainClient
        ↓
    LangChain4j
```

因此教程中如果写：

```text
AnthropicClient 负责手写 HTTP 和 SSE
```

应该改为：

```text
LangChainClient 负责通过 LangChain4j 适配不同 Provider。
AnthropicClient 和 OpenAiClient 仅作为兼容名称保留。
```

---

# 6. HTTP 和 SSE 实现差异

## 早期教程描述

旧教程描述了：

```java
HttpClient.newHttpClient()
HttpRequest.newBuilder()
BufferedReader.readLine()
event: content_block_delta
event: content_block_stop
```

并且由项目自己解析 Anthropic SSE。

## 当前源码实际情况

当前使用 LangChain4j：

```java
StreamingChatModel model = buildModel(tokenSnapshot);

model.chat(
        request,
        handler(queue)
);
```

不同 Provider 通过 `buildModel()` 创建：

```java
AnthropicStreamingChatModel
OpenAiStreamingChatModel
OllamaStreamingChatModel
```

流式回调来自：

```java
StreamingChatResponseHandler
```

主要回调包括：

```java
onPartialResponse(...)
onPartialThinking(...)
onPartialToolCall(...)
onCompleteToolCall(...)
onCompleteResponse(...)
onError(...)
```

当前流程是：

```text
LangChain4j 回调
    ↓
LangChainClient.handler()
    ↓
StreamEvent
    ↓
Agent
```

而不是：

```text
原始 SSE 文本
    ↓
BufferedReader
    ↓
手写事件 switch
    ↓
StreamEvent
```

因此旧教程中的以下内容属于历史实现：

```text
content_block_start
content_block_delta
content_block_stop
message_start
message_delta
BufferedReader
java.net.http.HttpClient
```

---

# 7. Message 序列化位置差异

## 早期教程描述

教程曾描述：

```text
ConversationManager.serializeAnthropic()
ConversationManager.serializeOpenAI()
```

由 `ConversationManager` 负责把内部消息转换成各 Provider 的 JSON。

## 当前源码实际情况

当前 `ConversationManager` 主要负责维护内部历史：

```java
addUserMessage(...)
addAssistantMessage(...)
addAssistantFull(...)
addToolResultsMessage(...)
addSystemReminder(...)
getMessages()
truncateTo(...)
```

它没有：

```java
serializeAnthropic()
serializeOpenAI()
```

消息转换现在位于：

```java
LangChainClient.toMessages(...)
```

并转换为 LangChain4j 的类型：

```java
SystemMessage
UserMessage
AiMessage
ToolExecutionResultMessage
```

当前结构是：

```text
ConversationManager
    保存内部 Message

LangChainClient.toMessages()
    转成 LangChain4j ChatMessage

LangChain4j Provider Model
    负责最终协议转换
```

---

# 8. `StreamEvent` 差异

当前仍然使用：

```java
public sealed interface StreamEvent
```

事件包括：

```java
TextDelta
ThinkingDelta
ThinkingComplete
ToolCallStart
ToolCallDelta
ToolCallComplete
StreamEnd
Error
```

这部分整体一致。

但是当前 `StreamEnd` 包含缓存 Token：

```java
record StreamEnd(
        String stopReason,
        int inputTokens,
        int outputTokens,
        int cacheReadTokens,
        int cacheCreationTokens
)
```

旧教程中的三参数版本只是兼容构造函数：

```java
new StreamEnd(
        stopReason,
        inputTokens,
        outputTokens
)
```

当前真实事件模型比旧教程更完整。

---

# 9. ModelResolver 差异

旧教程包含：

```java
ModelResolver.resolve("sonnet")
```

并维护：

```text
haiku
sonnet
opus
```

到具体模型 ID 的映射。

当前 `ModelResolver.java` 已经删除。

删除原因是：

```text
不再使用硬编码的模型别名映射
```

当前模型名称直接来自：

```java
ProviderConfig.getModel()
```

因此教程中的以下内容需要标记为历史设计：

```text
ModelResolver
supportsAdaptiveThinking()
ALIASES
haiku/sonnet/opus 短名称
```

---

# 10. API Key 初始化行为差异

旧教程描述：

```java
AnthropicClient 构造时立即检查 API Key，
为空就抛 AuthenticationException。
```

当前 `LangChainClient` 构造时主要保存：

```java
ProviderConfig
systemPrompt
maxOutputTokens
```

API Key 在构建 Provider 模型时读取：

```java
String key = config.resolvedApiKey();
```

当前没有完全实现旧教程所说的：

```text
构造阶段 fail-fast
```

因此教程中应改成：

```text
当前 API Key 的读取位于 Provider 模型构建阶段。
是否为空以及 Provider 如何处理空 Key，取决于具体 LangChain4j 模型实现。
```

---

# 11. 错误处理当前状态

## 旧教程描述

旧教程描述了：

```java
classifyHttpError(int status, String body)
classifyError(Exception e)
```

并将错误转换为：

```java
AuthenticationException
RateLimitException
ContextTooLongException
NetworkException
```

## 当前工作区实现

当前错误处理已经向这个目标靠拢：

### `LlmException`

负责定义错误层次：

```text
LlmException
├── AuthenticationException
├── RateLimitException
├── ContextTooLongException
└── NetworkException
```

并提供：

```java
LlmException.classify(Throwable)
LlmException.classifyHttpError(...)
```

### `StreamEvent.Error`

当前不再只保存字符串，而是保留类型化异常：

```java
record Error(LlmException exception)
        implements StreamEvent
```

同时保留兼容构造：

```java
new StreamEvent.Error("message")
```

并提供：

```java
event.message()
event.exception()
```

### `LangChainClient`

现在会将底层异常分类：

```java
new StreamEvent.Error(
        LlmException.classify(failure)
)
```

### `Agent`

现在按异常类型处理：

```java
if (error instanceof ContextTooLongException) {
    // 压缩上下文并重试
}
```

```java
if (error instanceof RateLimitException rateLimit) {
    // 根据 Retry-After 或退避时间重试
}
```

认证错误和未知错误不会自动重试。

---

# 12. 错误修改计划

## 目标

错误处理需要满足：

```text
底层 Provider 异常
    ↓
语义化 LlmException
    ↓
StreamEvent.Error
    ↓
Agent 按类型恢复
    ↓
TUI / Remote / Print 展示
```

---

## 第一阶段：异常分类

当前已经具备：

### 认证错误

```text
401
403
LangChain4j AuthenticationException
invalid api key
unauthorized
```

转换为：

```java
LlmException.AuthenticationException
```

### 限流错误

```text
429
LangChain4j RateLimitException
rate limit
too many requests
```

转换为：

```java
LlmException.RateLimitException
```

并保存：

```java
retryAfter
```

### 上下文过长

```text
413
prompt is too long
context length
context window
too many tokens
```

转换为：

```java
LlmException.ContextTooLongException
```

### 网络错误

包括：

```text
IOException
ConnectException
SocketTimeoutException
HttpTimeoutException
LangChain4j TimeoutException
```

转换为：

```java
LlmException.NetworkException
```

### 未知错误

其他情况统一转换为：

```java
LlmException
```

---

## 第二阶段：流式事件传递 ✅ 已完成

`StreamEvent.Error` 需要保留完整异常对象：

```java
record Error(LlmException exception)
        implements StreamEvent
```

这样 Agent 不再依赖：

```java
message.contains("rate limit")
message.contains("context")
```

而是依赖：

```java
instanceof LlmException.RateLimitException
instanceof LlmException.ContextTooLongException
```

这可以避免 Provider 更换错误文案后导致恢复逻辑失效。

---

## 第三阶段：Agent 恢复策略 ✅ 已完成

### 上下文过长 ✅

处理策略：

```text
最多重试 3 次
先裁剪工具结果
再执行上下文压缩
重新发起 LLM 请求
```

### 限流 ✅

处理策略：

```text
优先使用 Retry-After
没有 Retry-After 时使用指数退避
最多重试 3 次
```

当前默认退避大致为：

```text
1 秒
2 秒
4 秒
```

并设置最大等待上限。

### 认证错误 ✅

处理策略：

```text
不自动重试
直接通知上层
提示检查 API Key、模型配置和环境变量
```

### 网络错误 ✅ 分类完成，重试待实现

当前已经能够分类为：

```java
NetworkException
```

后续可以继续增加：

```text
最多 2～3 次网络重试
指数退避
区分连接超时和服务端错误
```

### 普通未知错误

处理策略：

```text
不自动重试
通过 AgentEvent.ErrorEvent 通知前端
保留原始 cause 供日志排查
```

---

# 13. 当前错误实现仍然存在的限制

## 1. `Retry-After` 目前是尽力解析

LangChain4j 的：

```java
HttpException
```

当前主要暴露：

```java
statusCode()
```

不一定直接暴露完整的 HTTP 响应头。

因此当前 `retryAfter` 主要通过异常消息进行解析：

```text
Retry-After: 7
```

如果底层异常消息不包含该信息，系统会退回到指数退避。

如果要百分百读取 HTTP 响应头，需要：

```text
自定义 LangChain4j HttpClient
```

或者：

```text
改用能够暴露 response headers 的传输层
```

---

## 2. Provider 的 HTTP 错误可能只有异常文本

如果 LangChain4j 只提供：

```java
Throwable
```

而没有明确状态码，那么状态码识别只能作为兜底策略。

更可靠的做法是让传输层暴露：

```java
statusCode
responseBody
responseHeaders
```

再调用：

```java
classifyHttpError(status, body, retryAfter)
```

---

## 3. 部分下层消费者只读取错误消息

例如：

```java
ContextCompactor
MemoryManager
```

目前仍然主要读取：

```java
err.message()
```

这不会破坏功能，但它们暂时不会根据：

```java
err.exception()
```

做精细化恢复。

后续可以统一增加：

```java
instanceof ContextTooLongException
```

等类型分支。

---

# 14. 测试计划 ✅ 已完成

当前已经增加错误分类单元测试，覆盖：

```text
401 → AuthenticationException
403 → AuthenticationException
429 → RateLimitException
Retry-After 提取
413 → ContextTooLongException
400 + prompt is too long → ContextTooLongException
IOException → NetworkException
已有 LlmException 透传
未知异常 → 基类 LlmException
StreamEvent.Error 保留异常对象
```

当前完整构建结果：

```text
144 个测试（新增 6 个 Agent 恢复测试）
0 失败
0 错误
1 个跳过
clean verify 成功
```

✅ **已补充测试** (AgentErrorRecoveryTest.java):

```text
✅ Agent 遇到上下文过长时重新压缩并重试
✅ Agent 遇到限流时按照 Retry-After 等待
✅ 认证错误不会重试（快速失败）
✅ 网络错误当前不重试（已验证行为）
✅ LangChain4j 异常包装在 CompletionException 中时正确识别
✅ StreamEvent.Error 保留类型化异常对象
✅ 指数退避算法验证
✅ Retry-After 解析验证
```

📝 **待补充测试** (可选):

```text
Remote/Print/TUI 是否都能正常显示类型化错误（需要集成测试环境）
```

---

# 15. 最终结论

教程中的总体设计方向是正确的：

```text
LlmClient
StreamEvent
ConversationManager
LlmException
Agent Loop
```

但旧教程描述的是：

```text
手写 HTTP + SSE 的旧实现
```

当前代码实际是：

```text
LangChain4j 统一传输实现
+ StreamEvent 适配
+ Agent 自己控制工具和上下文
```

错误处理方面，当前工作区已经从：

```text
Throwable → String → StreamEvent.Error
```

改造成：

```text
Throwable
    ↓
LlmException 分类
    ↓
StreamEvent.Error(LlmException)
    ↓
Agent 按异常类型恢复
```

剩余工作主要是：

1. 从真实 HTTP 响应头中可靠读取 `Retry-After`
2. 增加网络异常的有限重试
3. 为 Agent 恢复逻辑补集成测试
4. 将教程中旧的手写 HTTP/SSE 部分明确标注为历史实现
5. 保持教程与当前 LangChain4j 源码同步更新