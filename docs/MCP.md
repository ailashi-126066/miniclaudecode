# MCP 集成：基于官方 SDK 的实现

MewCode 通过 MCP（Model Context Protocol）接入外部工具服务器，让 AI Agent 获得文件系统访问、数据库查询、API 调用等扩展能力。本文解析基于官方 Java SDK 的 MCP 客户端实现。

---

## 1. 架构概览

### 1.1 核心依赖

```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.modelcontextprotocol</groupId>
    <artifactId>mcp-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

MewCode 使用官方 MCP Java SDK 而非手写协议实现，SDK 内部封装了：
- **JSON-RPC 2.0 协议**：请求/响应序列化、ID 生成、通知机制
- **传输层**：Stdio（子进程通信）和 HTTP（Server-Sent Events）
- **会话管理**：初始化握手、工具列表拉取、工具调用

### 1.2 模块结构

```
McpManager (209 行)
 ├─ Map<String, McpServerConfig> configs     // 服务器配置
 ├─ Map<String, McpSyncClient> clients       // SDK 客户端实例
 └─ McpToolWrapper (内部类)                   // 工具适配器
```

**关键设计**：
- **单文件设计**：所有 MCP 逻辑集中在 `McpManager.java`
- **SDK 封装**：不暴露 JSON-RPC 细节，调用 `client.initialize()` / `client.callTool()`
- **延迟加载**：所有 MCP 工具 `shouldDefer() = true`，通过 ToolSearchTool 按需激活

---

## 2. 加载时机

**MCP 不是程序启动时就加载，而是在用户选择 Provider 之后才异步连接。**

### 2.1 完整加载流程

```
程序启动 (MewCodeModel 构造函数)
  ↓ 只读取配置到 mcpServers 字段，mcpManager = null
AppState.PROVIDER_SELECT（多 Provider）或 AppState.CHAT（单 Provider）
  ↓ 用户在 TUI 中选择 Provider
用户按 Enter 键
  ↓ 触发 initializeProvider()
内置工具注册 (ToolRegistry.createDefault())
  ↓ ReadFile, WriteFile, Bash, Glob, Grep...
Agent 创建 (new Agent(...))
  ↓ 此时 Agent 已可用，用户可以开始对话
MCP 异步连接 (Thread.ofVirtual())  ← 第 618 行
  ↓ 虚拟线程后台连接，不阻塞主线程
mcpManager.connectAll()
  ↓ 遍历 mcpServers，逐个连接
MCP 工具注册 (registry.register())
  ↓ 连接完成后追加到工具列表
用户可使用 MCP 工具
```

### 2.2 谁调用谁

```
用户按 Enter 选择 Provider
  ↓
MewCodeModel.update()  处理键盘事件
  ↓
initializeProvider()  初始化所有组件
  ├─ 创建 ToolRegistry
  ├─ 注册内置工具（ReadFile, WriteFile...）
  ├─ 创建 Agent
  └─ 启动虚拟线程连接 MCP  ← 异步，不等结果直接返回
      ↓
      McpManager.connectAll()  遍历所有 MCP 服务器
        ↓
        createClient(config)  根据配置创建客户端
          ├─ 有 command → StdioClientTransport（启动子进程）
          └─ 有 url → HttpClientTransport（HTTP 请求）
        ↓
        client.initialize()  SDK 发送初始化握手
          ↓ JSON-RPC 请求
          MCP 服务器返回 instructions
        ↓
        client.listTools()  SDK 拉取工具列表
          ↓ JSON-RPC 请求
          MCP 服务器返回工具定义
        ↓
        new McpToolWrapper(...)  为每个工具创建适配器
        ↓
        registry.register(tool)  注册到工具池
