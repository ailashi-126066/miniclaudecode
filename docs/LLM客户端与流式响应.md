# Java源码解析：LLM 客户端与流式响应（基于 LangChain4j）

本文带你深入 MiniCode 项目的 LLM 通信层，看看如何用 Java 21 + LangChain4j 实现流式 AI 对话。

---

## 模块概览

MiniCode 的 LLM 通信层是整个项目的核心，负责与各种 AI 提供商（Anthropic、OpenAI、Ollama）进行交互。这一层的设计哲学是：**统一抽象、分层解耦、类型安全**。

代码分布在两个包下，共 **12 个文件**，每个文件都有明确的职责：

### llm 包（5 个文件）

**核心接口层**：
- **LlmClient.java**：整个 LLM 层的对外契约。它只暴露一个 `stream()` 方法，返回一个事件队列。工厂方法也内置在这个接口中，调用方通过 `LlmClient.create()` 就能拿到正确的实现，完全不需要知道底层是 Anthropic 还是 OpenAI。

**统一实现层**：
- **LangChainClient.java**：这是当前的核心实现类。它使用 LangChain4j 库来适配所有协议，不再手写 HTTP 请求和 SSE 解析。所有的协议差异都在这个类内部通过 `buildModel()` 方法来处理，对外暴露统一的 `StreamEvent` 接口。

- **OpenAiCompatClient.java**：用于支持 OpenAI 兼容的 API（比如 Azure OpenAI、Groq 等）。

**事件定义层**：
- **StreamEvent.java**：定义了 8 种流式事件类型。使用 Java 17 的 sealed interface 特性，编译器能够确保所有 switch 语句都覆盖了所有可能的事件类型。这是类型安全的核心保障。

**异常体系**：
- **LlmException.java**：定义了 4 种异常类型（认证错误、超速错误、上下文过长、网络错误）。虽然当前的 LangChain4j 实现还没有完全使用这套异常体系，但它为未来的错误处理提供了完整的基础设施。

### conversation 包（5 个文件）

**对话管理**：
- **ConversationManager.java**：负责维护整个对话的历史记录。它提供了添加用户消息、助手消息、工具结果的方法，并且能够在上下文过长时进行截断。这个类是对话状态的唯一真相来源。

**消息结构**：
- **Message.java**：一条消息的完整表示。它是可变的（用 class 而不是 record），因为 AI 的回复需要逐步构建——先收到文本，再收到思考块，最后收到工具调用。如果用不可变的 record，每次更新都要创建新对象，性能和代码复杂度都会增加。

**消息组件**（3 个 record）：
- **ThinkingBlock.java**：AI 的思考过程。Anthropic 的 Extended Thinking 功能会返回 AI 的内部推理过程，包括一个防篡改的签名。
- **ToolUseBlock.java**：AI 想要调用的工具。包含工具 ID、工具名称和参数（用 `Map<String, Object>` 存储，因为每个工具的参数结构都不同）。
- **ToolResultBlock.java**：工具执行的结果。包含工具 ID、执行输出和是否出错的标记。

### 代码规模与设计权衡

整个 LLM 通信层约 **505 行**（不含空行和注释），分散在 12 个文件中。这个文件数量看起来有点多，但这是 Java 的硬性要求：**每个 public 类型必须独立成文件**。

虽然啰嗦，但好处也很明显：
1. **IDE 跳转精确**：Ctrl+Click 直接跳到类定义，不需要在大文件里翻找
2. **Git diff 清晰**：修改一个类只影响一个文件，代码审查更容易
3. **职责边界明确**：文件树本身就是架构图

对比其他语言：Python 可以把所有类型放在一个文件里，代码行数会少很多，但 Java 的这种"强制分离"反而有助于保持代码的组织性。

---

## 核心类型

### LlmClient：接口 + 工厂一体化

LlmClient 是整个 LLM 层的对外接口，只有一个业务方法 `stream()` 和一个工厂方法 `create()`。

`stream()` 方法的参数是对话历史和可用工具列表，返回流式事件队列。多轮对话、工具执行、上下文管理由上层 Agent 负责，LLM 层只负责通信。

工厂方法 `create()` 直接定义在接口内，利用 Java 8 的接口静态方法特性。调用方只需要知道 `LlmClient` 这一个类型，不需要额外的工厂类。

整个 LLM 层只有一个对外接口，一个核心方法：

```java
public interface LlmClient {
    BlockingQueue<StreamEvent> stream(
        ConversationManager conv,
        List<Map<String, Object>> tools
    );
    
    static LlmClient create(ProviderConfig cfg, String systemPrompt) {
        return switch (cfg.getProtocol()) {
            case "anthropic", "openai", "openai-compat", "ollama" ->
                    new LangChainClient(cfg, systemPrompt);
            default ->
                    throw new IllegalArgumentException(
                            "Unknown protocol: " + cfg.getProtocol()
                    );
        };
    }
}
```

**实现细节**：

#### 1. 接口内静态工厂（Java 8+）

这个设计利用了 Java 8 允许接口包含静态方法的特性。工厂方法 `create()` 直接定义在接口内部，而不是单独的工厂类。

**传统做法的问题**：
```java
// 需要两个类型
LlmClientFactory factory = new LlmClientFactory();
LlmClient client = factory.create(cfg, prompt);

// 或者静态方法
LlmClient client = LlmClientFactory.create(cfg, prompt);
```

调用方需要知道 `LlmClientFactory` 的存在，增加了认知负担。而且从语义上讲，"创建 LlmClient"应该是 `LlmClient` 自己的责任，而不是另一个类的责任。

**当前设计的优势**：
```java
// 只需要知道 LlmClient 一个类型
LlmClient client = LlmClient.create(cfg, prompt);
```

这种设计在 Java 标准库中也有使用，比如 `List.of()`、`Set.of()` 等工厂方法都定义在接口中。

#### 2. 统一实现策略

当前的工厂方法看起来很简单，但它体现了一个重要的架构演进：**从多实现到单实现适配**。

**旧设计**（文档描述的版本）：
```java
case "anthropic" -> new AnthropicClient(cfg, systemPrompt);
case "openai" -> new OpenAiClient(cfg, systemPrompt);
```

每个协议一个类，每个类都要手写 HTTP 请求、SSE 解析、错误处理。代码重复度高，维护成本大。

**当前实现**（LangChain4j 版本）：
```java
case "anthropic", "openai", "openai-compat", "ollama" ->
    new LangChainClient(cfg, systemPrompt);
```

所有协议都用同一个 `LangChainClient`，协议差异通过 `buildModel()` 方法内部的 switch 来处理。

**LangChainClient 和 buildModel() 的关系**：

**文件位置**：`src/main/java/com/mewcode/llm/LangChainClient.java`

```java
// LangChainClient.java
public class LangChainClient implements LlmClient {
    private final ProviderConfig config;
    private final String systemPrompt;
    private final int maxOutputTokens;
    
    // 构造函数：保存配置
    public LangChainClient(ProviderConfig config, String systemPrompt) {
        this.config = config;
        this.systemPrompt = systemPrompt;
        this.maxOutputTokens = config.resolvedMaxOutputTokens();
    }
    
    // 实现 LlmClient 接口
    @Override
    public BlockingQueue<StreamEvent> stream(
        ConversationManager conv,
        List<Map<String, Object>> tools
    ) {
        var queue = new LinkedBlockingQueue<StreamEvent>(64);
        
        Thread.ofVirtual().start(() -> {
            try {
                doStream(conv, tools, queue);  // 调用 doStream
            } catch (Exception e) {
                put(queue, new StreamEvent.Error(classify(e)));
            }
        });
        
        return queue;
    }
    
    // 私有方法：实际执行流式请求
    private void doStream(
        ConversationManager conv,
        List<Map<String, Object>> tools,
        BlockingQueue<StreamEvent> queue
    ) {
        TokenSnapshot snapshot = new TokenSnapshot();
        
        // ⭐ 调用 buildModel() 创建 LangChain4j 模型
        StreamingChatModel model = buildModel(snapshot);
        
        // 转换消息和工具
        List<ChatMessage> messages = toMessages(conv.getMessages(), systemPrompt);
        List<ToolSpecification> toolSpecs = toToolSpecs(tools);
        
        // 构建请求
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolSpecs)
                .build();
        
        // 调用 LangChain4j
        model.chat(request, handler(queue));
    }
    
    // ⭐ 关键方法：根据协议创建不同的 LangChain4j 模型
    private StreamingChatModel buildModel(TokenSnapshot snapshot) {
        // 根据 config.getProtocol() 的值选择不同的模型
        return switch (config.getProtocol()) {
            case "anthropic" -> AnthropicStreamingChatModel.builder()
                    .apiKey(config.resolvedApiKey())
                    .modelName(config.getModel())
                    .maxTokens(maxOutputTokens)
                    .build();
                    
            case "openai" -> OpenAiStreamingChatModel.builder()
                    .apiKey(config.resolvedApiKey())
                    .modelName(config.getModel())
                    .maxTokens(maxOutputTokens)
                    .build();
                    
            case "ollama" -> OllamaStreamingChatModel.builder()
                    .baseUrl(config.getBaseUrl())
                    .modelName(config.getModel())
                    .numPredict(maxOutputTokens)
                    .build();
                    
            default -> throw new IllegalArgumentException(
                "Unknown protocol: " + config.getProtocol()
            );
        };
    }
}
```

**调用链路**：

```
Agent 构造函数
    ↓
LlmClient.create(cfg, systemPrompt)
    ↓ cfg.getProtocol() = "anthropic"
new LangChainClient(cfg, systemPrompt)
    ↓ 保存配置
this.config = cfg
    ↓
Agent.agentLoop() 调用
    ↓
client.stream(conv, tools)
    ↓
LangChainClient.stream()
    ↓ 启动虚拟线程
doStream(conv, tools, queue)
    ↓
buildModel(snapshot)  ← 根据 config.getProtocol() 创建模型
    ↓ switch (config.getProtocol())
    ├─ "anthropic" → AnthropicStreamingChatModel
    ├─ "openai" → OpenAiStreamingChatModel
    └─ "ollama" → OllamaStreamingChatModel
    ↓
model.chat(request, handler)  ← 调用 LangChain4j 发送请求
```

**为什么这样设计**：

1. **LangChainClient**：适配器，统一所有协议的接口
   - 实现 `LlmClient` 接口
   - 管理队列、虚拟线程、异常处理
   - 转换消息格式（Message → ChatMessage）

2. **buildModel()**：策略选择器，根据协议创建具体模型
   - 读取 `config.getProtocol()` 的值
   - 通过 switch 选择创建哪个 LangChain4j 模型
   - 返回统一的 `StreamingChatModel` 接口

3. **职责分离**：
   - LangChainClient 负责：队列、线程、消息转换
   - buildModel() 负责：协议选择
   - LangChain4j 模型负责：HTTP 通信、SSE 解析

**代码复用效果**：

- HTTP 请求代码：只在 LangChain4j 库中，写一次
- 队列管理：只在 `LangChainClient.stream()` 中，写一次
- 异常处理：只在 `LangChainClient.stream()` 中，写一次
- 消息转换：只在 `toMessages()` 中，写一次
- 协议差异：只在 `buildModel()` 的 switch 中，每个协议一个 case

**扩展方式**：新增协议只需要在 `buildModel()` 中加一个 case：

```java
private StreamingChatModel buildModel(TokenSnapshot snapshot) {
    return switch (config.getProtocol()) {
        case "anthropic" -> ...
        case "openai" -> ...
        case "ollama" -> ...
        case "azure" -> AzureOpenAiStreamingChatModel.builder()...  // 新增这一行
        default -> throw new IllegalArgumentException(...);
    };
}
```

这使用了**适配器模式** + **策略模式**：`LangChainClient` 是适配器，`buildModel()` 是策略选择器。

#### 3. 单队列错误传递

`stream()` 方法返回 `BlockingQueue<StreamEvent>`，这个队列既传递正常事件，也传递错误事件。

**单队列的实现**：

```java
while (true) {
    StreamEvent event = queue.poll(30, TimeUnit.SECONDS);
    
    switch (event) {
        case TextDelta td -> handleText(td);
        case Error e -> handleError(e);  // 错误和正常事件统一处理
        case StreamEnd se -> break;
    }
}
```

错误在队列中的位置就是它发生的位置，事件的时序关系完全保留。这体现了**统一接口原则**：成功和失败都是事件，都走同一个通道。

---

### StreamEvent：sealed interface + records

StreamEvent 定义了 LLM 流式响应的事件类型。使用 Java 16/17 的 sealed interface 和 record 特性。

**8 种事件类型**：

LLM 的流式响应包含多种内容：
- **文本**（TextDelta）：AI 回复的正文
- **思考**（ThinkingDelta / ThinkingComplete）：AI 的内部推理过程
- **工具调用**（ToolCallStart / ToolCallDelta / ToolCallComplete）：AI 要执行的操作
- **元数据**（StreamEnd）：token 用量、停止原因
- **错误**（Error）：异常情况

每种内容的处理方式不同：文本要拼接显示，思考要单独展示，工具调用要实际执行。

流式响应被拆成 **8 种事件**：

```java
public sealed interface StreamEvent {
    record TextDelta(String text) implements StreamEvent {}
    
    record ThinkingDelta(String text) implements StreamEvent {}
    
    record ThinkingComplete(
        String thinking, 
        String signature
    ) implements StreamEvent {}
    
    record ToolCallStart(
        String toolId, 
        String toolName
    ) implements StreamEvent {}
    
    record ToolCallDelta(String text) implements StreamEvent {}
    
    record ToolCallComplete(
        String toolId, 
        String toolName,
        Map<String, Object> arguments
    ) implements StreamEvent {}
    
    record StreamEnd(
        String stopReason,
        int inputTokens,
        int outputTokens,
        int cacheReadTokens,
        int cacheCreationTokens
    ) implements StreamEvent {
        // 向后兼容：三参数构造
        public StreamEnd(
            String stopReason,
            int inputTokens,
            int outputTokens
        ) {
            this(stopReason, inputTokens, outputTokens, 0, 0);
        }
    }
    
    record Error(String message) implements StreamEvent {}
}
```

