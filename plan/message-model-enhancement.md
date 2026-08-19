# Message 模型增强计划

## 背景

当前的 `Message` 类设计过于简化，缺少完整的元数据支持。虽然已有明确的内部层/API层分层架构，但内部层缺少状态管理、唯一标识、性能追踪等能力。

## 当前问题

### 0. LangChain4j 不做消息验证（前置说明）

**重要**：LangChain4j 框架**只是传递消息**，不做以下验证：

- ❌ 不过滤 system/error 消息
- ❌ 不合并相邻同角色消息
- ❌ 不验证首条是否为 user
- ❌ 不验证角色是否交替

这些规则是**各个 LLM API 的要求**（Anthropic、OpenAI、AWS Bedrock），但 LangChain4j 只是把你传入的消息直接发送给 API。如果不符合规则，**会在 API 层报错**。

**举例**：
```
❌ 错误场景：连续两条 user 消息
API 报错：400 Bad Request - "messages must alternate between user and assistant"

❌ 错误场景：首条是 assistant 消息
API 报错：400 Bad Request - "first message must be from user"
```

**因此应用层必须在转换时做验证和过滤**，而不是依赖 API 报错。

---

### 1. 缺少唯一标识
- **问题**：无法精确定位和更新特定消息
- **影响**：流式更新时只能依赖"最后一条 assistant 消息"这种脆弱假设
- **风险**：并发场景或消息顺序变化时会更新错消息

### 2. 缺少状态管理
- **问题**：无法区分消息生命周期（streaming / complete / error）
- **影响**：
  - UI 无法显示加载动画或错误提示
  - 无法过滤 error 状态的消息（会发给 API 让模型困惑）
  - 无法判断消息是否接收完成

### 3. 缺少元数据
- **问题**：没有时间戳、token 用量、响应耗时
- **影响**：
  - 无法统计每次对话的成本
  - 无法分析性能瓶颈
  - 无法实现 token 预算控制

### 4. 角色类型不安全
- **问题**：role 是 String，没有类型约束
- **影响**：可能出现拼写错误（"assitant" vs "assistant"）

### 5. 缺少消息验证逻辑
- **问题**：toMessages() 不验证消息顺序和角色交替
- **影响**：
  - 连续同角色消息直接发给 API → API 报错 400
  - 首条不是 user → API 报错 400
  - 依赖 API 报错才发现问题，调试困难

## 改进目标

### 核心目标
增强内部层 `Message` 模型，使其具备完整的元数据和状态管理能力，同时保持与 API 层的清晰分离。

### 非目标
- ❌ 不修改 API 层（LangChain4j 的 ChatMessage）
- ❌ 不改变现有的 toMessages() 转换逻辑（除非必要）
- ❌ 不破坏现有测试

## 设计方案

### 方案 A：渐进式增强（推荐）

保持现有 `Message` 类，逐步添加新字段：

```java
public class Message {
    // === 现有字段（保持不变）===
    private String role;
    private String content;
    private List<ThinkingBlock> thinkingBlocks;
    private List<ToolUseBlock> toolUses;
    private List<ToolResultBlock> toolResults;
    
    // === 新增字段 ===
    private String id;                    // 唯一标识，UUID
    private MessageStatus status;         // 状态：STREAMING / COMPLETE / ERROR
    private long createdAt;               // 创建时间戳（毫秒）
    private UsageInfo usage;              // Token 用量（可选）
    private Long responseTimeMs;          // 响应耗时（可选）
    
    // 构造函数保持向后兼容
    public Message(String role, String content) {
        this.id = UUID.randomUUID().toString();
        this.role = role;
        this.content = content;
        this.status = MessageStatus.COMPLETE;  // 默认完成状态
        this.createdAt = System.currentTimeMillis();
    }
}
```

**优点**：
- ✅ 向后兼容，不破坏现有代码
- ✅ 可以分阶段实现（先加 ID，再加状态，最后加元数据）
- ✅ 测试改动最小

**缺点**：
- ⚠️ 类会变得更大
- ⚠️ 可选字段可能被遗忘

### 方案 B：引入枚举类型

将 role 从 String 改为枚举：