```

**关键点**：
- `initializeProvider()` 是主入口，但它**不等待** MCP 连接完成
- 虚拟线程在后台跑，主线程继续往下执行
- 用户可能在 MCP 连接完成前就开始对话（只能用内置工具）
- 连接完成后，MCP 工具会**追加**到已有的工具列表

### 2.3 三个关键方法

**方法 1：initializeProvider() - 启动异步连接**

位置：`MewCodeModel.java:618`，作用：检查配置 → 启动虚拟线程

```java
if (!mcpServers.isEmpty()) {
    Thread.ofVirtual().name("mcp-connect").start(() -> {
        mcpManager = new McpManager(mcpServers);
        var result = mcpManager.connectAll();
        for (var t : result.tools()) registry.register(t);
    });
}
```

**方法 2：McpManager.connectAll() - 遍历所有服务器**

位置：`McpManager.java:43`，作用：逐个连接 → 收集工具和错误

```java
for (var entry : configs.entrySet()) {
    try {
        var client = createClient(cfg);      // 创建客户端
        client.initialize();                 // 初始化握手
        var result = client.listTools();     // 拉取工具列表
        for (var sdkTool : result.tools()) {
            tools.add(new McpToolWrapper(name, sdkTool, client));
        }
    } catch (Exception e) {
        errors.add("MCP server '" + name + "': " + e.getMessage());
    }
}
```

**方法 3：createClient() - 选择传输层**

位置：`McpManager.java:87`，作用：根据配置决定用 Stdio 还是 HTTP

```java
if (cfg.getCommand() != null) {
    // Stdio：启动子进程（npx、node 等）
    transport = new StdioClientTransport(...);
} else if (cfg.getUrl() != null) {
    // HTTP：发送 POST 请求到远程服务器
    transport = new HttpClientStreamableHttpTransport(...);
}
return McpClient.sync(transport).build();
```

### 2.4 为什么异步加载

**问题**：MCP 服务器可能在远程，或者启动子进程需要时间（几百毫秒到几秒）

**方案**：用虚拟线程后台连接，主线程继续初始化 Agent

**效果**：
- TUI 不会卡住，用户立即可以输入
- 内置工具（ReadFile、WriteFile）可以马上用
- MCP 工具连接完成后自动追加到工具列表
- 连接失败也不影响程序启动

**时机对比**：

| 组件 | 加载时机 | 是否阻塞 | 用户能否使用 |
|-----|---------|---------|------------|
| 内置工具 | `initializeProvider()` 同步 | 是 | ✅ 立即可用 |
| Agent | `initializeProvider()` 同步 | 是 | ✅ 立即可用 |
| **MCP 工具** | 虚拟线程异步 | **否** | ⏳ 连接完成后可用 |

---

## 3. 连接流程详解

### 3.1 配置加载

**位置**：`AppConfig.java` 读取 YAML 配置

```yaml
# config.yaml
mcpServers:
  - name: filesystem
    command: npx
    args: ["-y", "@modelcontextprotocol/server-filesystem", "/home/user"]
    env:
      LOG_LEVEL: debug
  
  - name: github
    url: https://api.example.com/mcp
    headers:
      Authorization: Bearer ${GITHUB_TOKEN}
```

**配置类型**：`McpServerConfig.java`

```java
public class McpServerConfig {
    private String name;           // 服务器标识
    private String command;        // Stdio 模式：启动命令
    private List<String> args;     // Stdio 模式：命令参数
    private String url;            // HTTP 模式：服务器 URL
    private Map<String, String> headers;   // HTTP 模式：自定义 Header
    private Map<String, String> env;       // Stdio 模式：环境变量注入
}
```

### 2.2 启动时连接

**调用链**：
```
MewCodeModel.init()                           // TUI 初始化
  → Thread.ofVirtual().start()                // 虚拟线程异步连接
    → mcpManager = new McpManager(mcpServers)
      → mcpManager.connectAll()
        → 遍历配置 → createClient() → client.initialize()