**关键特性深度解析**：

#### 1. sealed interface（Java 17）—— 封闭的类型家族

`sealed` 关键字的作用是**限制谁可以实现这个接口**。在 StreamEvent 中，只有这 8 个 record 可以实现它，外部代码无法创建新的 StreamEvent 类型。

**为什么要封闭？**

想象如果 StreamEvent 是普通接口，任何人都可以写：
```java
public record MyCustomEvent(String data) implements StreamEvent {}
```

那么消费 StreamEvent 的代码就无法保证穷尽性：
```java
switch (event) {
    case TextDelta td -> ...
    case Error e -> ...
    // 如果有人加了 MyCustomEvent，这个 switch 就漏了
}
```

**sealed 接口的保证**：

编译器知道所有可能的子类型，所以能够在编译时检查 switch 语句是否覆盖了所有情况。如果遗漏了某个分支，会得到编译警告：

```
The switch statement does not cover all possible input values
```

这是**类型安全的终极形式**：不仅类型正确，而且逻辑完备。

#### 2. record（Java 16）—— 数据类的语法糖

每个 StreamEvent 子类型都是 record。record 是 Java 16 引入的特殊类，专门用于"只存数据，不包含复杂逻辑"的场景。

**一行 record 等价于 120 行传统代码**：

```java
record TextDelta(String text) implements StreamEvent {}
```

编译器自动生成：
- 构造函数：`public TextDelta(String text)`
- getter：`public String text()`（注意没有 `get` 前缀）
- equals：深度比较所有字段
- hashCode：基于所有字段
- toString：`TextDelta[text=...]`
- 所有字段都是 `private final`（不可变）

**为什么 getter 没有 get 前缀？**

传统 Java Bean：
```java
class Person {
    private String name;
    public String getName() { return name; }  // get 前缀
}
```

record 的设计哲学：
```java
record Person(String name) {}
person.name()  // 直接用字段名，更简洁
```

这不是随意的简化，而是有理论基础的：record 代表的是"数据"，不是"对象"。数据没有行为，只有属性。`person.name()` 读起来像"person 的 name"，比 `person.getName()` 更自然。

#### 3. 代数数据类型（Algebraic Data Type）

sealed interface + record 的组合，在类型理论中被称为"代数数据类型"，特别是"和类型"（Sum Type）。

**什么是和类型？**

StreamEvent 可以是以下之一：
```
StreamEvent = TextDelta OR ThinkingDelta OR ... OR Error
```

这是"和"（Sum），因为 StreamEvent 的可能性是 8 种类型的"加和"。

**对比积类型（Product Type）**：

每个 record 内部是"积类型"：
```java
record ToolCallComplete(String toolId, String toolName, Map<String, Object> arguments)
```

一个 ToolCallComplete 必须**同时拥有**这三个字段，这是"积"（Product），因为信息量是三个字段的"乘积"。

**实际好处**：

当你 switch 一个 StreamEvent 时：
```java
return switch (event) {
    case TextDelta(var text) -> handleText(text);        // 自动解构
    case ToolCallComplete(var id, var name, var args) -> // 模式匹配
        executeTool(name, args);
    // 编译器保证：要么列举所有 8 种，要么有 default
};
```

编译器能够：
1. **检查穷尽性**（是否覆盖所有类型）
2. **自动解构**（直接提取 record 的字段）
3. **类型安全**（每个分支中的变量类型是确定的）

这就是为什么 StreamEvent 的设计被称为"类型安全的终极形式"——它把运行时才能发现的错误，变成了编译时错误。

---

### Message：可变的消息容器

```java
public class Message {
    private String role;           // "user" 或 "assistant"
    private String content;        // 文本内容
    private List<ThinkingBlock> thinkingBlocks;
    private List<ToolUseBlock> toolUses;
    private List<ToolResultBlock> toolResults;
    
    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }
    
    // getter + setter ...
}
```

### Message：可变的消息容器

Message 类的设计与 StreamEvent 形成了鲜明对比：**StreamEvent 是不可变的（record），Message 是可变的（class）**。这不是随意的选择，而是基于它们不同的使用场景。

**为什么 Message 必须是可变的？**

AI 的回复是**逐步构建**的过程，不是一次性生成的：

1. **第一阶段**：收到文本片段
   ```java
   Message msg = new Message("assistant", "");
   // 收到 TextDelta("好")
   msg.setContent("好");
   // 收到 TextDelta("的")
   msg.setContent("好的");
   ```

2. **第二阶段**：收到思考块
   ```java
   // 收到 ThinkingComplete
   msg.setThinkingBlocks(List.of(new ThinkingBlock(...)));
   ```

3. **第三阶段**：收到工具调用
   ```java
   // 收到 ToolCallComplete
   msg.setToolUses(List.of(new ToolUseBlock(...)));
   ```

如果 Message 是不可变的 record，每次更新都要创建新对象：
```java
// 假设 Message 是 record（不可行）
record Message(String role, String content, List<ThinkingBlock> thinking, ...) {}

// 每次更新都要重建
Message msg1 = new Message("assistant", "", null, null);
Message msg2 = new Message("assistant", "好", null, null);  // 重建
Message msg3 = new Message("assistant", "好的", null, null);  // 再次重建
Message msg4 = new Message("assistant", "好的", thinkingBlocks, null);  // 又重建
```

这样做的问题：
1. **性能差**：频繁创建对象，GC 压力大
2. **代码复杂**：每次更新都要把所有字段重新传一遍
3. **引用失效**：外部持有的 Message 引用会指向旧对象

**class 的优势**：

```java
Message msg = new Message("assistant", "");
msg.setContent("好的");              // 原地修改
msg.setThinkingBlocks(blocks);      // 原地修改
msg.setToolUses(tools);             // 原地修改

// 外部持有的引用始终指向同一个对象
conversation.add(msg);  // msg 后续的修改会反映在 conversation 中
```

**为什么是 class 而不是 record？**

总结一下：

| 特性 | record | class |
|------|--------|-------|
| **可变性** | 不可变（所有字段 final） | 可变（字段可修改） |
| **创建方式** | 一次性传入所有字段 | 先创建再逐步填充 |
| **适用场景** | 值对象、事件、数据传输 | 实体对象、状态容器 |
| **StreamEvent** | ✅ 事件是瞬时的，不变的 | ❌ |
| **Message** | ❌ | ✅ 消息是可变的，逐步构建的 |

**封装的价值**：

虽然是可变的 class，但 Message 仍然使用 `private` 字段 + getter/setter 的封装方式：

```java
public class Message {
    private String content;  // private
    
    public String getContent() { return content; }
    
    public void setContent(String content) {
        this.content = content;  // 可以在这里做校验
    }
}
```

这样做的好处：
1. **可以加校验**：`setContent()` 中可以检查 content 不为 null
2. **可以加日志**：记录每次修改
3. **可以改实现**：未来可以改变内部表示而不影响调用方

这是经典的**面向对象封装原则**：即使是可变状态，也要通过方法访问，而不是直接暴露字段。

---

## 主流程走读

理解了核心类型之后，我们来看看它们是如何协作的。从 Agent 发起对话请求，到收到 AI 的流式响应，整个过程经历了 **5 个关键步骤**。这 5 个步骤跨越了 3 个层次：

1. **接口层**（LlmClient）：定义契约
2. **适配层**（LangChainClient）：协议转换
3. **传输层**（LangChain4j）：底层通信

每一层都有明确的职责边界，通过队列和回调进行解耦。

从 Agent 调用到底层事件生成，完整链路分 **5 步**。

---

### 第一步：Agent 调用工厂创建客户端

整个流程的起点在 `Agent.agentLoop()` 中。Agent 是对话的编排者，它需要一个 LlmClient 来与 AI 通信。

**创建客户端**：

**文件位置**：`src/main/java/com/mewcode/agent/Agent.java`

```java
// Agent.java
public class Agent {
    private final LlmClient client;
    
    // 构造函数：初始化 LlmClient
    public Agent(ProviderConfig providerCfg, String systemPrompt, ...) {
        // 调用工厂方法创建客户端
        this.client = LlmClient.create(providerCfg, systemPrompt);
        // ... 其他初始化
    }
    
    // 主循环方法
    private void agentLoop(ConversationManager conv, BlockingQueue<AgentEvent> queue) {
        // 每次迭代调用 stream()
        for (int iteration = 1; ; iteration++) {
            // 获取工具列表
            var tools = registry.getAllSchemas(protocol);
            
            // 调用 LLM（第 240 行）
            var streamQueue = client.stream(conv, tools);
            
            // 消费事件...
        }
    }
}
```

**调用链路**：
```
Agent 构造函数
    ↓
LlmClient.create(providerCfg, systemPrompt)
    ↓ 进入工厂方法
LlmClient.create() 静态方法
    ↓
new LangChainClient(providerCfg, systemPrompt)
    ↓
返回 LlmClient 接口引用
    ↓
Agent.client = ... （保存引用）
```

这个 `create()` 调用看起来简单，但它隐藏了很多细节：
- **配置解析**：从 `providerCfg` 中提取 protocol、model、apiKey 等
- **协议选择**：根据 protocol 决定使用哪个 LangChain4j 模型
- **客户端实例化**：创建 `LangChainClient` 并传入配置

**发起请求**：

在每次迭代中，Agent 调用 `stream()` 方法：

**文件位置**：`src/main/java/com/mewcode/agent/Agent.java:240`

```java
// Agent.java agentLoop() 方法内
private void agentLoop(ConversationManager conv, BlockingQueue<AgentEvent> queue) {
    for (int iteration = 1; ; iteration++) {
        // 准备工具列表
        var iterToolSchemas = registry.getAllSchemas(protocol);
        var tools = iterToolSchemas;
        
        // ⭐ 调用 LLM（第 240 行）
        var streamQueue = client.stream(conv, tools);
        
        // 消费事件
        var text = new StringBuilder();
        var thinkingBlocks = new ArrayList<ThinkingBlock>();
        var toolCalls = new ArrayList<ToolCallInfo>();
        
        while (true) {
            StreamEvent event = streamQueue.poll(30, TimeUnit.SECONDS);
            // ... 处理事件
        }
    }
}
```

**providerCfg 的来源**：

```java
// 配置文件：.mewcode/config.yaml
providers:
  - name: anthropic
    protocol: anthropic        # ← 决定创建哪个模型
    model: claude-opus-4-20250514
    apiKey: ${ANTHROPIC_API_KEY}
    
// 读取配置
ProviderConfig cfg = ConfigLoader.load(".mewcode/config.yaml");
    ↓
传给 Agent 构造函数
    ↓
Agent.Agent(cfg, systemPrompt)
    ↓
this.client = LlmClient.create(cfg, systemPrompt)
```

```java
// Agent.agentLoop() 中
var tools = registry.getAllSchemas(protocol);
var streamQueue = client.stream(conv, tools);
```

**client 从哪来？**

```java
// Agent 初始化时
this.client = LlmClient.create(providerCfg, systemPrompt);
```

**工厂方法**：

```java
static LlmClient create(ProviderConfig cfg, String systemPrompt) {
    return switch (cfg.getProtocol()) {
        case "anthropic", "openai", "openai-compat", "ollama" ->
                new LangChainClient(cfg, systemPrompt);
        default -> throw new IllegalArgumentException(...);
    };
}
```

**工厂方法深度解析**：

```java
static LlmClient create(ProviderConfig cfg, String systemPrompt) {
    return switch (cfg.getProtocol()) {
        case "anthropic", "openai", "openai-compat", "ollama" ->
                new LangChainClient(cfg, systemPrompt);
        default -> throw new IllegalArgumentException(
                "Unknown protocol: " + cfg.getProtocol()
        );
    };
}
```

这个工厂方法体现了几个重要的设计决策：

**1. 统一返回类型**

不管是 anthropic 还是 openai，返回的都是 `LlmClient` 接口类型。调用方永远不需要知道具体实现类是什么，这是**依赖倒置原则**的体现：
- 高层模块（Agent）依赖抽象（LlmClient 接口）
- 低层模块（LangChainClient）实现抽象
- 两者通过接口解耦

**2. 多协议统一实现**

注意 switch 语句中，4 个协议都返回同一个类：
```java
case "anthropic", "openai", "openai-compat", "ollama" ->
    new LangChainClient(cfg, systemPrompt);
```

这是架构重构的结果。在旧版本中，每个协议有独立的类（AnthropicClient / OpenAiClient），每个类都要手写 HTTP 请求和 SSE 解析。这导致了大量重复代码。

当前版本使用 LangChain4j 库，HTTP 通信的细节由库处理，我们只需要：
- 选择正确的 LangChain4j 模型（AnthropicStreamingChatModel / OpenAiStreamingChatModel）
- 转换消息格式（Message → ChatMessage）
- 适配回调（LangChain4j 回调 → StreamEvent）

这些工作都在 `LangChainClient` 内部完成，外部看来只有一个实现类。

**3. Fail-fast 原则**

如果传入未知的 protocol，直接抛出 `IllegalArgumentException`，而不是返回 null 或默认实现。这是 **fail-fast** 原则：
- **早暴露问题**：启动时就发现配置错误，而不是运行到一半才报错
- **清晰的错误信息**：明确告诉用户"Unknown protocol: xxx"
- **防止静默失败**：不会因为错误配置而继续执行，产生不可预测的行为

**关键点**：
- ✅ **所有协议统一实现**（不再是多个类）
- ✅ **返回接口类型**（调用方不知道具体实现）
- ✅ **启动时校验**（而不是运行时才发现配置错误）

---

### 第二步：LangChainClient.stream() 启动虚拟线程

这是整个流程中最关键的一步，它决定了系统的**异步模型**和**并发能力**。