```java
public enum MessageRole {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    TOOL("tool");
    
    private final String value;
    MessageRole(String value) { this.value = value; }
    public String getValue() { return value; }
}
```

**优点**：
- ✅ 类型安全，编译期检查
- ✅ IDE 自动补全

**缺点**：
- ❌ 破坏性变更，需要修改所有使用 role 的代码
- ❌ 如果未来需要支持新角色，需要修改枚举

### 方案 C：分离元数据类

创建独立的元数据类：

```java
public class MessageMetadata {
    private String id;
    private MessageStatus status;
    private long createdAt;
    private UsageInfo usage;
    private Long responseTimeMs;
}

public class Message {
    private String role;
    private String content;
    private MessageMetadata metadata;  // 元数据封装
    // ... 其他字段
}
```

**优点**：
- ✅ 关注点分离
- ✅ Message 类不会过度膨胀

**缺点**：
- ⚠️ 访问元数据需要多一层：msg.getMetadata().getId()
- ⚠️ 需要考虑 metadata 为 null 的情况

## 推荐实施方案

**采用方案 A（渐进式增强）+ 部分方案 B（状态枚举）**

### 第一阶段：添加核心字段

1. 添加 `id`、`status`、`createdAt` 字段
2. 创建 `MessageStatus` 枚举
3. 修改构造函数自动生成 ID 和时间戳
4. 保持 role 为 String（暂不改动，避免大范围修改）

```java
public enum MessageStatus {
    STREAMING,   // 正在流式接收
    COMPLETE,    // 接收完成
    ERROR        // 接收出错
}

public class Message {
    private String id;                          // 新增
    private MessageStatus status;               // 新增
    private long createdAt;                     // 新增
    
    private String role;                        // 保持不变
    private String content;
    private List<ThinkingBlock> thinkingBlocks;
    private List<ToolUseBlock> toolUses;
    private List<ToolResultBlock> toolResults;
    
    public Message(String role, String content) {
        this.id = UUID.randomUUID().toString();
        this.role = role;
        this.content = content;
        this.status = MessageStatus.COMPLETE;
        this.createdAt = System.currentTimeMillis();
    }
    
    // Getters and Setters
}
```

### 第二阶段：添加元数据字段

1. 创建 `UsageInfo` 类
2. 添加 `usage` 和 `responseTimeMs` 字段
3. 在流式响应完成时记录元数据

```java
public class UsageInfo {
    private int inputTokens;
    private int outputTokens;
    private int totalTokens;
    
    // Constructor, Getters, Setters
}

public class Message {
    // ... 第一阶段字段
    private UsageInfo usage;           // 新增
    private Long responseTimeMs;       // 新增（Long 允许 null）
}
```

### 第三阶段：应用状态管理

1. 在流式响应开始时创建 STREAMING 状态的消息
2. 在流式响应完成时更新为 COMPLETE 状态
3. 在出错时更新为 ERROR 状态
4. 在 `toMessages()` 中过滤 ERROR 状态的消息

## 需要修改的文件

### 核心类
- ✏️ `src/main/java/com/mewcode/conversation/Message.java` - 添加字段
- ✏️ `src/main/java/com/mewcode/conversation/MessageStatus.java` - 新建枚举
- ✏️ `src/main/java/com/mewcode/conversation/UsageInfo.java` - 新建类

### 使用方代码
- ✏️ `src/main/java/com/mewcode/conversation/ConversationManager.java` - 可能需要添加按 ID 查找的方法
- ✏️ `src/main/java/com/mewcode/llm/LangChainClient.java` - toMessages() 中过滤 ERROR 消息
- ✏️ `src/main/java/com/mewcode/agent/Agent.java` - 流式响应时更新消息状态

### 测试
- ✏️ `src/test/java/com/mewcode/conversation/MessageTest.java` - 新建测试类
- ✏️ 现有涉及 Message 的测试 - 可能需要微调

## 实施步骤

### Step 1: 添加基础字段和枚举
1. 创建 `MessageStatus` 枚举
2. 在 `Message` 类中添加 `id`、`status`、`createdAt`
3. 修改构造函数
4. 添加 getter/setter
5. 运行测试确保没有破坏现有功能