```

**代码位置**：`MewCodeModel.java:623`

```java
if (!mcpServers.isEmpty()) {
    mcpConnecting = true;
    final var registryRef = registry;
    Thread.ofVirtual().name("mcp-connect").start(() -> {
        try {
            mcpManager = new McpManager(mcpServers);
            var result = mcpManager.connectAll();
            for (var t : result.tools()) registryRef.register(t);
            mcpServerInfo = "Connected to %d MCP server(s), %d tools registered"
                .formatted(result.servers().size(), result.tools().size());
            // ... 错误处理和 system prompt 拼接 ...
        } catch (Exception e) {
            mcpServerInfo = "MCP connection failed: " + e.getMessage();
        } finally {
            mcpConnecting = false;
        }
    });
}
```

**关键点**：
- **非阻塞启动**：虚拟线程异步连接，不阻塞 TUI 主线程
- **容错机制**：单个服务器失败不影响其他服务器，错误收集到 `result.errors()`
- **System Prompt 注入**：连接成功后，服务器返回的 `instructions` 会拼接到 System Prompt

### 2.3 连接核心逻辑

**位置**：`McpManager.java:43-72`

```java
public ConnectResult connectAll() {
    var tools = new ArrayList<Tool>();
    var servers = new ArrayList<ServerInfo>();
    var errors = new ArrayList<String>();

    for (var entry : configs.entrySet()) {
        String name = entry.getKey();
        var cfg = entry.getValue();

        try {
            // 1. 创建传输层客户端
            var client = createClient(cfg);
            
            // 2. 初始化握手（发送 initialize 请求 + notifications/initialized 通知）
            client.initialize();
            
            // 3. 保存客户端实例
            clients.put(name, client);

            // 4. 提取服务器使用说明
            String instructions = client.getServerInstructions();
            servers.add(new ServerInfo(name, instructions != null ? instructions : ""));

            // 5. 拉取工具列表
            var result = client.listTools();
            if (result != null && result.tools() != null) {
                for (var sdkTool : result.tools()) {
                    tools.add(new McpToolWrapper(name, sdkTool, client));
                }
            }
        } catch (Exception e) {
            errors.add("MCP server '" + name + "': " + e.getMessage());
        }
    }

    return new ConnectResult(List.copyOf(tools), List.copyOf(servers), List.copyOf(errors));
}
```

**返回值**：
```java
public record ConnectResult(
    List<Tool> tools,          // 注册成功的工具列表
    List<ServerInfo> servers,  // 服务器信息（含使用说明）
    List<String> errors        // 连接失败的错误消息
) {}
```

---

## 3. 传输层实现

### 3.1 传输层选择

**位置**：`McpManager.java:87-121`

```java
private McpSyncClient createClient(McpServerConfig cfg) {
    io.modelcontextprotocol.spec.McpClientTransport transport;

    // 策略 1：有 command 就走 Stdio（子进程）
    if (cfg.getCommand() != null && !cfg.getCommand().isBlank()) {
        var paramsBuilder = ServerParameters.builder(windowsSafe(cfg.getCommand()));
        if (cfg.getArgs() != null) {
            paramsBuilder.args(cfg.getArgs());
        }
        if (cfg.getEnv() != null) {
            var resolvedEnv = new HashMap<String, String>();
            for (var e : cfg.getEnv().entrySet()) {
                resolvedEnv.put(e.getKey(), resolveEnvVars(e.getValue()));
            }
            paramsBuilder.env(resolvedEnv);
        }
        transport = new StdioClientTransport(paramsBuilder.build(), McpJsonDefaults.getMapper());
    } 
    // 策略 2：有 url 就走 HTTP
    else if (cfg.getUrl() != null && !cfg.getUrl().isBlank()) {
        var httpBuilder = HttpClientStreamableHttpTransport.builder(cfg.getUrl());
        if (cfg.getHeaders() != null && !cfg.getHeaders().isEmpty()) {
            httpBuilder.customizeRequest(rb -> {
                for (var e : cfg.getHeaders().entrySet()) {
                    rb.header(e.getKey(), resolveEnvVars(e.getValue()));
                }
            });
        }
        transport = httpBuilder.build();
    } 
    // 策略 3：配置不完整，报错
    else {
        throw new IllegalArgumentException("Neither command nor url configured");
    }

    // 构建同步客户端
    return McpClient.sync(transport)
            .clientInfo(new McpSchema.Implementation("mewcode", "0.1.0"))
            .requestTimeout(Duration.ofSeconds(60))
            .build();
}
```

**决策逻辑**：
| 配置字段 | 传输方式 | SDK 类型 |
|---------|---------|---------|
| `command` 非空 | Stdio（子进程通信） | `StdioClientTransport` |
| `url` 非空 | HTTP（SSE 流式响应） | `HttpClientStreamableHttpTransport` |
| 都为空 | 抛异常 | - |

### 3.2 Stdio 传输（子进程模式）

**SDK 内部行为**（无需手写）：
1. **启动子进程**：`ProcessBuilder` 启动 `command` + `args`
2. **环境变量注入**：通过 `ProcessBuilder.environment()` 设置 `env`
3. **stdio 通信**：
   - 写入 stdin：JSON-RPC 请求（每条消息一行）
   - 读取 stdout：JSON-RPC 响应（换行符分隔）
   - stderr 消费：防止缓冲区满导致子进程阻塞
4. **初始化握手**：
   ```json
   → {"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}
   ← {"jsonrpc":"2.0","id":1,"result":{"instructions":"..."}}
   → {"jsonrpc":"2.0","method":"notifications/initialized"}
   ```

**Windows 兼容处理**：

```java
// McpManager.java:126-131
static String windowsSafe(String command) {
    if (!System.getProperty("os.name", "").toLowerCase().contains("win")) 
        return command;
    String base = command.toLowerCase();
    if (WIN_CMD_SUFFIXED.contains(base)) 
        return command + ".cmd";  // npx → npx.cmd
    return command;
}

private static final Set<String> WIN_CMD_SUFFIXED = Set.of(
    "npx", "npm", "node", "uvx", "uv", "pnpm", "yarn", "bunx");
```

**为什么需要**：Windows 的 npm 全局命令实际是 `.cmd` 批处理文件，直接调用 `npx` 会找不到命令。

### 3.3 HTTP 传输（SSE 流式响应）

**SDK 内部行为**（无需手写）：
1. **HTTP 请求**：`java.net.http.HttpClient` 发送 POST 请求
2. **Header 注入**：通过 `customizeRequest()` 回调设置自定义 Header
3. **响应解析**：自动处理两种格式
   - `application/json`：直接反序列化 JSON-RPC 响应
   - `text/event-stream`：解析 SSE 格式的 `data:` 行
4. **会话维持**：自动管理 `Mcp-Session-Id` Header

**环境变量展开**：

```java
// McpManager.java:137-143
static String resolveEnvVars(String value) {
    if (value == null) return null;
    return ENV_VAR.matcher(value).replaceAll(m -> {
        String env = System.getenv(m.group(1));
        return env != null ? env : m.group(0);  // 找不到就保留原文
    });
}

private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([^}]+)}");
```

**示例**：`Authorization: Bearer ${GITHUB_TOKEN}` → `Authorization: Bearer ghp_xxxx`

---

## 4. 工具适配

### 4.1 McpToolWrapper 设计

MCP 服务器返回的工具定义需要适配成 MewCode 的 `Tool` 接口。

**位置**：`McpManager.java:147-196`

```java
private static class McpToolWrapper implements Tool {
    private final String serverName;         // 服务器名（filesystem、github）
    private final McpSchema.Tool sdkTool;    // SDK 的工具定义
    private final McpSyncClient client;      // 客户端实例（用于调用）