**方法位置**：`src/main/java/com/mewcode/llm/LangChainClient.java`

**调用方**：`Agent.agentLoop()`

**方法签名**：
```java
// LangChainClient.java
public class LangChainClient implements LlmClient {
    
    @Override
    public BlockingQueue<StreamEvent> stream(
        ConversationManager conv,
        List<Map<String, Object>> tools
    ) {
        var queue = new LinkedBlockingQueue<StreamEvent>(64);
        
        Thread.ofVirtual()
                .name("minicode-model-stream")
                .start(() -> {
                    try {
                        doStream(conv, tools, queue);
                    } catch (Exception e) {
                        put(queue, new StreamEvent.Error(
                            classify(e)
                        ));
                    }
                });
        
        return queue;
    }
    
    // 私有方法：实际执行流式请求
    private void doStream(
        ConversationManager conv,
        List<Map<String, Object>> tools,
        BlockingQueue<StreamEvent> queue
    ) {
        // ... 见第三步
    }
    
    // 私有方法：分类异常
    private static String classify(Throwable failure) {
        // ... 见错误分类章节
    }
    
    // 私有方法：安全地放入队列
    private static void put(BlockingQueue<StreamEvent> queue, StreamEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

**调用链路**：
```
Agent.agentLoop()
    ↓ 第 240 行
var streamQueue = client.stream(conv, tools);
    ↓
LangChainClient.stream(conv, tools)  ← 这里
    ↓ 创建队列
var queue = new LinkedBlockingQueue<StreamEvent>(64)
    ↓ 启动虚拟线程
Thread.ofVirtual().start(() -> doStream(...))
    ↓ 立即返回
return queue
```

这个方法虽然短小，但每一行都有深意。让我们逐一分析：

**设计解析**：

#### 1. 立即返回队列 —— 非阻塞的关键

```java
var queue = new LinkedBlockingQueue<StreamEvent>(64);
// ... 启动虚拟线程 ...
return queue;  // 立即返回，不等待网络请求
```

`stream()` 方法的执行时间是 **< 1 毫秒**。它不等待 HTTP 请求完成，不等待 AI 开始回复，甚至不等待连接建立。它只做三件事：
1. 创建队列
2. 启动后台线程
3. 返回队列

这是**异步编程的黄金法则**：让调用方立即拿到"未来的结果"（队列），而不是阻塞等待"当前的结果"。

**对比同步方式**：
```java
// 假设是同步实现（不可取）
public List<StreamEvent> streamSync(...) {
    var events = new ArrayList<StreamEvent>();
    
    // 发送 HTTP 请求
    HttpResponse response = httpClient.send(request);  // 阻塞 100ms
    
    // 解析 SSE 流
    while (...) {
        String line = reader.readLine();  // 阻塞，可能等待几秒
        StreamEvent event = parse(line);
        events.add(event);
    }
    
    return events;  // 10 秒后才返回
}

// 调用方必须等待
List<StreamEvent> events = client.streamSync(...);  // 阻塞 10 秒
```

同步方式的问题：
- ❌ **调用线程阻塞**：Agent 的线程在这里卡住 10 秒
- ❌ **无法实时显示**：必须等所有事件收集完才能返回
- ❌ **无法取消**：一旦调用就必须等到结束

**异步方式的优势**：
```java
// 调用方立即返回
BlockingQueue<StreamEvent> queue = client.stream(...);  // < 1ms

// 后台线程慢慢处理
// 主线程可以继续做其他事，或者立即开始消费队列
```

- ✅ **调用线程不阻塞**：Agent 立即拿到队列，可以继续执行
- ✅ **实时显示**：边生成边消费，用户立即看到第一个字
- ✅ **可以取消**：停止消费队列即可（虚拟线程会自然结束）

#### 2. 虚拟线程（Java 21）—— 高并发的基石

```java
Thread.ofVirtual()
        .name("minicode-model-stream")
        .start(() -> {
            doStream(conv, tools, queue);
        });
```

这里使用的是 **虚拟线程**（Virtual Thread），而不是传统的平台线程（Platform Thread）。这是 Java 21 最重要的特性之一。

**为什么需要虚拟线程？**

想象一个场景：1000 个用户同时在使用 MiniCode，每个用户都在等待 AI 回复。

**如果用平台线程**：
```java
// 每个对话一个平台线程
Thread.ofPlatform().start(() -> doStream(...));
```

问题：
- **内存占用**：每个平台线程占用 1-2 MB 栈内存
  - 1000 个对话 = 1000 个线程 = 1-2 GB 内存
- **OS 线程限制**：操作系统能创建的线程数有限（通常几千到一万）
- **上下文切换**：大量线程导致频繁的上下文切换，性能下降
- **阻塞成本高**：线程在 `readLine()` 阻塞时，OS 线程也被占用

**虚拟线程的魔法**：
```java
Thread.ofVirtual().start(() -> doStream(...));
```

虚拟线程是 JVM 管理的轻量级线程，不是 OS 线程。关键特性：

1. **轻量级**：
   - 每个虚拟线程只占用几 KB 内存
   - 1000 个对话 = 1000 个虚拟线程 = 几 MB 内存
   - 可以创建数十万个虚拟线程

2. **自动调度**：
   - 虚拟线程运行在少量的 OS 线程（carrier thread）上
   - 当虚拟线程阻塞时（如 `readLine()`），自动让出 OS 线程给其他虚拟线程
   - JVM 自动管理调度，无需程序员干预

3. **阻塞友好**：
   - 虚拟线程可以随意阻塞，不会浪费 OS 线程
   - `doStream()` 中的 `readLine()` 可能阻塞几秒，但不影响其他虚拟线程

**实际运行模型**：
```
1000 个虚拟线程  →  运行在  →  10 个 OS 线程（carrier threads）

虚拟线程 1: readLine() 阻塞 → 让出 OS 线程
虚拟线程 2: 继续执行 → 使用被让出的 OS 线程
虚拟线程 3: readLine() 阻塞 → 让出 OS 线程
虚拟线程 4: 继续执行 → 使用被让出的 OS 线程
...

当虚拟线程 1 的数据到达：
虚拟线程 1: 恢复执行 → 分配一个空闲的 OS 线程
```

这是 **M:N 调度模型**：M 个虚拟线程映射到 N 个 OS 线程（M >> N）。

**为什么这对 LLM 通信特别重要？**

LLM 的流式响应有个特点：**大量时间在等待**。
- 等待 HTTP 连接建立（100ms）
- 等待第一个 token（500ms - 2s）
- 等待每个后续 token（50ms - 200ms）

如果用平台线程，这些等待时间会浪费宝贵的 OS 线程。虚拟线程让"等待"变得廉价，可以支持上千个并发对话而不占用过多资源。

#### 3. 队列容量 64 —— 生产者消费者的缓冲区

```java
var queue = new LinkedBlockingQueue<StreamEvent>(64);
```

为什么容量是 64？这是**经验值**，平衡了内存占用和阻塞频率。

**容量太小（如 4）的问题**：
```
生产者（后台线程）：快速生成事件
TextDelta("好") → 入队
TextDelta("的") → 入队
TextDelta("，") → 入队
TextDelta("我") → 入队
TextDelta("来") → 队列满，阻塞！⏸️

消费者（Agent）：慢慢处理
处理 "好" （耗时 10ms）
处理 "的" （耗时 10ms）
```

生产者频繁阻塞，网络带宽浪费（虽然服务器在推送，但客户端队列满了，无法接收）。

**容量太大（如 10000）的问题**：
```
生产者：疯狂生成
10000 个事件全部入队

消费者：慢慢消费
处理第 1 个事件
处理第 2 个事件
...
处理第 10000 个事件（要等很久）
```

问题：
- **内存占用高**：10000 个 StreamEvent 对象占用大量内存
- **延迟高**：最后一个事件要等很久才被处理
- **实时性差**：用户看不到最新的进展

**容量 64 的优势**：
```
生产者：持续生成（很少阻塞）
队列：保持在 20-40 个事件（有余量）
消费者：持续消费（很少等待）

内存：64 * 平均事件大小 ≈ 几百 KB（可接受）
延迟：最多 64 个事件的处理时间 ≈ 几百毫秒（可接受）
```

这个值是**经验公式**的结果：
```
理想容量 ≈ (生产速度 - 消费速度) × 峰值持续时间

假设：
- AI 生成速度：50 token/s = 50 events/s
- 显示速度：40 events/s
- 峰值差异：10 events/s
- 缓冲时间：5 秒

容量 = 10 × 5 = 50 ≈ 64（取 2 的幂，性能更好）
```

#### 4. 异常兜底 —— 最后一道防线

```java
try {
    doStream(conv, tools, queue);
} catch (Exception e) {
    put(queue, new StreamEvent.Error(classify(e)));
}
```

这个 try-catch 非常关键，它是**系统稳定性的保证**。

**为什么需要兜底？**

`doStream()` 内部会做很多事情：
- 构建 LangChain4j 模型（可能抛 IllegalArgumentException）
- 发送 HTTP 请求（可能抛 IOException）
- 解析 JSON（可能抛 JsonProcessingException）
- 调用回调（回调内部可能抛任何异常）

如果没有这个 catch，任何未预料的异常都会导致虚拟线程崩溃，队列不再有新事件，消费方永远阻塞在 `queue.take()`。

**兜底的效果**：
```java
// 假设 HTTP 请求失败
doStream() → 抛出 IOException("Connection refused")
    ↓
catch 捕获
    ↓
classify(IOException) → "无法连接到服务器"
    ↓
put(queue, new StreamEvent.Error("无法连接到服务器"))
    ↓
消费方收到 Error 事件
    ↓
显示错误给用户，而不是程序卡死
```

所有异常都转换成 `StreamEvent.Error`，通过同一个队列传递。这保证了：
- ✅ **消费方不会卡死**：总能收到一个结束信号（Error 或 StreamEnd）
- ✅ **错误信息清晰**：classify() 把底层异常转换成用户友好的消息
- ✅ **系统优雅降级**：错误不会导致整个系统崩溃

---

### 第三步：doStream() 构建请求并调用 LangChain4j

`stream()` 方法启动虚拟线程后，实际工作在 `doStream()` 中完成。这个方法是 LLM 层的核心，负责协议转换和事件适配。

**完整调用链**：

```
LangChainClient.stream()
    ↓
Thread.ofVirtual().start(() -> {
    doStream(conv, tools, queue)  ← 在虚拟线程中执行
})
    ↓
doStream() {
    1. buildModel()           ← 创建 LangChain4j 模型
    2. toMessages()           ← 转换消息格式
    3. toToolSpecs()          ← 转换工具格式
    4. model.chat()           ← 调用 LangChain4j
}
```

**doStream() 源码结构**：

```java
// LangChainClient.java
private void doStream(
    ConversationManager conv,
    List<Map<String, Object>> tools,
    BlockingQueue<StreamEvent> queue
) {
    // 1. 创建 TokenSnapshot（用于跟踪 token 用量）
    TokenSnapshot tokenSnapshot = new TokenSnapshot();
    
    // 2. 构建 LangChain4j 模型
    StreamingChatModel model = buildModel(tokenSnapshot);
    
    // 3. 转换消息格式：Message → ChatMessage
    List<ChatMessage> messages = toMessages(
        conv.getMessages(),
        systemPrompt
    );
    
    // 4. 转换工具格式：Map → ToolSpecification
    List<ToolSpecification> toolSpecs = toToolSpecs(tools);
    
    // 5. 构建请求对象
    ChatRequest request = ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(toolSpecs)
            .build();
    
    // 6. 调用 LangChain4j，传入回调处理器
    model.chat(request, handler(queue));
}
```

这个方法的每一步都有明确的职责：

---

#### 3.1 buildModel()：根据协议创建 LangChain4j 模型

**调用位置**：
```java
// doStream() 第一步
StreamingChatModel model = buildModel(tokenSnapshot);
```

**方法实现**：

```java
// LangChainClient.java
private StreamingChatModel buildModel(TokenSnapshot snapshot) {
    return switch (config.getProtocol()) {
        case "anthropic" -> AnthropicStreamingChatModel.builder()
                .apiKey(config.resolvedApiKey())
                .modelName(config.getModel())
                .maxTokens(maxOutputTokens)
                .logRequests(true)
                .logResponses(true)
                .build();
                
        case "openai" -> OpenAiStreamingChatModel.builder()
                .apiKey(config.resolvedApiKey())
                .modelName(config.getModel())
                .maxTokens(maxOutputTokens)
                .logRequests(true)
                .logResponses(true)
                .build();
                
        case "ollama" -> OllamaStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModel())
                .numPredict(maxOutputTokens)
                .build();
                
        default -> throw new IllegalArgumentException(
            "Unknown protocol: " + config.getProtocol()
        );
    };
}
```

**这个方法的作用**：

根据配置中的 `protocol` 字段，创建对应的 LangChain4j 模型实例：
- `"anthropic"` → `AnthropicStreamingChatModel`
- `"openai"` → `OpenAiStreamingChatModel`
- `"ollama"` → `OllamaStreamingChatModel`

所有模型都实现 `StreamingChatModel` 接口，因此返回类型统一。

**配置来源**：

```java
// config.getProtocol() 从哪里来？
Agent 构造函数
    ↓
LlmClient.create(providerCfg, systemPrompt)
    ↓
new LangChainClient(providerCfg, systemPrompt)
    ↓
this.config = providerCfg  // 保存配置
    ↓
buildModel() 读取 config.getProtocol()
```

**配置文件示例**：

```yaml
# .mewcode/config.yaml
providers:
  - name: anthropic
    protocol: anthropic      # ← 这里的值决定创建哪个模型
    model: claude-opus-4-20250514
    apiKey: ${ANTHROPIC_API_KEY}
```

---

#### 3.2 toMessages()：内部 Message → LangChain4j ChatMessage

**调用位置**：
```java
// doStream() 第二步
List<ChatMessage> messages = toMessages(
    conv.getMessages(),
    systemPrompt
);
```

**conv.getMessages() 的来源**：

```java
// Agent.agentLoop() 中
ConversationManager conv = ...;

