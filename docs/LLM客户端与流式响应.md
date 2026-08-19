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
// LangChainClient.java
private static List<ChatMessage> toMessages(
    List<Message> source,
    String systemPrompt
) {
    var result = new ArrayList<ChatMessage>();
    
    // 1. 添加系统消息（如果有）
    if (!systemPrompt.isEmpty()) {
        result.add(SystemMessage.from(systemPrompt));
    }
    
    // 2. 遍历对话历史
    for (var msg : source) {
        if ("user".equals(msg.getRole())) {
            // 用户消息
            result.add(UserMessage.from(msg.getContent()));
            
        } else if ("assistant".equals(msg.getRole())) {
            // 助手消息
            var builder = AiMessage.builder()
                    .text(msg.getContent());
            
            // 如果有工具调用
            if (msg.getToolUses() != null) {
                for (var tu : msg.getToolUses()) {
                    builder.toolCall(ToolExecutionRequest.builder()
                            .id(tu.toolUseId())
                            .name(tu.toolName())
                            .arguments(toJson(tu.arguments()))
                            .build());
                }
            }
            
            result.add(builder.build());
        }
    }
    
    // 3. 添加工具结果
    for (var msg : source) {
        if (msg.getToolResults() != null) {
            for (var tr : msg.getToolResults()) {
                result.add(ToolExecutionResultMessage.from(
                    tr.toolUseId(),
                    tr.toolName(),
                    tr.content()
                ));
            }
        }
    }
    
    return result;
}
```

**转换示例**：

```java
// 输入：MiniCode 内部格式
List<Message> source = [
    Message(role="user", content="创建 hello.txt"),
    Message(role="assistant", content="好的", 
            toolUses=[ToolUseBlock(id="tool_1", name="WriteFile", ...)])
];

// 输出：LangChain4j 格式
List<ChatMessage> result = [
    SystemMessage("You are a helpful assistant"),  // systemPrompt
    UserMessage("创建 hello.txt"),
    AiMessage(
        text="好的",
        toolCalls=[
            ToolExecutionRequest(id="tool_1", name="WriteFile", ...)
        ]
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

## 错误分类 ✅ 已完全实现

当前项目已实现完整的类型化错误处理系统，包括异常分类、流式事件传递和 Agent 自动恢复策略。

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

`LlmException.classify()` 方法自动将底层异常转换为语义化类型：

```java
public static LlmException classify(Throwable failure) {
    if (failure == null) {
        return new LlmException("Unknown LLM error");
    }
    
    // 1. 已经是 LlmException，直接返回
    LlmException existing = findCause(failure, LlmException.class);
    if (existing != null) {
        return existing;
    }
    
    // 2. 识别 LangChain4j 语义异常类型
    if (hasCause(failure, dev.langchain4j.exception.AuthenticationException.class)) {
        return new AuthenticationException("Authentication failed: " + message, failure);
    }
    
    if (hasCause(failure, dev.langchain4j.exception.RateLimitException.class)) {
        return new RateLimitException(
            "Rate limited: " + message,
            extractRetryAfter(message),
            failure
        );
    }
    
    // 3. 识别网络异常
    if (hasCause(failure, IOException.class) 
            || hasCause(failure, SocketTimeoutException.class)) {
        return new NetworkException("Network error: " + message, failure);
    }
    
    // 4. 解析 HTTP 状态码
    HttpException http = findCause(failure, HttpException.class);
    int status = http != null ? http.statusCode() : extractStatus(message);
    
    if (status > 0) {
        return classifyHttpError(status, message, extractRetryAfter(message), failure);
    }
    
    // 5. 文本模式匹配
    if (isContextTooLong(message.toLowerCase())) {
        return new ContextTooLongException("Context too long: " + message, failure);
    }
    
    // 6. 兜底：返回基类
    return new LlmException("Unexpected LLM error: " + message, failure);
}
```

**HTTP 状态码分类**：

```java
static LlmException classifyHttpError(int status, String body, 
                                       String retryAfter, Throwable cause) {
    return switch (status) {
        case 401, 403 -> new AuthenticationException(
            "Authentication failed (HTTP " + status + "): " + body, cause);
        
        case 429 -> new RateLimitException(
            "Rate limited (HTTP 429): " + body, retryAfter, cause);
        
        case 413 -> new ContextTooLongException(
            "Context too long: " + body, cause);
        
        default -> new LlmException(
            "API error (HTTP " + status + "): " + body, cause);
    };
}
```

**文本模式匹配**：

```java
private static boolean isContextTooLong(String lower) {
    return lower.contains("prompt is too long")
        || lower.contains("context length")
        || lower.contains("context window")
        || lower.contains("maximum context")
        || lower.contains("too many tokens")
        || lower.contains("max tokens");
}
```

**Retry-After 提取**：

```java
static String extractRetryAfter(String message) {
    // 匹配 "Retry-After: 7" 或 "retry after 7 seconds"
    Pattern pattern = Pattern.compile(
        "(?i)retry[- ]after\\s*[:=]\\s*(\\d+)(?:\\s*seconds?)?"
    );
    Matcher matcher = pattern.matcher(message);
    return matcher.find() ? matcher.group(1) : "";
}
```

### 流式事件传递

`StreamEvent.Error` 现在保留完整的类型化异常对象：

```java
record Error(LlmException exception) implements StreamEvent {
    
    public Error {
        Objects.requireNonNull(exception, "exception must not be null");
    }
    
    // 兼容构造函数（用于本地错误）
    public Error(String message) {
        this(new LlmException(message == null ? "Unknown LLM error" : message));
    }
    
    // 兼容方法（返回消息文本）
    public String message() {
        return exception.getMessage();
    }
}
```

**使用位置**：

```java
// 在回调中
@Override
public void onError(Throwable error) {
    put(queue, new StreamEvent.Error(LlmException.classify(error)));
}

// 在异常兜底中
catch (Throwable failure) {
    put(queue, new StreamEvent.Error(LlmException.classify(failure)));
}

// 本地解析错误
catch (JsonProcessingException e) {
    put(queue, new StreamEvent.Error(
        "Invalid tool arguments: " + e.getMessage()
    ));
}
```

### Agent 自动恢复策略

Agent 根据异常类型自动执行恢复策略：

#### 1. 上下文过长恢复

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
        
        // 重新注入长期记忆
        conv.resetLtmInjected();
        conv.injectLongTermMemory(instructions, memoryContent);
        
        continue;  // 重试
    }
}
```

**策略**：
- 最多重试 3 次
- 先裁剪工具结果
- 执行上下文压缩
- 重新注入长期记忆
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

### 实现状态

- ✅ **异常类型已定义**（4 种语义化类型）
- ✅ **HTTP 状态码分类**（401、403、413、429）
- ✅ **文本模式匹配**（识别上下文过长关键词）
- ✅ **Retry-After 提取**（从响应消息中解析）
- ✅ **Agent 自动重试**（上下文压缩、限流退避）
- ✅ **完整测试覆盖**（17 个测试验证所有场景）

### 类型安全的错误处理

从字符串匹配升级到类型判断：

```java
// ❌ 旧方式（字符串匹配，易出错）
switch (event) {
    case StreamEvent.Error err -> {
        if (err.message().contains("rate limit")) {
            // 重试逻辑
        } else if (err.message().contains("context")) {
            // 压缩逻辑
        }
    }
}