    @Override
    public String name() {
        // 格式：mcp__<server>__<tool>
        return "mcp__" + sanitizeName(serverName) + "__" + sanitizeName(sdkTool.name());
    }

    @Override
    public String description() {
        return sdkTool.description() != null ? sdkTool.description() : "";
    }

    @Override
    public ToolCategory category() { 
        return ToolCategory.COMMAND; 
    }

    @Override
    public boolean shouldDefer() { 
        return true;  // 所有 MCP 工具延迟加载
    }

    @Override
    public Map<String, Object> schema() {
        // 从 SDK 的 McpSchema.JsonSchema 转换为通用 Map
        var input = new LinkedHashMap<String, Object>();
        var jsonSchema = sdkTool.inputSchema();
        if (jsonSchema != null) {
            if (jsonSchema.type() != null) 
                input.put("type", jsonSchema.type());
            if (jsonSchema.properties() != null) 
                input.put("properties", jsonSchema.properties());
            if (jsonSchema.required() != null) 
                input.put("required", jsonSchema.required());
        }
        // 空 schema 给默认值（防止 LLM 困惑）
        if (input.isEmpty()) {
            input.put("type", "object");
            input.put("properties", Map.of());
        }
        return Map.of(
            "name", name(), 
            "description", description(), 
            "input_schema", input
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        try {
            var request = new McpSchema.CallToolRequest(
                sdkTool.name(),                    // 原始工具名（不带前缀）
                args != null ? args : Map.of()
            );
            var result = client.callTool(request);
            String text = extractTextContent(result);
            boolean isError = result.isError() != null && result.isError();
            return isError ? ToolResult.error(text) : ToolResult.success(text);
        } catch (Exception e) {
            return ToolResult.error("MCP tool call failed: " + e.getMessage());
        }
    }
}
```

### 4.2 工具命名规则

**名称格式**：`mcp__<server>__<tool>`

**示例**：
- 服务器 `filesystem` 的工具 `read_file` → `mcp__filesystem__read_file`
- 服务器 `github-api` 的工具 `list-repos` → `mcp__github_api__list_repos`

**名称清理**：

```java
// McpManager.java:133-135
static String sanitizeName(String name) {
    return NON_ALNUM.matcher(name).replaceAll("_");
}
private static final Pattern NON_ALNUM = Pattern.compile("[^a-zA-Z0-9_]");
```

**为什么**：LLM 调用工具时需要精确匹配名称，非字母数字字符可能导致解析错误。

### 4.3 内容提取

MCP 工具返回的 `CallToolResult` 包含多态内容列表（文本、图片、嵌入资源），MewCode 只提取文本块。

**位置**：`McpManager.java:198-208`

```java
private static String extractTextContent(McpSchema.CallToolResult result) {
    if (result.content() == null || result.content().isEmpty()) 
        return "(no output)";
    
    var sb = new StringBuilder();
    for (var content : result.content()) {
        if (content instanceof McpSchema.TextContent tc) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append(tc.text());
        }
        // 图片和嵌入资源被忽略
    }
    return sb.isEmpty() ? "(no output)" : sb.toString();
}
```

**处理逻辑**：
- 多个文本块用换行符拼接
- 空结果返回 `(no output)`（明确信号，避免 LLM 困惑）
- 图片/资源块被跳过（命令行场景下无法展示）

---

## 5. 延迟加载机制

### 5.1 为什么需要延迟加载

MCP 服务器可能注册了几十上百个工具（如 GitHub API 有 50+ 端点），全部塞进 system prompt 会：
- **挤爆上下文窗口**：每个工具 schema ~100-500 token
- **降低工具调用精度**：工具列表过长，LLM 容易选错

**解决方案**：
1. **默认隐藏**：`shouldDefer() = true`，工具不出现在初始工具列表
2. **按需激活**：LLM 通过 `ToolSearch` 搜索工具，找到后标记为 `discovered`
3. **持久生效**：一旦 `discovered`，后续轮次自动包含在工具列表中

### 5.2 ToolSearchTool 工作流

**位置**：`ToolSearchTool.java:93-147`

```java
@Override
public ToolResult execute(Map<String, Object> args) {
    String query = stringArg(args, "query", "");
    int maxResults = intArg(args, "max_results", 5);
    List<Map<String, Object>> schemas;

    // 精确查找：select:ToolName1,ToolName2
    if (query.startsWith("select:")) {
        List<String> names = Arrays.stream(query.substring("select:".length()).split(","))
                .map(String::trim)
                .toList();
        schemas = registry.findDeferredByNames(names, protocol);
    } 
    // 关键词搜索：在名称和描述中查找
    else {
        schemas = registry.searchDeferred(query, maxResults, protocol);
    }

    // 标记为已发现
    for (var s : schemas) {
        Object nameObj = s.get("name");
        if (nameObj instanceof String n) {
            registry.markDiscovered(n);  // 加入 discoveredTools 集合
        }
    }

    return ToolResult.success(
        "Found " + schemas.size() + " tool(s). Their full schemas are now loaded " +
        "and will be available in subsequent requests.\n\n" + schemasJson
    );
}
```

**搜索策略**：

| 查询格式 | 匹配逻辑 | 实现方法 |
|---------|---------|---------|
| `select:mcp__filesystem__read_file,mcp__github__list_repos` | 精确匹配工具名（不区分大小写） | `findDeferredByNames()` |
| `file system` | 名称或描述包含关键词 | `searchDeferred()` |

**关键词搜索实现**（`ToolRegistry.java:73-95`）：

```java
public List<Map<String, Object>> searchDeferred(String query, int maxResults, String protocol) {
    String lower = query.toLowerCase();
    var matches = new ArrayList<Map<String, Object>>();
    for (var tool : tools.values()) {
        if (!tool.shouldDefer()) continue;  // 只搜索延迟工具
        if (tool.name().toLowerCase().contains(lower)
                || tool.description().toLowerCase().contains(lower)) {
            var base = tool.schema();
            // 协议适配（OpenAI vs Anthropic）
            if (isOpenAIProtocol(protocol)) {
                matches.add(Map.of(
                    "type", "function",
                    "name", base.get("name"),
                    "description", base.get("description"),
                    "parameters", base.get("input_schema")
                ));
            } else {
                matches.add(base);
            }
            if (matches.size() >= maxResults) break;  // 够数就停
        }
    }
    return matches;
}
```

### 5.3 发现状态管理

**位置**：`ToolRegistry.java:21-26`

```java
private final Set<String> discoveredTools = ConcurrentHashMap.newKeySet();