// 用户输入后
conv.addUserMessage("帮我创建 hello.txt");

// AI 回复后
conv.addAssistantFull(text, thinkingBlocks, toolUses);

// 工具执行后
conv.addToolResults(results);

// 现在 conv.getMessages() 返回完整的对话历史
```

**转换逻辑**：

```java
// LangChainClient.java:165-187
// 位置：LangChainClient.java 私有方法
// 被调用于：doStream() 方法中准备 API 请求前
// 作用：将 MiniCode 内部的 Message 列表转换为 LangChain4j 的 ChatMessage 列表
private static List<ChatMessage> toMessages(
    List<Message> source,        // MiniCode 内部格式的对话历史
    String systemPrompt          // 系统提示词
) {
    List<ChatMessage> out = new ArrayList<>();
    
    // 1. 添加系统消息（如果有）
    if (!systemPrompt.isBlank()) {
        out.add(SystemMessage.from(systemPrompt));
    }
    
    // 2. 构建工具名称映射表
    // 为什么需要：ToolExecutionResultMessage 需要同时提供 toolUseId 和 toolName
    // 但 ToolResultBlock 只存储了 toolUseId，所以先收集映射关系
    Map<String, String> toolNames = new HashMap<>();
    for (Message m : source) {
        if (m.getToolUses() != null) {
            m.getToolUses().forEach(t -> 
                toolNames.put(t.toolUseId(), t.toolName())
            );
        }
    }
    
    // 3. 遍历对话历史，逐条转换消息
    for (Message m : source) {
        // 3.1 处理工具结果消息（优先级最高）
        if (m.getToolResults() != null && !m.getToolResults().isEmpty()) {
            for (var r : m.getToolResults()) {
                out.add(ToolExecutionResultMessage.builder()
                    .id(r.toolUseId())
                    .toolName(toolNames.getOrDefault(r.toolUseId(), "tool"))
                    .text(r.content())
                    .isError(r.isError())
                    .build());
            }
            
        // 3.2 处理 AI 助手消息
        } else if ("assistant".equals(m.getRole())) {
            var builder = AiMessage.builder()
                .text(m.getContent() == null ? "" : m.getContent());
            
            // ⚠️ 关键过滤：thinking 块不能发送回 API
            // Anthropic API 的限制：
            //   - 响应中可以包含 thinking 块（Extended Thinking 功能）
            //   - 请求中不能包含 thinking 块（会报错）
            // thinking 块仅用于显示给用户，维护对话历史时必须过滤掉
            
            // 添加工具调用请求（如果有）
            if (m.getToolUses() != null) {
                builder.toolExecutionRequests(
                    m.getToolUses().stream()
                        .map(t -> ToolExecutionRequest.builder()
                            .id(t.toolUseId())
                            .name(t.toolName())
                            .arguments(writeJson(t.arguments()))
                            .build())
                        .toList()
                );
            }
            
            out.add(builder.build());
            
        // 3.3 处理用户消息（默认情况）
        } else {
            out.add(UserMessage.from(
                m.getContent() == null ? "" : m.getContent()
            ));
        }
    }
    
    return out;
}
```

**为什么这样设计**：

1. **工具名称映射表的必要性**：
   - LangChain4j 的 `ToolExecutionResultMessage` 需要 `toolName`
   - 但我们的 `ToolResultBlock` 只有 `toolUseId`
   - 必须从前面的 `ToolUseBlock` 中查找对应的工具名称

2. **thinking 块的特殊处理**：
   - Anthropic API 在响应中返回 thinking 块（用户可见）
   - 但在后续请求中不能包含 thinking 块（API 限制）
   - 如果发送回去会导致 `400 Bad Request`

3. **消息顺序的重要性**：
   - 系统消息必须在最前面
   - 工具结果必须紧跟在对应的工具调用之后
   - LangChain4j 会验证消息顺序的合法性

**转换示例**：

```java
// 输入：MiniCode 内部格式
List<Message> source = [
    Message(
        role="user", 
        content="创建 hello.txt"
    ),
    Message(
        role="assistant", 
        content="好的，我来创建文件", 
        toolUses=[
            ToolUseBlock(
                id="tool_1", 
                name="WriteFile",
                arguments={"path": "hello.txt", "content": "Hello"}
            )
        ],
        thinkingBlocks=[
            ThinkingBlock(thinking="用户想创建文件，我应该调用 WriteFile")
        ]
    ),
    Message(
        role="user",
        toolResults=[
            ToolResultBlock(
                toolUseId="tool_1",
                content="文件创建成功",
                isError=false
            )
        ]
    )
];

// 输出：LangChain4j 格式
List<ChatMessage> result = [
    SystemMessage("You are a helpful assistant"),
    
    UserMessage("创建 hello.txt"),
    
    AiMessage(
        text="好的，我来创建文件",
        toolExecutionRequests=[
            ToolExecutionRequest(
                id="tool_1", 
                name="WriteFile",
                arguments="{\"path\":\"hello.txt\",\"content\":\"Hello\"}"
            )
        ]
        // 注意：thinkingBlocks 被过滤掉了！
    ),
    
    ToolExecutionResultMessage(
        id="tool_1",
        toolName="WriteFile",  // 从前面的映射表中查找
        text="文件创建成功",
        isError=false
    )
];
```

**调用链**：

```
Agent.agentLoop()
    ↓
conversation.addUserMessage("创建文件")
    ↓
client.stream(conversation, tools)
    ↓
doStream(conversation, tools, queue)
    ↓
toMessages(conversation.getMessages(), systemPrompt)
    ↓
返回 List<ChatMessage>
```

---

#### 3.3 toToolSpecs()：工具 Map → LangChain4j ToolSpecification

**调用位置**：
```java
// doStream() 第三步
List<ToolSpecification> toolSpecs = toToolSpecs(tools);
```

**tools 参数的来源**：

```java
// Agent.agentLoop() 中
var tools = registry.getAllSchemas(protocol);
    ↓
client.stream(conv, tools)  // 传给 stream()
    ↓
doStream(conv, tools, queue)  // 传给 doStream()
    ↓
toToolSpecs(tools)  // 转换格式
```

**registry.getAllSchemas() 返回什么**：

```java
// ToolRegistry.java
public List<Map<String, Object>> getAllSchemas(String protocol) {
    var result = new ArrayList<Map<String, Object>>();
    
    for (Tool tool : allTools) {
        Map<String, Object> schema = Map.of(
            "name", tool.name(),
            "description", tool.description(),
            "input_schema", tool.inputSchema()
        );
        result.add(schema);
    }
    
    return result;
}
```

**转换逻辑**：

```java
// LangChainClient.java
private static List<ToolSpecification> toToolSpecs(
    List<Map<String, Object>> tools
) {
    var result = new ArrayList<ToolSpecification>();
    
    for (var tool : tools) {
        String name = (String) tool.get("name");
        String desc = (String) tool.get("description");
        var inputSchema = (Map<String, Object>) tool.get("input_schema");
        
        result.add(ToolSpecification.builder()
                .name(name)
                .description(desc)
                .parameters(ToolParameters.builder()
                        .properties(inputSchema)
                        .build())
                .build());
    }
    
    return result;
}
```

**转换示例**：

```java
// 输入：MiniCode 格式
List<Map<String, Object>> tools = [
    {
        "name": "WriteFile",
        "description": "Write content to a file",
        "input_schema": {
            "type": "object",
            "properties": {
                "file_path": {"type": "string"},
                "content": {"type": "string"}
            }
        }
    }
];

// 输出：LangChain4j 格式
List<ToolSpecification> toolSpecs = [
    ToolSpecification(
        name="WriteFile",
        description="Write content to a file",
        parameters=ToolParameters(
            properties={
                "file_path": {"type": "string"},
                "content": {"type": "string"}
            }
        )
    )
];
```

**完整的工具流转链**：

```
Tool 定义（Java 类）
    ↓
ToolRegistry.register(tool)
    ↓
ToolRegistry.getAllSchemas(protocol)
    ↓ 返回 List<Map<String, Object>>
Agent.agentLoop() 获取工具列表
    ↓
client.stream(conv, tools)
    ↓
doStream(conv, tools, queue)
    ↓
toToolSpecs(tools)
    ↓ 转换成 List<ToolSpecification>
model.chat(request, handler)
    ↓ 发送给 LangChain4j
LangChain4j 发送给 LLM API
```

---

### 第四步：回调适配器 - 关键转换点

这是 LLM 层最关键的转换点，将 LangChain4j 的回调事件转换成内部的 StreamEvent。

**方法位置**：`src/main/java/com/mewcode/llm/LangChainClient.java`

**调用方**：`doStream()` 方法

**调用位置**：
```java
// LangChainClient.java doStream() 方法内
private void doStream(...) {
    StreamingChatModel model = buildModel(snapshot);
    List<ChatMessage> messages = toMessages(...);
    List<ToolSpecification> toolSpecs = toToolSpecs(tools);
    
    ChatRequest request = ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(toolSpecs)
            .build();
    
    // ⭐ 调用 handler() 创建回调适配器
    model.chat(request, handler(queue));  // 传入队列
}
```

**完整的 handler() 方法实现**：

```java
// LangChainClient.java
public class LangChainClient implements LlmClient {
    
    // 私有方法：创建回调适配器
    private StreamingChatResponseHandler handler(
        BlockingQueue<StreamEvent> queue
    ) {
        // 返回匿名内部类，实现 LangChain4j 的回调接口
        return new StreamingChatResponseHandler() {
            
            @Override
            public void onPartialResponse(String text) {
                // LangChain4j 回调：收到文本片段
                put(queue, new StreamEvent.TextDelta(text));
            }
            
            @Override
            public void onPartialThinking(PartialThinking thinking) {
                // LangChain4j 回调：收到思考片段
                put(queue, new StreamEvent.ThinkingDelta(
                    thinking.content()
                ));
            }
            
            @Override
            public void onCompleteThinking(CompleteThinking thinking) {
                // LangChain4j 回调：思考完成
                put(queue, new StreamEvent.ThinkingComplete(
                    thinking.content(),
                    thinking.signature()
                ));
            }
            
            @Override
            public void onPartialToolCall(PartialToolCall partial) {
                // LangChain4j 回调：工具调用开始
                if (partial.name() != null) {
                    put(queue, new StreamEvent.ToolCallStart(
                        partial.id(),
                        partial.name()
                    ));
                }
            }
            
            @Override
            public void onCompleteToolCall(CompleteToolCall complete) {
                // LangChain4j 回调：工具调用完成
                Map<String, Object> args = parseJson(
                    complete.arguments()
                );
                put(queue, new StreamEvent.ToolCallComplete(
                    complete.id(),
                    complete.name(),
                    args
                ));
            }
            
            @Override
            public void onCompleteResponse(AiMessage response) {
                // LangChain4j 回调：流结束
                TokenUsage usage = response.tokenUsage();
                put(queue, new StreamEvent.StreamEnd(
                    "end_turn",
                    usage.inputTokenCount(),
                    usage.outputTokenCount(),
                    usage.cacheReadTokens(),
                    usage.cacheWriteTokens()
                ));
            }
            
            @Override
            public void onError(Throwable error) {
                // LangChain4j 回调：发生错误
                put(queue, new StreamEvent.Error(
                    classify(error)
                ));
            }
        };
    }
    