// ✅ 新方式（类型安全，编译器保证）
switch (event) {
    case StreamEvent.Error err -> {
        var exception = err.exception();
        
        if (exception instanceof LlmException.RateLimitException rateLimit) {
            String retryAfter = rateLimit.getRetryAfter();
            // Retry-After 是结构化数据，不是字符串解析
        }
        
        if (exception instanceof LlmException.ContextTooLongException) {
            // 类型明确，不会误判
        }
    }
}
```

### 向后兼容性

所有现有代码无需修改即可继续工作：

```java
// 兼容旧代码
StreamEvent.Error error1 = new StreamEvent.Error("error message");
String msg = error1.message();  // 仍然有效

// 新代码可使用类型化异常
LlmException exception = new LlmException.RateLimitException("limited", "5");
StreamEvent.Error error2 = new StreamEvent.Error(exception);
LlmException typed = error2.exception();  // 获取类型化异常
```

### 测试覆盖

完整的测试套件验证所有错误处理场景：

**LlmExceptionTest.java**（11 个测试）：
- ✅ HTTP 状态码分类（401、403、413、429）
- ✅ Retry-After 提取和解析
- ✅ 文本模式匹配（"prompt is too long"、"context window"）
- ✅ LangChain4j 异常包装
- ✅ CompletionException 解包
- ✅ StreamEvent.Error 兼容性

**AgentErrorRecoveryTest.java**（6 个测试）：
- ✅ 上下文过长触发压缩和重试
- ✅ 限流使用 Retry-After 或指数退避
- ✅ 认证错误不重试
- ✅ 网络错误不重试（当前行为）
- ✅ 指数退避算法验证
- ✅ Retry-After 解析验证

**测试结果**：
```
Tests run: 144
Failures: 0
Errors: 0
Skipped: 1
BUILD SUCCESS
```

---

**详细技术报告**：参见 [docs/错误处理改进完成报告.md](错误处理改进完成报告.md)

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