public void markDiscovered(String name) {
    discoveredTools.add(name);
}

public boolean isDiscovered(String name) {
    return discoveredTools.contains(name);
}
```

**生成工具列表时的过滤**（`ToolRegistry.java:48-65`）：

```java
public List<Map<String, Object>> getAllSchemas(String protocol) {
    var schemas = new ArrayList<Map<String, Object>>();
    for (var tool : tools.values()) {
        // 跳过未被发现的延迟工具
        if (tool.shouldDefer() && !discoveredTools.contains(tool.name())) 
            continue;
        
        var base = tool.schema();
        // 协议转换...
        schemas.add(base);
    }
    return schemas;
}
```

**执行流程**：

```
第 1 轮：LLM 看到工具列表
  [ReadFile, WriteFile, EditFile, Bash, Glob, Grep, ToolSearch]

LLM 需要读取 GitHub 仓库文件，调用 ToolSearch
  → query: "github file"
  → 找到：mcp__github__read_repo_file
  → markDiscovered("mcp__github__read_repo_file")

第 2 轮：LLM 看到工具列表
  [ReadFile, WriteFile, EditFile, Bash, Glob, Grep, ToolSearch, mcp__github__read_repo_file]

LLM 直接调用 mcp__github__read_repo_file
```

---

## 6. 完整调用链

### 6.1 从启动到工具执行

```
1. TUI 启动
   MewCodeModel.init()
     → Thread.ofVirtual().start(() -> {
         mcpManager = new McpManager(mcpServers);
         var result = mcpManager.connectAll();
         for (var t : result.tools()) registry.register(t);
       })