    // 辅助方法：安全地放入队列
    private static void put(BlockingQueue<StreamEvent> queue, StreamEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    // 辅助方法：解析 JSON 字符串为 Map
    private static Map<String, Object> parseJson(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
    
    // 辅助方法：分类异常
    private static String classify(Throwable failure) {
        String message = failure == null
                ? "Unknown provider error"
                : String.valueOf(failure.getMessage());
        return message.length() > 500
                ? message.substring(0, 500)
                : message;
    }
}
```

**这是核心转换点**：

```
LangChain4j 底层通信
    ↓ 收到 SSE 事件
LangChain4j 解析
    ↓ 触发回调
StreamingChatResponseHandler.onPartialResponse("好")
    ↓ 进入 handler() 返回的匿名类
put(queue, new StreamEvent.TextDelta("好"))
    ↓ 放入队列
BlockingQueue<StreamEvent>
    ↓ Agent 消费
Agent.agentLoop() 收到 TextDelta("好")
```

**这是核心转换点**：

```
LangChain4j 回调事件
    ↓
StreamingChatResponseHandler
    ↓
StreamEvent
    ↓
BlockingQueue<StreamEvent>
    ↓
Agent 消费
```

**回调映射表**：

| LangChain4j 回调 | StreamEvent |
|-----------------|-------------|
| `onPartialResponse(text)` | `TextDelta(text)` |
| `onPartialThinking(thinking)` | `ThinkingDelta(content)` |
| `onCompleteThinking(thinking)` | `ThinkingComplete(content, sig)` |
| `onPartialToolCall(partial)` | `ToolCallStart(id, name)` |
| `onCompleteToolCall(complete)` | `ToolCallComplete(id, name, args)` |
| `onCompleteResponse(response)` | `StreamEnd(...)` |
| `onError(error)` | `Error(message)` |

---

### 第五步：Agent 消费 StreamEvent

```java
// Agent.agentLoop()
var streamQueue = client.stream(conv, tools);

// 消费事件
var text = new StringBuilder();
var thinkingBlocks = new ArrayList<ThinkingBlock>();
var toolCalls = new ArrayList<ToolCallInfo>();

while (true) {
    StreamEvent event = streamQueue.poll(30, TimeUnit.SECONDS);
    
    if (event == null) {
        // 超时
        break;
    }
    
    switch (event) {
        case StreamEvent.TextDelta td -> {
            // 文本片段
            text.append(td.text());
            putSafe(queue, new AgentEvent.StreamText(td.text()));
        }
        
        case StreamEvent.ThinkingDelta td -> {
            // 思考片段
            putSafe(queue, new AgentEvent.ThinkingText(td.text()));
        }
        
        case StreamEvent.ThinkingComplete tc -> {
            // 思考完成
            thinkingBlocks.add(new ThinkingBlock(
                tc.thinking(),
                tc.signature()
            ));
            putSafe(queue, new AgentEvent.ThinkingComplete(
                tc.thinking(),
                tc.signature()
            ));
        }
        
        case StreamEvent.ToolCallStart tcs -> {
            // 工具调用开始
            putSafe(queue, new AgentEvent.ToolUseEvent(
                tcs.toolId(),
                tcs.toolName(),
                Map.of()
            ));
        }
        
        case StreamEvent.ToolCallComplete tcc -> {
            // 工具调用完成
            toolCalls.add(new ToolCallInfo(
                tcc.toolId(),
                tcc.toolName(),
                tcc.arguments()
            ));
        }
        
        case StreamEvent.StreamEnd se -> {
            // 流结束
            stopReason = se.stopReason();
            inputTokens = se.inputTokens();
            outputTokens = se.outputTokens();
            break;
        }
        
        case StreamEvent.Error e -> {
            // 错误
            putSafe(queue, new AgentEvent.ErrorEvent(e.message()));
            break;
        }
    }
}

// 如果有工具调用，执行工具
if (!toolCalls.isEmpty()) {
    var results = executor.executeToolCalls(toolCalls);
    // 添加到对话历史
    conv.addAssistantFull(text.toString(), thinkingBlocks, toolUses);
    conv.addToolResults(results);
    // 继续下一轮迭代
} else {
    // 没有工具调用，结束
    putSafe(queue, new AgentEvent.TurnComplete());
}
```

---

## 完整的数据流

```
┌─────────────────────────────────────────────────────────┐
│ 1. Agent.agentLoop()                                    │
│    client.stream(conv, tools)                           │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ 2. LlmClient.create(cfg, systemPrompt)                  │
│    → new LangChainClient(cfg, systemPrompt)             │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ 3. LangChainClient.stream()                             │
│    - 创建 LinkedBlockingQueue(64)                       │
│    - 启动虚拟线程                                        │
│    - 立即返回 queue                                      │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ 4. 虚拟线程执行 doStream()                               │
│    - buildModel() → StreamingChatModel                  │
│    - toMessages() → List<ChatMessage>                   │
│    - toToolSpecs() → List<ToolSpecification>            │
│    - model.chat(request, handler(queue))                │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ 5. LangChain4j 底层通信                                 │
│    - 发送 HTTP 请求                                      │
│    - 解析 SSE 流                                         │
│    - 触发回调                                            │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ 6. StreamingChatResponseHandler 回调                    │
│    onPartialResponse() → TextDelta                      │
│    onPartialThinking() → ThinkingDelta                  │
│    onCompleteToolCall() → ToolCallComplete              │
│    onCompleteResponse() → StreamEnd                     │
│    onError() → Error                                    │
│    ↓                                                     │
│    queue.put(StreamEvent)                               │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ 7. Agent 消费 StreamEvent                                │
│    streamQueue.poll()                                    │
│    ↓                                                     │
│    switch (event) {                                      │
│        TextDelta → 累积文本 + 转 AgentEvent             │
│        ToolCallComplete → 记录工具调用                   │
│        StreamEnd → 结束循环                              │
│    }                                                     │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ 8. 执行工具（如果有）                                    │
│    executor.executeToolCalls(toolCalls)                 │
│    ↓                                                     │
│    返回 ToolResultBlock                                  │
│    ↓                                                     │
│    添加到 ConversationManager                            │
│    ↓                                                     │
│    继续下一轮迭代（让 AI 看到工具结果）                  │
└─────────────────────────────────────────────────────────┘
```

---

## 三层事件转换

### **为什么需要三层？**

```
Layer 1: LangChain4j 回调事件
    ↓ 转换
Layer 2: StreamEvent（LLM 层抽象）
    ↓ 转换
Layer 3: AgentEvent（Agent 层抽象）
    ↓ 渲染
TUI / Remote / Print
```

**职责分离**：

| 层级 | 类型 | 职责 | 关注点 |
|------|------|------|--------|
| **LangChain4j** | 回调接口 | 底层通信 | 如何从 API 获取数据 |
| **StreamEvent** | LLM 层 | 协议抽象 | AI 响应的标准化表示 |
| **AgentEvent** | Agent 层 | 业务逻辑 | 工具执行、权限请求 |
| **UI** | 渲染层 | 用户界面 | 如何显示给用户 |

**解耦的好处**：

1. **新增 LLM 提供商**
   - 只需修改 `buildModel()`
   - Agent 和 UI 完全不需要改

2. **切换底层库**
   - 从 LangChain4j 切换到其他库
   - 只需修改回调适配器
   - StreamEvent 接口不变

3. **新增 UI 类型**
   - 新增 Web UI / Mobile UI
   - 只需消费 AgentEvent
   - Agent 和 LLM 层不需要改

---

## 错误分类 

当前项目已实现完整的类型化错误处理系统，包括异常分类、流式事件传递和 Agent 自动恢复策略。

**文件位置**：`src/main/java/com/mewcode/llm/LlmException.java`

**核心方法**：`public static LlmException classify(Throwable failure)`

**调用位置**：
1. `LangChainClient.java:116` - LangChain4j 回调错误时调用
2. `LangChainClient.java:239` - doStream() 异常兜底时调用  
3. `Agent.java:304` - 收到 StreamEvent.Error 后提取异常对象

**调用链路**：
```
LangChain4j 底层通信
    ↓ 发生错误（网络/API/超时）
LangChain4j 抛出异常
    ↓
LangChainClient.onError(Throwable error)  (第116行)
    ↓
LlmException.classify(error)  ← 在这里分类异常
    ↓
返回分类后的异常：
  - AuthenticationException (401/403)
  - RateLimitException (429)
  - ContextTooLongException (413/文本匹配)
  - NetworkException (IO错误)
  - LlmException (其他)
    ↓
put(queue, new StreamEvent.Error(分类后的异常))
    ↓
Agent.agentLoop() 收到 StreamEvent.Error  (第304行)
    ↓
var error = err.exception();  // 获取类型化异常
    ↓
根据异常类型执行不同策略：
  - ContextTooLongException → 压缩上下文重试
  - RateLimitException → 等待后重试
  - AuthenticationException → 不重试，报错
```

### 异常体系定义

```java
public class LlmException extends RuntimeException {
    // 1. 认证错误 (401/403)
    public static class AuthenticationException 
            extends LlmException {}
    
    // 2. 限流错误 (429，带 Retry-After)
    public static class RateLimitException 
            extends LlmException {
        private final String retryAfter;
        public String getRetryAfter() { return retryAfter; }
    }
    
    // 3. 上下文过长 (413 或文本匹配)
    public static class ContextTooLongException 
            extends LlmException {}
    
    // 4. 网络错误 (IOException/超时)
    public static class NetworkException 
            extends LlmException {}
}
```

### 智能错误分类

`LlmException.classify()` 方法自动将底层异常转换为语义化类型。这个方法按照**优先级顺序**检查异常，一旦匹配就返回分类结果。

**完整实现**：

```java
// LlmException.java
public static LlmException classify(Throwable failure) {
    // ========== 步骤 0: null 检查 ==========
    if (failure == null) {
        return new LlmException("Unknown LLM error");
    }
    // 含义：如果传入的异常为 null，返回一个通用错误
    // 场景：防御性编程，避免空指针异常
    
    
    // ========== 步骤 1: 已经是 LlmException，直接返回 ==========
    LlmException existing = findCause(failure, LlmException.class);
    if (existing != null) {
        return existing;
    }
    // 含义：如果异常链中已经包含 LlmException（或其子类），直接返回它
    // 场景：避免重复分类，保留原始的类型信息
    // 例子：
    //   原始异常：RateLimitException("Rate limited")
    //   被包装成：CompletionException(cause=RateLimitException)
    //   findCause() 找到内部的 RateLimitException，直接返回
    
    
    // ========== 步骤 2: 识别 LangChain4j 语义异常类型 ==========
    
    // 2.1 认证异常
    if (hasCause(failure, dev.langchain4j.exception.AuthenticationException.class)) {
        return new AuthenticationException("Authentication failed: " + message, failure);
    }
    // 含义：检查异常链中是否有 LangChain4j 的 AuthenticationException
    // 场景：API Key 错误、权限不足
    // 例子：
    //   LangChain4j 抛出：dev.langchain4j.exception.AuthenticationException
    //   转换成我们的：com.mewcode.llm.LlmException.AuthenticationException
    
    // 2.2 限流异常
    if (hasCause(failure, dev.langchain4j.exception.RateLimitException.class)) {
        return new RateLimitException(
            "Rate limited: " + message,
            extractRetryAfter(message),  // 从错误消息提取重试时间
            failure
        );
    }
    // 含义：检查异常链中是否有 LangChain4j 的 RateLimitException
    // 场景：请求过于频繁，触发 API 限流（HTTP 429）
    // 特殊处理：提取 Retry-After 时间（例如 "60 秒后重试"）
    // 例子：
    //   错误消息："Rate limit exceeded. Retry after 60 seconds"
    //   extractRetryAfter() 提取出 "60"
    //   后续 Agent 可以等待 60 秒后自动重试
    
    
    // ========== 步骤 3: 识别网络异常 ==========
    if (hasCause(failure, IOException.class) 
            || hasCause(failure, SocketTimeoutException.class)) {
        return new NetworkException("Network error: " + message, failure);
    }
    // 含义：检查异常链中是否有网络相关异常
    // 场景：
    //   - IOException：网络断开、DNS 解析失败、连接被拒绝
    //   - SocketTimeoutException：请求超时
    // 例子：
    //   ConnectException: Connection refused (服务器未启动)
    //   UnknownHostException: DNS 解析失败
    //   SocketTimeoutException: Read timed out (服务器响应慢)
    
    
    // ========== 步骤 4: 解析 HTTP 状态码 ==========
    HttpException http = findCause(failure, HttpException.class);
    int status = http != null ? http.statusCode() : extractStatus(message);
    
    if (status > 0) {
        return classifyHttpError(status, message, extractRetryAfter(message), failure);
    }
    // 含义：尝试获取 HTTP 状态码，根据状态码分类
    // 两种获取方式：
    //   1. 从 HttpException 对象中直接读取（http.statusCode()）
    //   2. 从错误消息文本中提取（extractStatus(message)）
    // 如果成功获取到状态码（> 0），调用 classifyHttpError() 分类：
    //   - 401/403 → AuthenticationException
    //   - 429 → RateLimitException
    //   - 413 → ContextTooLongException
    //   - 其他 → LlmException（通用 API 错误）
    // 例子：
    //   错误消息："HTTP 401 Unauthorized: Invalid API Key"
    //   extractStatus() 提取出 401
    //   classifyHttpError() 返回 AuthenticationException
    
    
    // ========== 步骤 5: 文本模式匹配 ==========
    if (isContextTooLong(message.toLowerCase())) {
        return new ContextTooLongException("Context too long: " + message, failure);
    }
    // 含义：如果前面的方法都没匹配，检查错误消息文本是否包含"上下文过长"关键词
    // 场景：有些 API 返回 400 或其他状态码，但错误文本说明是上下文过长
    // isContextTooLong() 检查以下关键词：
    //   - "prompt is too long"
    //   - "context length"
    //   - "context window"
    //   - "maximum context"
    //   - "too many tokens"
    //   - "max tokens"
    // 例子：
    //   错误消息："Request failed: prompt is too long, exceeds maximum context"
    //   包含 "prompt is too long" → 返回 ContextTooLongException
    
    
    // ========== 步骤 6: 兜底 ==========
    return new LlmException("Unexpected LLM error: " + message, failure);
    // 含义：如果所有分类规则都不匹配，返回基类 LlmException
    // 场景：未知错误、新类型错误、无法分类的错误
    // 例子：
    //   LangChain4j 新增了一种异常，我们还没适配
    //   或者是 LLM 返回了意外的错误格式
}
```

**优先级顺序为什么重要？**

```
假设错误消息是："HTTP 429 Rate limited. Retry after 60 seconds"

按顺序检查：
1. ❌ 不是 null
2. ❌ 不是已有的 LlmException
3. ✅ 是 LangChain4j 的 RateLimitException → 返回！

如果把步骤 5（文本匹配）放在前面：
5. ✅ 消息包含 "rate" 关键词 → 错误地返回通用 LlmException
3. 永远不会执行 → 丢失了 Retry-After 信息！

所以顺序是：精确匹配 → 类型匹配 → 状态码 → 文本匹配 → 兜底
```

**关键辅助方法**：

```java
// 查找异常链中是否有指定类型
private static <T extends Throwable> T findCause(
    Throwable failure, 
    Class<T> type
) {
    Throwable current = failure;
    while (current != null) {
        if (type.isInstance(current)) {
            return type.cast(current);  // 找到了，返回
        }
        current = current.getCause();  // 继续往下找
    }
    return null;  // 没找到
}
// 例子：
//   异常链：RuntimeException 
//            → CompletionException 
//              → IOException
//   findCause(root, IOException.class) 会遍历整个链，返回 IOException

// 检查异常链中是否有指定类型（只判断有无，不返回对象）
private static <T extends Throwable> boolean hasCause(
    Throwable failure,
    Class<T> type
) {
    return findCause(failure, type) != null;
}
```

**HTTP 状态码分类**：

这是 `classify()` 方法在**步骤 4**中调用的辅助方法。当从异常中成功提取到 HTTP 状态码后，通过这个方法根据状态码返回对应的异常类型。

**调用位置**：`LlmException.java` 的 `classify()` 方法第 4 步
```java
// 在 classify() 方法中：
// 步骤 4: 解析 HTTP 状态码
HttpException http = findCause(failure, HttpException.class);
int status = http != null ? http.statusCode() : extractStatus(message);

if (status > 0) {
    // ⭐ 调用这个方法
    return classifyHttpError(status, message, extractRetryAfter(message), failure);
}
```

**方法实现**：
```java
// LlmException.java
static LlmException classifyHttpError(
    int status,        // HTTP 状态码（如 401、429、413）
    String body,       // 错误消息体
    String retryAfter, // Retry-After 值（如果有）
    Throwable cause    // 原始异常
) {
    return switch (status) {
        case 401, 403 -> new AuthenticationException(
            "Authentication failed (HTTP " + status + "): " + body, cause);
        // 401 Unauthorized: API Key 无效
        // 403 Forbidden: 权限不足
        
        case 429 -> new RateLimitException(
            "Rate limited (HTTP 429): " + body, retryAfter, cause);
        // 429 Too Many Requests: 请求过于频繁
        // 特殊：保留 retryAfter 参数，Agent 可以据此等待后重试
        
        case 413 -> new ContextTooLongException(
            "Context too long: " + body, cause);
        // 413 Payload Too Large: 请求体太大（通常是上下文过长）
        
        default -> new LlmException(
            "API error (HTTP " + status + "): " + body, cause);
        // 其他状态码: 400、500、502、503 等
        // 返回通用的 LlmException
    };
}
```

**为什么步骤 4 不和前面的步骤冲突？**

关键在于 LangChain4j 的异常设计：**它既抛出语义化异常，又包含 HTTP 状态码**。

**场景 1：LangChain4j 已经分类好的异常**
```
LangChain4j 收到 HTTP 429 响应
    ↓
LangChain4j 内部识别这是限流
    ↓
抛出：dev.langchain4j.exception.RateLimitException
    ↓
我们的 classify() 检查：
    步骤 2: hasCause(..., dev.langchain4j.exception.RateLimitException.class)
    ✅ 匹配！直接返回我们的 RateLimitException
    步骤 4: 永远不会执行
```

**场景 2：LangChain4j 没有分类，只有 HTTP 状态码**
```
LangChain4j 收到 HTTP 413 响应
    ↓
LangChain4j 没有专门的 PayloadTooLargeException
    ↓
抛出：dev.langchain4j.exception.HttpException(statusCode=413)
    ↓
我们的 classify() 检查：
    步骤 2: hasCause(..., dev.langchain4j.exception.RateLimitException.class)
    ❌ 不匹配（不是 RateLimitException）
    步骤 3: hasCause(..., IOException.class)
    ❌ 不匹配（不是 IOException）
    步骤 4: 从 HttpException 提取状态码 413
    ✅ 匹配！调用 classifyHttpError(413, ...) 返回 ContextTooLongException
```

**步骤 2 vs 步骤 4 的职责分工**：

| 步骤 | 处理什么 | 例子 |
|-----|---------|------|
| **步骤 2** | LangChain4j **已经分类好**的语义异常 | `dev.langchain4j.exception.RateLimitException` <br> `dev.langchain4j.exception.AuthenticationException` |
| **步骤 4** | LangChain4j **没有专门分类**的 HTTP 错误 | `HttpException(statusCode=413)` <br> `HttpException(statusCode=500)` |

**为什么这样设计？**

1. **优先信任 LangChain4j 的分类**（步骤 2）
   - LangChain4j 可能从响应头提取了更多信息
   - 例如 `RateLimitException` 可能包含精确的 `Retry-After` 值

2. **兜底处理未分类的状态码**（步骤 4）
   - LangChain4j 不可能为每个状态码都定义异常类
   - 我们通过状态码自己分类

**实际例子对比**：

```java
// 例子 1: LangChain4j 已分类
异常链：
  dev.langchain4j.exception.RateLimitException: "Rate limited"
    ↓ 原因
  HttpException(statusCode=429)

classify() 执行：
  步骤 2: ✅ 发现 RateLimitException，立即返回（保留 LangChain4j 的分类）
  步骤 4: ❌ 不执行

// 例子 2: LangChain4j 未分类
异常链：
  HttpException(statusCode=413): "Payload too large"

classify() 执行：
  步骤 2: ❌ 没有 RateLimitException（LangChain4j 没有 PayloadTooLargeException）
  步骤 3: ❌ 不是网络异常
  步骤 4: ✅ 发现状态码 413，调用 classifyHttpError() 返回 ContextTooLongException
```

**总结**：
- 步骤 2 和步骤 4 **不冲突**
- 步骤 2 处理"LangChain4j 已经分类好的"
- 步骤 4 处理"LangChain4j 没有分类，只有状态码的"
- 它们是**互补**的，不是**重复**的

因为不同的 HTTP 状态码代表不同的错误类型，需要返回不同的异常类：

| HTTP 状态码 | 含义 | 返回的异常类型 | Agent 处理策略 |
|------------|------|---------------|---------------|
| 401 | Unauthorized | `AuthenticationException` | ❌ 不重试，提示检查 API Key |
| 403 | Forbidden | `AuthenticationException` | ❌ 不重试，提示检查权限 |
| 429 | Too Many Requests | `RateLimitException` | ✅ 等待后重试（最多3次） |
| 413 | Payload Too Large | `ContextTooLongException` | ✅ 压缩上下文后重试（最多3次） |
| 400/500/502... | 其他错误 | `LlmException` | ❌ 不重试 |

**实际调用示例**：

```
LangChain4j 收到 HTTP 429 响应
    ↓
抛出异常（消息包含 "HTTP 429 Rate limited. Retry after 60 seconds"）
    ↓
LangChainClient.onError() 捕获
    ↓
调用 LlmException.classify(exception)
    ↓ 步骤 1-3 不匹配
    ↓ 步骤 4: 从消息中提取状态码 429
    ↓
调用 classifyHttpError(429, "Rate limited...", "60", exception)
    ↓ switch (429)
    ↓ case 429 -> new RateLimitException(..., "60", ...)
    ↓
返回 RateLimitException（包含 retryAfter="60"）
    ↓
Agent 收到后等待 60 秒，然后重试
```
**文本模式匹配**：

这是 `classify()` 方法在**步骤 5**中调用的辅助方法。当前面的所有步骤都不匹配时，通过检查错误消息文本是否包含特定关键词来判断是否是上下文过长错误。

**为什么需要文本匹配？**

因为不是所有 LLM API 都返回标准的 HTTP 413 状态码。有些 API 返回 400 或 500，但错误消息中包含"上下文过长"的关键词。

**调用位置**：`LlmException.java` 的 `classify()` 方法第 5 步
```java
// 在 classify() 方法中：
// 步骤 5: 文本模式匹配
if (isContextTooLong(message.toLowerCase())) {
    return new ContextTooLongException("Context too long: " + message, failure);
}
```

**方法实现**：
```java
// LlmException.java
private static boolean isContextTooLong(String lower) {
    // lower 参数是小写的错误消息
    return lower.contains("prompt is too long")      // OpenAI 风格
        || lower.contains("context length")          // 通用关键词
        || lower.contains("context window")          // Anthropic 风格
        || lower.contains("maximum context")         // 通用关键词
        || lower.contains("too many tokens")         // 通用关键词
        || lower.contains("max tokens");             // 通用关键词
}
```

**实际例子**：

```
场景 1: Ollama 本地模型
错误消息："Error: prompt is too long, maximum context is 4096 tokens"
    ↓
步骤 2: ❌ 不是 LangChain4j 的语义异常
步骤 3: ❌ 不是网络异常
步骤 4: ❌ 没有 HTTP 状态码（本地 API）
步骤 5: ✅ 消息包含 "prompt is too long" 和 "maximum context"
    ↓
返回 ContextTooLongException

场景 2: 某些自定义 API
错误消息："Request failed: input exceeds max tokens limit (8192)"
    ↓
步骤 2-4: ❌ 都不匹配
步骤 5: ✅ 消息包含 "max tokens"
    ↓
返回 ContextTooLongException
```

---

**Retry-After 提取**：

这个方法从错误消息文本中提取重试等待时间。LLM API 限流时通常会在响应中包含 `Retry-After` 信息。

**调用位置**：
1. `classify()` 方法步骤 2 - 处理 LangChain4j 的 `RateLimitException`
2. `classify()` 方法步骤 4 - 处理 HTTP 429 状态码

**方法实现**：
```java
// LlmException.java
static String extractRetryAfter(String message) {
    // 匹配 "Retry-After: 7" 或 "retry after 7 seconds"
    // (?i) 表示大小写不敏感
    Pattern pattern = Pattern.compile(
        "(?i)retry[- ]after\\s*[:=]\\s*(\\d+)(?:\\s*seconds?)?"
    );
    Matcher matcher = pattern.matcher(message);
    return matcher.find() ? matcher.group(1) : "";  // 返回数字部分，如 "60"
}
```

**匹配的格式**：
- `"Retry-After: 60"` → 提取 `"60"`
- `"retry after 60 seconds"` → 提取 `"60"`
- `"Retry-After=60"` → 提取 `"60"`
- `"retry-after: 60s"` → 提取 `"60"`

**如何使用提取的值**：

```java
// 在 Agent.java 中
if (error instanceof LlmException.RateLimitException rateLimit) {
    String retryAfter = rateLimit.getRetryAfter();  // 例如 "60"
    
    long waitMs;
    if (retryAfter != null && !retryAfter.isBlank()) {
        long seconds = Long.parseLong(retryAfter);
        waitMs = seconds * 1000;  // 60 秒 = 60000 毫秒
    } else {
        waitMs = 指数退避算法;  // 如果没有提取到，使用默认策略
    }
    
    Thread.sleep(waitMs);  // 等待后重试
}
```
---

### 流式事件传递

这一步是**将类型化异常包装成流式事件**，通过队列传递给 Agent。

**为什么需要这一步？**

因为 LLM 的错误和正常响应（文本、工具调用）都要通过**同一个队列**传递给 Agent。所以错误也必须包装成 `StreamEvent`。

**文件位置**：`src/main/java/com/mewcode/llm/StreamEvent.java`

**完整调用链**：

```
LangChain4j 抛出异常
    ↓
LangChainClient.onError(Throwable error)
    ↓
LlmException.classify(error)  ← 步骤 1: 分类异常
    ↓ 返回 RateLimitException / ContextTooLongException / ...
new StreamEvent.Error(分类后的异常)  ← 步骤 2: 包装成事件
    ↓
put(queue, error事件)  ← 步骤 3: 放入队列
    ↓
队列：[TextDelta("好"), TextDelta("的"), Error(RateLimitException), ...]
    ↓
Agent.agentLoop() 消费队列
    ↓
case StreamEvent.Error err -> {
    LlmException exception = err.exception();  ← 步骤 4: 提取异常对象
    
    if (exception instanceof RateLimitException) {
        // 执行重试逻辑
    }
}
```

**StreamEvent.Error 的定义**：

```java
// StreamEvent.java
public sealed interface StreamEvent {
    // ... 其他事件类型
    
    /**
     * 错误事件：保留完整的类型化异常对象
     */
    record Error(LlmException exception) implements StreamEvent {
        
        // 主构造函数：要求异常不能为 null
        public Error {
            Objects.requireNonNull(exception, "exception must not be null");
        }
        
        // 兼容构造函数（用于本地错误，如 JSON 解析失败）
        public Error(String message) {
            this(new LlmException(message == null ? "Unknown LLM error" : message));
        }
        
        // 兼容方法（返回消息文本，用于日志打印）
        public String message() {
            return exception.getMessage();
        }
    }
}
```

**三个使用位置**：

**位置 1：LangChain4j 回调错误**

**作用**：捕获 LangChain4j 在 HTTP 请求、SSE 解析、网络通信过程中发生的错误。

**什么时候触发**：
- 网络连接失败
- HTTP 返回 401/403/429/413/500 等错误状态码
- SSE 流被中断
- API 返回格式错误

```java
// LangChainClient.java:116
private StreamingChatResponseHandler handler(BlockingQueue<StreamEvent> queue) {
    return new StreamingChatResponseHandler() {
        @Override
        public void onError(Throwable error) {
            // ⭐ LangChain4j 调用这个方法通知错误
            // error 可能是：
            //   - dev.langchain4j.exception.RateLimitException (HTTP 429)
            //   - dev.langchain4j.exception.AuthenticationException (HTTP 401)
            //   - HttpException (其他 HTTP 错误)
            //   - IOException (网络错误)
            
            put(queue, new StreamEvent.Error(
                LlmException.classify(error)  // 先分类成我们的异常类型，再包装成事件
            ));
        }
    };
}
```

**例子**：
```
用户请求 → LangChainClient.stream() → LangChain4j 发送 HTTP 请求
    ↓
LLM API 返回 HTTP 429 Too Many Requests
    ↓
LangChain4j 抛出 dev.langchain4j.exception.RateLimitException
    ↓
触发 onError(RateLimitException)
    ↓
classify() 分类成 com.mewcode.llm.LlmException.RateLimitException
    ↓
包装成 StreamEvent.Error
    ↓
放入队列 → Agent 收到 → 等待后重试
```

---

**位置 2：doStream() 异常兜底**

**作用**：捕获 `doStream()` 方法内部的代码错误，防止虚拟线程因未捕获异常而终止。

**什么时候触发**：
- `buildModel()` 创建模型失败（协议配置错误）
- `toMessages()` 转换消息失败（数据格式问题）
- `toToolSpecs()` 转换工具失败（JSON Schema 错误）
- 任何其他意外的运行时异常

```java
// LangChainClient.java:230-240
@Override
public BlockingQueue<StreamEvent> stream(...) {
    var queue = new LinkedBlockingQueue<StreamEvent>(64);
    
    Thread.ofVirtual().start(() -> {
        try {
            doStream(conv, tools, queue);  // ← 调用实际工作方法
        } catch (Exception e) {
            // ⭐ 如果 doStream() 内部抛出异常（非 LangChain4j 回调的）
            // 例如：
            //   - buildModel() 抛出 IllegalArgumentException (未知协议)
            //   - toMessages() 抛出 NullPointerException (数据异常)
            //   - 队列操作抛出 InterruptedException
            
            put(queue, new StreamEvent.Error(
                LlmException.classify(e)  // 先分类，再包装
            ));
        }
    });
    
    return queue;
}
```

**例子**：
```
用户配置了错误的协议：protocol: "unknown"
    ↓
Agent 调用 client.stream()
    ↓
虚拟线程启动，执行 doStream()
    ↓
buildModel() 中 switch 找不到匹配的 case
    ↓
抛出 IllegalArgumentException("Unknown protocol: unknown")
    ↓
被 catch 捕获，调用 classify() 分类
    ↓
包装成 StreamEvent.Error
    ↓
放入队列 → Agent 收到 → 提示用户检查配置
```

**为什么需要这个兜底？**

如果不捕获，虚拟线程会因为异常而终止，但主线程（Agent）不知道发生了什么，会一直阻塞在 `queue.poll()` 等待事件，直到超时。

---

**位置 3：本地解析错误**

**作用**：捕获本地处理数据时的错误（与 LLM API 无关）。

**什么时候触发**：
- 工具参数 JSON 格式错误
- LLM 返回了无效的 JSON
- 本地数据验证失败

```java
// LangChainClient.java (handler 方法内部)
@Override
public void onCompleteToolCall(CompleteToolCall complete) {
    try {
        // ⭐ 尝试将工具参数从 JSON 字符串解析成 Map
        // complete.arguments() 是 LLM 返回的，例如：
        //   正常："{"file_path": "hello.txt", "content": "hello"}"
        //   错误："{file_path: hello.txt}"  (缺少引号，JSON 格式错误)
        
        Map<String, Object> args = parseJson(complete.arguments());
        
        put(queue, new StreamEvent.ToolCallComplete(
            complete.id(),
            complete.name(),
            args
        ));
    } catch (JsonProcessingException e) {
        // ⭐ 本地解析失败，不是 LLM API 的错误
        // 这是我们自己的代码处理数据时失败了
        
        put(queue, new StreamEvent.Error(
            "Invalid tool arguments: " + e.getMessage()  // 使用字符串构造函数
        ));
    }
}

private static Map<String, Object> parseJson(String json) throws JsonProcessingException {
    return MAPPER.readValue(json, new TypeReference<>() {});
}
```

**例子**：
```
LLM 返回工具调用：
{
  "id": "tool_1",
  "name": "WriteFile",
  "arguments": "{file_path: hello.txt}"  ← JSON 格式错误（缺少引号）
}
    ↓
LangChain4j 触发 onCompleteToolCall()
    ↓
我们调用 parseJson("{file_path: hello.txt}")
    ↓
Jackson 抛出 JsonProcessingException: Unexpected character ('f' (code 102))
    ↓
catch 捕获，包装成 StreamEvent.Error
    ↓
放入队列 → Agent 收到 → 提示 "Invalid tool arguments: Unexpected character"
```

**为什么使用字符串构造函数？**

因为这不是 LLM API 的错误（HTTP/网络），而是我们本地代码处理数据失败，不需要分类成 `RateLimitException` 或 `NetworkException`，直接用通用的 `LlmException` 即可。

---

**三种错误的对比**：

| 位置 | 错误来源 | 触发时机 | 构造函数 | 例子 |
|-----|---------|---------|---------|------|
| **位置 1** | LangChain4j 底层通信 | HTTP 错误、网络错误 | `Error(classify(error))` | HTTP 429 限流 |
| **位置 2** | doStream() 内部代码 | 配置错误、空指针 | `Error(classify(error))` | 未知协议配置 |
| **位置 3** | 本地数据解析 | JSON 格式错误 | `Error("message")` | LLM 返回的 JSON 格式不对 |

**共同点**：都通过同一个队列传递错误，保证了错误和正常事件的顺序一致。
---

**为什么有两个构造函数？**

| 构造函数 | 用途 | 调用位置 |
|---------|------|---------|
| `Error(LlmException exception)` | LLM API 错误（已经分类好的） | LangChain4j 回调、doStream() 兜底 |
| `Error(String message)` | 本地错误（不需要分类的） | JSON 解析失败、本地验证失败 |

**旧设计 vs 新设计**：

```java
// ❌ 旧设计：只保存错误消息字符串
record Error(String message) implements StreamEvent {}

// Agent 使用时：
case StreamEvent.Error err -> {
    if (err.message().contains("rate limit")) {  // 字符串匹配，容易出错
        // 重试逻辑
    }
}

// ✅ 新设计：保存完整的异常对象
record Error(LlmException exception) implements StreamEvent {}

// Agent 使用时：
case StreamEvent.Error err -> {
    if (err.exception() instanceof RateLimitException rateLimit) {  // 类型安全
        String retryAfter = rateLimit.getRetryAfter();  // 可以访问结构化数据
        // 重试逻辑
    }
}
```

**核心优势**：

1. **类型安全**：编译器保证不会误判异常类型
2. **结构化数据**：可以访问 `retryAfter`、`statusCode` 等字段
3. **统一处理**：错误和正常事件走同一个队列，保证顺序

---

### Agent 自动恢复策略

当 Agent 从队列中收到 `StreamEvent.Error` 事件后，会根据异常类型自动执行不同的恢复策略。

**文件位置**：`src/main/java/com/mewcode/agent/Agent.java`

**方法位置**：`agentLoop()` 方法内的错误处理部分（第 304-368 行）

**调用链路**：

```
LangChain4j 抛出异常
    ↓
LangChainClient 分类异常
    ↓
包装成 StreamEvent.Error 放入队列
    ↓
Agent.agentLoop() 消费队列
    ↓
case StreamEvent.Error err -> {
    var error = err.exception();  ← 提取类型化异常
    
    // 根据异常类型执行不同策略
    if (error instanceof ContextTooLongException) {
        // 策略 1: 压缩上下文重试
    } else if (error instanceof RateLimitException) {
        // 策略 2: 等待后重试
    } else {
        // 策略 3/4: 不重试，报错
        break;
    }
}
```

Agent 根据异常类型自动执行恢复策略：

#### 1. 上下文过长恢复

**触发条件**：收到 `ContextTooLongException`（上下文超过模型限制）

**代码位置**：`Agent.java:309-342`

```java
if (error instanceof LlmException.ContextTooLongException) {
    if (contextRetries < 3) {
        contextRetries++;
        putSafe(queue, new AgentEvent.RetryEvent(
            "Context too long, compacting...", 0));
        
        // 应用 tool-result budget（裁剪工具结果）
        ToolResultBudget.apply(conv, sessionDir, replacementState);
        
        // 强制压缩上下文
        ContextCompactor.forceCompact(conv, client, contextWindow, ...);
        
        // 压缩器会原样保留长期记忆；仅在外部旧流程丢失它时再补注入
        ensureLongTermMemory(conv);
        
        continue;  // 重试
    }
}
```

**策略**：
- 最多重试 3 次
- 先裁剪工具结果
- 执行上下文压缩
- 确认长期记忆仍存在（旧流程丢失时才补注入）
- 重新发起 LLM 请求

#### 2. 限流恢复

```java
if (error instanceof LlmException.RateLimitException rateLimit
        && rateLimitRetries < 3) {
    rateLimitRetries++;
    
    // 计算等待时间（Retry-After 优先 / 指数退避）
    long waitMs = retryDelayMillis(
        rateLimit.getRetryAfter(), 
        rateLimitRetries
    );
    
    putSafe(queue, new AgentEvent.RetryEvent(
        "Rate limited, retrying...", waitMs));
    
    Thread.sleep(waitMs);
    continue;  // 重试
}
```

**等待时间计算**：

```java
private static long retryDelayMillis(String retryAfter, int attempt) {
    // 1. 优先使用 Retry-After（上限 120 秒）
    if (retryAfter != null && !retryAfter.isBlank()) {
        try {
            long seconds = Long.parseLong(retryAfter.trim());
            return Math.min(seconds * 1000L, 120_000L);
        } catch (NumberFormatException ignored) {}
    }
    
    // 2. 指数退避: 1s << (attempt-1)
    // attempt 1: 1s, 2: 2s, 3: 4s, 4: 8s, 5: 16s, 6: 32s, 7+: 64s
    long backoff = 1_000L << Math.min(Math.max(attempt - 1, 0), 6);
    return Math.min(backoff, 120_000L);
}
```

**策略**：
- 最多重试 3 次
- **优先使用 Retry-After** 响应头
- 否则使用**指数退避**：1秒 → 2秒 → 4秒 → 8秒 → 16秒 → 32秒 → 64秒
- 上限 120 秒

#### 3. 认证错误（不重试）

```java
// Authentication and unknown errors are not safe to retry.
break;
```

**策略**：
- 不自动重试
- 直接结束循环
- 通过 `AgentEvent.ErrorEvent` 通知上层
- 提示用户检查 API Key、模型配置和环境变量

#### 4. 网络错误（当前不重试）

**策略**：
- 正确分类为 `NetworkException`
- 当前版本不自动重试
- 预留扩展点，未来可增加有限重试（2-3 次）

---

## 关键技术特性

### 1. 虚拟线程（Java 21）

```java
Thread.ofVirtual()
        .name("minicode-model-stream")
        .start(() -> {
            doStream(conv, tools, queue);
        });
```

**为什么用虚拟线程？**

- ✅ **轻量级**：创建上万个不会有性能问题
- ✅ **自动调度**：阻塞时自动让出 OS 线程
- ✅ **简化并发**：写法像同步代码，实际是异步

**对比平台线程**：

| 特性 | 平台线程 | 虚拟线程 |
|------|---------|---------|
| 内存占用 | 1-2 MB | 几 KB |
| 创建成本 | 高（系统调用） | 低（JVM 管理） |
| 数量限制 | 几千个 | 上万个 |
| 阻塞成本 | 高（OS 线程挂起） | 低（自动让出） |

---

### 2. Sealed Interface + Record（Java 16/17）

```java
public sealed interface StreamEvent {
    record TextDelta(String text) implements StreamEvent {}
    record Error(String message) implements StreamEvent {}
    // ... 其他 6 种
}
```

**编译器保证穷尽性**：

```java
StreamEvent event = ...;

// ✅ 编译通过（覆盖所有类型）
return switch (event) {
    case TextDelta td -> handleText(td);
    case ThinkingDelta td -> handleThinking(td);
    case ThinkingComplete tc -> handleComplete(tc);
    case ToolCallStart tcs -> handleStart(tcs);
    case ToolCallDelta tcd -> handleDelta(tcd);
    case ToolCallComplete tcc -> handleComplete(tcc);
    case StreamEnd se -> handleEnd(se);
    case Error e -> handleError(e);
};

// ❌ 编译警告（遗漏 Error 分支）
return switch (event) {
    case TextDelta td -> handleText(td);
    case ThinkingDelta td -> handleThinking(td);
    // ... 其他分支
    // 缺少 Error 分支 → 编译器警告
};
```

---

### 3. Switch 表达式（Java 14）

```java
// 传统 switch 语句
String result;
switch (protocol) {
    case "anthropic":
        result = "Anthropic";
        break;
    case "openai":
        result = "OpenAI";
        break;
    default:
        result = "Unknown";
}
return result;

// Switch 表达式
return switch (protocol) {
    case "anthropic" -> "Anthropic";
    case "openai" -> "OpenAI";
    default -> "Unknown";
};
```

**优势**：
- ✅ 不需要 `break`
- ✅ 不需要临时变量
- ✅ 编译器强制穷尽检查
- ✅ 代码更简洁

---

### 4. 模式匹配（Java 16）

```java
// 传统写法
if (event instanceof StreamEvent.TextDelta) {
    StreamEvent.TextDelta td = (StreamEvent.TextDelta) event;
    handleText(td.text());
}

// 模式匹配
if (event instanceof StreamEvent.TextDelta td) {
    handleText(td.text());  // td 已经转型并绑定
}

// Switch 模式匹配
switch (event) {
    case StreamEvent.TextDelta td -> handleText(td.text());
    case StreamEvent.Error e -> handleError(e.message());
}
```

---

### 5. BlockingQueue 生产者-消费者模式

```java
// 生产者（虚拟线程）
void doStream(..., BlockingQueue<StreamEvent> queue) {
    queue.put(new StreamEvent.TextDelta("好"));
    queue.put(new StreamEvent.TextDelta("的"));
}

// 消费者（Agent 线程）
while (true) {
    StreamEvent event = queue.poll(30, TimeUnit.SECONDS);
    if (event == null) break;  // 超时
    
    switch (event) {
        case TextDelta td -> ui.append(td.text());
    }
}
```

**特性**：
- ✅ **线程安全**（多线程读写自动同步）
- ✅ **阻塞机制**（队列满时生产者阻塞，队列空时消费者阻塞）
- ✅ **容量控制**（64 个缓冲，平衡速度）

---

## 设计模式总结

### 1. 工厂模式

```java
LlmClient.create(cfg, systemPrompt)
    ↓
根据 protocol 创建不同实现
```

### 2. 适配器模式

```java
LangChain4j 回调接口
    ↓ 适配
StreamingChatResponseHandler
    ↓
StreamEvent
```

### 3. 生产者-消费者模式

```java
虚拟线程（生产者）
    ↓ BlockingQueue
Agent（消费者）
```

### 4. 策略模式

```java
switch (protocol) {
    case "anthropic" -> AnthropicStreamingChatModel
    case "openai" -> OpenAiStreamingChatModel
    case "ollama" -> OllamaStreamingChatModel
}
```

### 5. 观察者模式

```java
LangChain4j 触发回调
    ↓
StreamingChatResponseHandler 接收
    ↓
转换成 StreamEvent
    ↓
放入队列
```

---

## 小结

| 设计决策 | Java + LangChain4j 实现 |
|---------|------------------------|
| **供应商抽象** | LlmClient 接口 + 工厂方法 |
| **底层通信** | LangChain4j 库（不再手写 HTTP + SSE） |
| **流式响应** | 虚拟线程 + LinkedBlockingQueue(64) |
| **事件类型安全** | sealed interface + 8 个 record |
| **回调适配** | StreamingChatResponseHandler → StreamEvent |
| **消息转换** | LangChainClient.toMessages() |
| **工具转换** | LangChainClient.toToolSpecs() |
| **错误处理** | 简化版 classify()（未使用完整异常体系） |
| **兼容性** | AnthropicClient / OpenAiClient 继承 LangChainClient |
| **并发模型** | 生产者-消费者 + 虚拟线程 |

---

## 核心价值

1. **零 HTTP 代码**
   - 使用 LangChain4j 库
   - 不需要手写 SSE 解析
   - 专注业务逻辑

2. **类型安全**
   - sealed interface 保证穷尽性
   - record 保证不可变
   - 编译时检查，运行时安全

3. **高并发低成本**
   - 虚拟线程轻量级
   - 可以支持上千个并发对话
   - 阻塞 IO 不浪费资源

4. **分层解耦**
   - LangChain4j / StreamEvent / AgentEvent 三层
   - 每层只关心自己的职责
   - 易于扩展和维护

5. **统一抽象**
   - 一个 LangChainClient 适配所有协议
   - 新增提供商只需修改 buildModel()
   - 上层代码完全不需要改

**这就是现代 Java + LangChain4j 的力量：用更少的代码，更强的类型约束，更高的并发能力，实现流式 LLM 通信！** 🚀

---

## 端到端完整流程图

从用户输入到 LLM 响应的完整数据流：

```
┌─────────────────────────────────────────────────────────────────────┐
│ 1. 用户层                                                            │
└─────────────────────────────────────────────────────────────────────┘
    用户输入："创建 hello.txt 文件"
        ↓
┌─────────────────────────────────────────────────────────────────────┐
│ 2. Agent 层 (src/main/java/com/mewcode/agent/Agent.java)            │
└─────────────────────────────────────────────────────────────────────┘
    Agent.agentLoop()
        ↓ 调用
    client.stream(conv, tools)  ← 传入对话历史和工具定义
        ↓ 返回
    BlockingQueue<StreamEvent> queue  ← 获得事件队列
        ↓
┌─────────────────────────────────────────────────────────────────────┐
│ 3. LLM 客户端层 (src/main/java/com/mewcode/llm/LangChainClient.java)│
└─────────────────────────────────────────────────────────────────────┘
    LangChainClient.stream()
        ↓ 创建虚拟线程
    Thread.ofVirtual().start(() -> doStream(...))
        ↓
    doStream() 方法：
        ├─ buildModel(snapshot)  ← 根据协议创建 LangChain4j 模型
        │   └─ switch (protocol) {
        │       case "anthropic" → AnthropicStreamingChatModel
        │       case "openai" → OpenAiStreamingChatModel
        │       case "ollama" → OllamaStreamingChatModel
        │   }
        ├─ toMessages(conv)  ← 转换对话历史
        ├─ toToolSpecs(tools)  ← 转换工具定义
        └─ model.chat(request, handler(queue))  ← 发送请求 + 注册回调
            ↓
┌─────────────────────────────────────────────────────────────────────┐
│ 4. LangChain4j 层 (第三方库)                                         │
└─────────────────────────────────────────────────────────────────────┘
    LangChain4j 底层通信：
        ├─ 构建 HTTP 请求（POST /v1/messages）
        ├─ 设置请求头（Authorization、Content-Type、Accept: text/event-stream）
        ├─ 发送 JSON 请求体
        └─ 建立 SSE (Server-Sent Events) 连接
            ↓
┌─────────────────────────────────────────────────────────────────────┐
│ 5. LLM API 层 (Anthropic / OpenAI / Ollama)                         │
└─────────────────────────────────────────────────────────────────────┘
    LLM API 接收请求
        ↓ 处理
    开始生成响应（流式）
        ↓ SSE 流
    发送事件：
        ├─ event: message_start
        ├─ event: content_block_start
        ├─ event: content_block_delta  ← 文本片段："好"
        ├─ event: content_block_delta  ← 文本片段："的"
        ├─ event: content_block_delta  ← 文本片段："，"
        ├─ event: content_block_stop
        └─ event: message_stop
            ↓
┌─────────────────────────────────────────────────────────────────────┐
│ 6. LangChain4j 解析层                                                │
└─────────────────────────────────────────────────────────────────────┘
    LangChain4j 接收 SSE 事件
        ↓ 解析
    触发回调：
        ├─ onPartialResponse("好")  ← 文本片段
        ├─ onPartialResponse("的")
        ├─ onPartialResponse("，")
        ├─ onPartialToolCall(...)  ← 工具调用
        ├─ onCompleteToolCall(...)
        ├─ onCompleteResponse(...)  ← 流结束
        └─ onError(...)  ← 错误（如果发生）
            ↓
┌─────────────────────────────────────────────────────────────────────┐
│ 7. 回调适配器层 (LangChainClient.handler())                          │
└─────────────────────────────────────────────────────────────────────┘
    StreamingChatResponseHandler.onPartialResponse("好")
        ↓ 转换
    new StreamEvent.TextDelta("好")
        ↓ 放入队列
    queue.put(StreamEvent.TextDelta("好"))
        ↓
    同样处理其他事件：
        ├─ onPartialThinking → StreamEvent.ThinkingDelta
        ├─ onCompleteThinking → StreamEvent.ThinkingComplete
        ├─ onPartialToolCall → StreamEvent.ToolCallStart
        ├─ onCompleteToolCall → StreamEvent.ToolCallComplete
        ├─ onCompleteResponse → StreamEvent.StreamEnd
        └─ onError → StreamEvent.Error(LlmException.classify(error))
            ↓
┌─────────────────────────────────────────────────────────────────────┐
│ 8. 事件队列 (BlockingQueue<StreamEvent>)                            │
└─────────────────────────────────────────────────────────────────────┘
    队列内容：
        [TextDelta("好"), TextDelta("的"), TextDelta("，"), 
         ToolCallStart("tool_1", "WriteFile"), 
         ToolCallComplete("tool_1", "WriteFile", {file_path: "hello.txt"}),
         StreamEnd("end_turn", 100, 50, 0, 0)]
            ↓
┌─────────────────────────────────────────────────────────────────────┐
│ 9. Agent 消费层 (Agent.agentLoop())                                  │
└─────────────────────────────────────────────────────────────────────┘
    while (true) {
        StreamEvent event = queue.poll(timeout);
        
        switch (event) {
            case TextDelta td -> {
                text.append(td.text());  ← 累积文本
                putSafe(queue, new AgentEvent.StreamText(td.text()));  ← 转发给 TUI
            }
            
            case ToolCallStart start -> {
                // 记录工具调用开始
            }
            
            case ToolCallComplete call -> {
                // 执行工具
                var result = toolRegistry.execute(call.toolName(), call.arguments());
                conv.addToolResult(call.toolId(), result);
            }
            
            case StreamEnd end -> {
                // 保存对话、更新统计
                return;  ← 结束循环
            }
            
            case Error err -> {
                var exception = err.exception();
                
                if (exception instanceof ContextTooLongException) {
                    // 压缩上下文，重试
                    ToolResultBudget.apply(conv, ...);
                    ContextCompactor.forceCompact(conv, ...);
                    continue;  ← 重新发起请求
                }
                
                if (exception instanceof RateLimitException rateLimit) {
                    // 等待后重试
                    long waitMs = retryDelayMillis(rateLimit.getRetryAfter(), ...);
                    Thread.sleep(waitMs);
                    continue;  ← 重新发起请求
                }
                
                // 其他错误：不重试
                break;  ← 结束循环
            }
        }
    }
        ↓
┌─────────────────────────────────────────────────────────────────────┐
│ 10. TUI 层 (src/main/java/com/mewcode/tui/MewCodeModel.java)        │
└─────────────────────────────────────────────────────────────────────┘
    收到 AgentEvent.StreamText("好")
        ↓
    终端实时显示："好"
        ↓
    收到 AgentEvent.StreamText("的")
        ↓
    终端追加显示："好的"
        ↓
    收到 AgentEvent.StreamText("，")
        ↓
    终端追加显示："好的，"
        ↓
    ...（逐字显示完整响应）
        ↓
┌─────────────────────────────────────────────────────────────────────┐
│ 11. 用户看到完整响应                                                 │
└─────────────────────────────────────────────────────────────────────┘
    终端输出：
    "好的，我来创建 hello.txt 文件。
    
    [Tool: WriteFile]
    file_path: hello.txt
    content: Hello, World!
    
    文件创建成功！"
```

---

## 错误处理流程

当 LLM API 返回错误时的完整处理流程：

```
┌─────────────────────────────────────────────────────────────────────┐
│ 错误场景 1: HTTP 429 限流错误                                        │
└─────────────────────────────────────────────────────────────────────┘
    LLM API 返回：HTTP 429 "Rate limited. Retry after 60 seconds"
        ↓
    LangChain4j 抛出：dev.langchain4j.exception.RateLimitException
        ↓
    LangChainClient.onError(RateLimitException)
        ↓
    LlmException.classify(error)  ← 分类异常
        └─ 步骤 2: 检测到 LangChain4j 的 RateLimitException
        └─ 返回：com.mewcode.llm.LlmException.RateLimitException(retryAfter="60")
        ↓
    new StreamEvent.Error(RateLimitException)  ← 包装成事件
        ↓
    queue.put(error事件)
        ↓
    Agent 收到 StreamEvent.Error
        ↓
    if (exception instanceof RateLimitException rateLimit) {
        waitMs = 60秒 * 1000 = 60000毫秒
        Thread.sleep(60000);
        continue;  ← 重新发起请求
    }

┌─────────────────────────────────────────────────────────────────────┐
│ 错误场景 2: 上下文过长                                               │
└─────────────────────────────────────────────────────────────────────┘
    LLM API 返回：HTTP 413 "Request entity too large: prompt exceeds max tokens"
        ↓
    LangChain4j 抛出：HttpException(statusCode=413)
        ↓
    LangChainClient.onError(HttpException)
        ↓
    LlmException.classify(error)
        └─ 步骤 4: 从 HttpException 提取状态码 413
        └─ classifyHttpError(413, ...) 返回：ContextTooLongException
        ↓
    new StreamEvent.Error(ContextTooLongException)
        ↓
    queue.put(error事件)
        ↓
    Agent 收到 StreamEvent.Error
        ↓
    if (exception instanceof ContextTooLongException) {
        ToolResultBudget.apply(...);  ← 裁剪工具结果
        ContextCompactor.forceCompact(...);  ← 压缩上下文
        conv.injectLongTermMemory(...);  ← 重新注入记忆
        continue;  ← 重新发起请求
    }

┌─────────────────────────────────────────────────────────────────────┐
│ 错误场景 3: 认证失败                                                 │
└─────────────────────────────────────────────────────────────────────┘
    LLM API 返回：HTTP 401 "Invalid API Key"
        ↓
    LangChain4j 抛出：dev.langchain4j.exception.AuthenticationException
        ↓
    LangChainClient.onError(AuthenticationException)
        ↓
    LlmException.classify(error)
        └─ 步骤 2: 检测到 LangChain4j 的 AuthenticationException
        └─ 返回：com.mewcode.llm.LlmException.AuthenticationException
        ↓
    new StreamEvent.Error(AuthenticationException)
        ↓
    queue.put(error事件)
        ↓
    Agent 收到 StreamEvent.Error
        ↓
    // 不是 RateLimitException，也不是 ContextTooLongException
    break;  ← 不重试，直接报错给用户
        ↓
    用户看到错误提示："Authentication failed (HTTP 401): Invalid API Key"
```

---

## 流程描述

这个系统采用**生产者-消费者模式**，通过**队列**实现异步流式通信：

### 核心特点

1. **异步非阻塞**
   - Agent 调用 `client.stream()` 立即返回一个队列，不阻塞主线程
   - LangChain4j 在虚拟线程中处理 HTTP 请求和 SSE 解析
   - Agent 通过 `queue.poll()` 消费事件，可以随时中断

2. **事件驱动**
   - LLM 的响应被分解成多个小事件（文本片段、工具调用、流结束）
   - 每个事件独立处理，实现真正的流式体验
   - 错误也是一种事件，统一通过队列传递

3. **三层转换**
   - **LangChain4j 层**：`onPartialResponse` / `onError` 等回调
   - **StreamEvent 层**：`TextDelta` / `Error` 等统一事件类型
   - **AgentEvent 层**：`StreamText` / `ErrorEvent` 等上层事件

4. **类型安全的错误处理**
   - 错误从底层到上层经过两次转换：
     - `Throwable` → `LlmException`（classify 分类）
     - `LlmException` → `StreamEvent.Error`（包装成事件）
   - Agent 通过 `instanceof` 判断异常类型，编译器保证类型安全
   - 可以访问结构化数据（如 `retryAfter`），不需要解析字符串

5. **自动恢复机制**
   - 上下文过长：自动裁剪 + 压缩 + 重试（最多 3 次）
   - 限流错误：智能等待 + 重试（优先使用 Retry-After，否则指数退避）
   - 认证错误：不重试，直接报错
   - 用户无感知，系统自动处理常见错误

### 为什么这样设计？

- **统一抽象**：一个 `LangChainClient` 支持所有协议（Anthropic、OpenAI、Ollama），新增协议只需修改 `buildModel()`
- **解耦合**：Agent 不知道底层用的是什么 LLM API，LangChainClient 不知道上层如何使用事件
- **可测试**：每一层都可以独立测试，队列可以 mock，事件可以构造
- **易扩展**：新增事件类型（如 `ImageDelta`）不影响现有代码

这就是现代 Java 的力量：**用类型系统保证正确性，用虚拟线程提升并发，用队列实现解耦，用事件驱动实现流式体验**！