### Step 2: 应用到流式响应
1. 在 `Agent.agentLoop()` 中创建 STREAMING 状态的消息
2. 在流式响应完成时更新状态为 COMPLETE
3. 在出错时更新状态为 ERROR
4. 添加按 ID 查找消息的方法（如需要）

### Step 3: 在转换时过滤
1. 修改 `toMessages()` 方法
2. 跳过 status == ERROR 的消息
3. 添加单元测试验证过滤逻辑

### Step 4: 添加元数据支持
1. 创建 `UsageInfo` 类
2. 在 `Message` 中添加 `usage` 和 `responseTimeMs`
3. 在流式响应回调中提取 usage 信息
4. 记录响应耗时

### Step 5: 文档更新
1. 更新 `docs/LLM客户端与流式响应.md`
2. 补充消息状态状态机图
3. 补充元数据使用示例

## 向后兼容性

### 保证兼容的措施
1. 构造函数签名不变：`new Message(role, content)` 仍然有效
2. 新字段有默认值（id 自动生成，status 默认 COMPLETE）
3. 现有 getter/setter 不改变
4. toMessages() 的行为不变（只是多了 ERROR 过滤）

### 潜在影响
- ⚠️ 序列化/反序列化：如果 Message 被序列化到文件/数据库，需要处理字段不存在的情况
- ⚠️ 测试断言：部分测试可能需要适配新字段（如比较 Message 对象时）

## 测试计划

### 单元测试
```java
@Test
void testMessageHasUniqueId() {
    Message m1 = new Message("user", "hello");
    Message m2 = new Message("user", "hello");
    assertNotEquals(m1.getId(), m2.getId());
}

@Test
void testMessageDefaultStatus() {
    Message m = new Message("user", "hello");
    assertEquals(MessageStatus.COMPLETE, m.getStatus());
}

@Test
void testToMessagesFilterError() {
    List<Message> messages = List.of(
        new Message("user", "hello"),
        createErrorMessage("assistant", "failed"),
        new Message("user", "retry")
    );
    
    List<ChatMessage> result = toMessages(messages, "");
    assertEquals(2, result.size());  // error 消息被过滤
}
```

### 集成测试
- 流式响应场景：验证状态从 STREAMING → COMPLETE
- 错误场景：验证状态变为 ERROR 且不发送给 API
- 元数据场景：验证 usage 和 responseTime 被正确记录

## 风险与缓解

### 风险 1：序列化兼容性
- **问题**：旧版本保存的 Message 没有新字段
- **缓解**：
  - 新字段使用包装类型（Long 而非 long）允许 null
  - 反序列化时给缺失字段赋默认值
  - 添加迁移脚本（如果持久化到数据库）

### 风险 2：性能开销
- **问题**：每个 Message 多了几个字段，内存占用增加
- **缓解**：
  - 元数据字段（usage、responseTimeMs）是可选的，不用时为 null
  - 实际开销很小（每个消息多几十字节）

### 风险 3：并发问题
- **问题**：流式更新时多线程修改 Message 状态
- **缓解**：
  - Message 本身已经是可变的（有 setter）
  - 保持当前的线程模型（单线程更新对话历史）
  - 如需并发，可以在 ConversationManager 加锁

## 参考资料

- 图片描述：消息状态状态机（streaming → complete / error）
- 图片描述：API 消息转换管道（过滤 → 转换 → 合并 → 交替验证）
- 现有代码：[Message.java](../src/main/java/com/mewcode/conversation/Message.java)
- 现有代码：[LangChainClient.java](../src/main/java/com/mewcode/llm/LangChainClient.java)

## 后续优化（可选）

### 阶段 4+（未来考虑）
1. 引入 MessageRole 枚举替代 String
2. 实现消息合并逻辑（相邻同角色消息）
3. 实现角色交替验证
4. 添加消息编辑/删除功能
5. 实现消息搜索/过滤 API
6. 支持消息持久化到数据库

---

**创建时间**：2026-08-19  
**作者**：Claude (Opus 5)  
**状态**：待评审