2. Agent Loop 第 1 轮
   registry.getAllSchemas(protocol)
     → 跳过 shouldDefer=true 且未 discovered 的工具
     → 返回 [ReadFile, WriteFile, ..., ToolSearch]

3. LLM 决策
   用户："读取 GitHub 仓库的 README.md"
   LLM："我需要访问 GitHub，先搜索工具"
   → 调用 ToolSearch(query="github read file")

4. ToolSearch 执行
   registry.searchDeferred("github read file", 5, protocol)
     → 找到 mcp__github__read_repo_file
     → registry.markDiscovered("mcp__github__read_repo_file")
     → 返回工具 schema

5. Agent Loop 第 2 轮
   registry.getAllSchemas(protocol)
     → mcp__github__read_repo_file 已 discovered，包含在列表中

6. LLM 调用工具
   → tool_use: {name: "mcp__github__read_repo_file", args: {...}}

7. StreamingExecutor 执行
   tool = registry.get("mcp__github__read_repo_file")
   → McpToolWrapper.execute(args)
     → client.callTool(new CallToolRequest("read_repo_file", args))
       → SDK 发送 JSON-RPC 请求到 MCP 服务器
       → 服务器返回 CallToolResult
     → extractTextContent(result)
   → ToolResult.success(text)
```

### 6.2 错误处理

**连接失败**（`McpManager.java:66-68`）：

```java
catch (Exception e) {
    errors.add("MCP server '" + name + "': " + e.getMessage());
}
```

单个服务器失败不阻断其他服务器，错误收集到 `ConnectResult.errors()`，最终显示在 TUI 聊天界面。

**工具调用失败**（`McpManager.java:192-194`）：

```java
catch (Exception e) {
    return ToolResult.error("MCP tool call failed: " + e.getMessage());
}
```

异常转换为 `ToolResult.error`，LLM 会看到错误信息并尝试恢复（如重试、换工具）。

---

## 7. 关键设计总结

| 设计点 | 实现方式 | 优势 |
|-------|---------|------|
| **SDK 封装** | 使用官方 `McpClient.sync()` | 协议升级无需改代码，专注业务逻辑 |
| **传输选择** | `command` 非空走 Stdio，`url` 非空走 HTTP | 配置驱动，同时支持本地和远程服务器 |
| **异步连接** | 虚拟线程 `Thread.ofVirtual()` | 不阻塞 TUI 主线程，连接失败不影响启动 |
| **容错机制** | 捕获异常收集到 `errors` 列表 | 单个服务器失败不影响其他服务器 |
| **工具命名** | `mcp__<server>__<tool>` + 字符清理 | 避免命名冲突，LLM 精确匹配 |
| **延迟加载** | `shouldDefer() = true` + ToolSearch | 节省上下文窗口，按需激活工具 |
| **环境变量** | 正则替换 `${VAR}` | 敏感信息（Token）不写入配置文件 |
| **Windows 兼容** | `windowsSafe()` 添加 `.cmd` 后缀 | npm 全局命令在 Windows 上正常工作 |
| **内容提取** | 只提取 `TextContent` | 命令行场景下忽略无法展示的图片 |

---

## 8. 使用示例

### 8.1 配置文件

```yaml
# config.yaml
mcpServers:
  # 本地文件系统访问
  - name: filesystem
    command: npx
    args: ["-y", "@modelcontextprotocol/server-filesystem", "/home/user/projects"]
  
  # GitHub API（需要环境变量 GITHUB_TOKEN）
  - name: github
    url: https://api.github.com/mcp
    headers:
      Authorization: Bearer ${GITHUB_TOKEN}
      Accept: application/vnd.github.v3+json
```

### 8.2 对话示例

```
用户: 列出 /home/user/projects 下的所有 Python 文件

AI: 我需要访问文件系统，先搜索相关工具。
    [调用 ToolSearch(query="filesystem list")]
    → 找到：mcp__filesystem__list_directory
    
    [调用 mcp__filesystem__list_directory(path="/home/user/projects")]
    → 返回：[main.py, utils.py, test.py]
    
    找到 3 个 Python 文件：main.py、utils.py、test.py

用户: 读取 main.py 的内容

AI: [调用 mcp__filesystem__read_file(path="/home/user/projects/main.py")]
    → 返回文件内容...
```

---

## 9. 与工具系统集成

MCP 工具在 MewCode 中与内置工具（ReadFile、WriteFile 等）地位完全平等：

1. **注册阶段**：`registry.register(new McpToolWrapper(...))` 加入工具池
2. **发现阶段**：ToolSearch 统一搜索内置工具和 MCP 工具
3. **执行阶段**：StreamingExecutor 无差别调用 `tool.execute(args)`
4. **权限检查**：MCP 工具同样经过 PermissionChecker 审批流程

**唯一差异**：MCP 工具的 `shouldDefer()` 固定返回 `true`，默认不出现在工具列表，需要 ToolSearch 激活。

---

## 10. 代码文件清单

| 文件 | 职责 | 行数 |
|-----|------|-----|
| `McpManager.java` | MCP 客户端管理 + 工具适配 | 209 |
| `McpServerConfig.java` | 服务器配置数据类 | 40 |
| `ToolSearchTool.java` | 延迟工具搜索与激活 | 160 |
| `ToolRegistry.java` | 工具注册与发现状态管理 | 144 |
| `MewCodeModel.java` | TUI 启动时异步连接 MCP | ~20 行相关代码 |

**总代码量**：~573 行（含注释），其中核心逻辑 ~400 行。
