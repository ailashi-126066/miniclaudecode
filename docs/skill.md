# Skill：从磁盘文件到 Agent 调用

本文只描述 **TUI 模式** 的完整 Skill 实现；Print 与 Remote 当前不提供可用的 Skill 激活入口。先看 Skill 如何被扫描和保存，再看它如何通过两种入口进入 Agent：普通文本触发 `LoadSkill`，或用户直接输入 `/skill-name`。

相关文件如下：

- `src/main/java/com/mewcode/skill/SkillCatalog.java`：Skill 目录扫描、文件解析、查询和重载。
- `src/main/java/com/mewcode/tui/MewCodeModel.java`：初始化 catalog、工具、命令和对话。
- `src/main/java/com/mewcode/tool/impl/LoadSkillTool.java`：`LoadSkill` 工具的实现。
- `src/main/java/com/mewcode/agent/Agent.java`、`StreamingExecutor.java`：模型工具调用的执行路径。
- `src/main/java/com/mewcode/command/CommandRegistry.java`：Slash Command 的 handler 注册和执行。
- `src/main/java/com/mewcode/conversation/ConversationManager.java`：对话消息与 system reminder 的保存。

---

## 1. 先看整体路径

同一个 Skill 有两条入口，后半段都会调用 `agent.run(conversation)`。两条入口都会通过 `SkillActivator` 热重读正文、替换参数，并把正文只作为一条 system reminder 写入 conversation。

```text
普通文本
  ↓
模型在 Available Skills 中找到匹配项
  ↓
模型调用 LoadSkill(name)
  ↓
LoadSkillTool 调用 SkillActivator
  ↓
把正文追加为 system reminder
  ↓
Agent 继续下一轮

/skill-name [arguments]
  ↓
MewCodeModel 调用 SkillActivator
  ↓
热重读正文并替换 $ARGUMENTS
  ↓
把正文追加为 system reminder
  ↓
agent.run(conversation)
```

下面从最前面的磁盘扫描开始。

---

## 2. Skill 文件如何被识别

项目中的 Skill 通常位于下面的目录：

```text
<workDir>/.mewcode/skills/<skill-name>/SKILL.md
```

例如：

```text
.mewcode/skills/
└── hello-world/
    └── SKILL.md
```

`SKILL.md` 可以包含 YAML frontmatter 和 Markdown 正文：

```md
---
name: hello-world
description: 根据用户输入生成问候语
---

# Hello World

读取用户输入，生成简短的问候语。
```

其中 frontmatter 用来描述 Skill，正文用来保存后续交给模型的 prompt。

### 2.1 `Skill` 和 `SkillMeta` 保存什么

**文件：`src/main/java/com/mewcode/skill/SkillCatalog.java`**

```java
public record SkillMeta(
        String name,
        String description,
        String whenToUse,
        List<String> tags,
        String mode,
        String model,
        String forkContext
) {}

public record Skill(SkillMeta meta, String promptBody,
                    Path sourceDir, boolean bodyLoaded) {}
```

`SkillMeta` 保存 frontmatter 中的字段。`name` 是 catalog 的查找键和 Slash Command 名称；`description` 用于生成系统提示词中的 Skill 列表；`whenToUse`、`tags`、`mode` 等字段也会被解析到对象中。

`Skill` 在 `SkillMeta` 外再保存三个运行时信息：`promptBody` 是 Markdown 正文；`sourceDir` 是当前 Skill 所在目录；`bodyLoaded` 表示对象中已有正文。当前的 `SKILL.md` 路径在首次扫描时就会读入正文，因此创建时传入 `true`。

对应关系可以写成：

```text
SKILL.md
  ├─ name、description 等 YAML 字段 → SkillMeta
  ├─ 第二个 --- 后的 Markdown        → Skill.promptBody
  └─ 文件所在的目录                  → Skill.sourceDir
```

### 2.2 根目录只扫描第一层子目录

**文件：`src/main/java/com/mewcode/skill/SkillCatalog.java`**

```java
private void loadTier(Path dir, String source) {
    if (!Files.isDirectory(dir)) return;

    try (Stream<Path> entries = Files.list(dir)) {
        entries.filter(Files::isDirectory).forEach(skillDir -> {
            try {
                Skill skill = loadSkill(skillDir);
                if (skill != null) register(skill, source);
            } catch (IOException ignored) {
            }
        });
    } catch (IOException ignored) {
    }
}
```

这个方法的 `dir` 是 Skill 根目录，例如：

```text
C:\project\.mewcode\skills
```

第一句先判断 `dir` 是否存在且确实是目录：

```java
if (!Files.isDirectory(dir)) return;
```

如果用户还没有创建 `.mewcode/skills`，方法在这里直接返回，后面的扫描不会执行。

接下来：

```java
Files.list(dir)
```

只返回 `dir` 的**直接子项**，并不递归读取所有层级。假设目录为：

```text
.mewcode/skills/
├── hello-world/
│   └── SKILL.md
├── frontend-design/
│   └── SKILL.md
├── readme.md
└── notes.txt
```

此时 `entries` 依次包含：

```text
hello-world/
frontend-design/
readme.md
notes.txt
```

然后这句过滤掉普通文件：

```java
entries.filter(Files::isDirectory)
```

等价于：

```java
entries.filter(path -> Files.isDirectory(path))
```

所以实际进入 `forEach` 的只有：

```text
hello-world/
frontend-design/
```

`readme.md` 和 `notes.txt` 不是目录，不会调用 `loadSkill`。这就是“只扫描 skills 的直接子目录”的含义：每个第一层目录都被当作一个候选 Skill 目录。

`try (Stream<Path> entries = ...)` 是 try-with-resources。`Files.list` 返回的 Stream 持有目录读取资源；离开 `try` 代码块时，Java 会自动关闭该 Stream。

在循环内部，`loadSkill(skillDir)` 尝试把当前目录解析成 `Skill`。解析结果不是 `null` 时才执行：

```java
register(skill, source);
```

如果某个目录读取 `SKILL.md` 失败，内层 `catch (IOException ignored)` 会结束当前目录的处理，随后继续下一个目录。若整个根目录无法列出，则由外层 `catch` 结束这次扫描。

### 2.3 一个目录中优先读取哪种文件

**文件：`src/main/java/com/mewcode/skill/SkillCatalog.java`**

```java
private static Skill loadSkill(Path dir) throws IOException {
    Path metaPath = dir.resolve("skill.yaml");
    if (Files.isRegularFile(metaPath)) {
        return loadFromYamlAndPrompt(dir, metaPath);
    }

    Path mdPath = dir.resolve("SKILL.md");
    if (Files.isRegularFile(mdPath)) {
        String content = Files.readString(mdPath);
        return parseSkillMD(dir, content);
    }

    return null;
}
```

这里的 `dir` 已经是单个 Skill 的目录，例如：

```text
.mewcode/skills/hello-world/
```

代码先拼出：

```java
Path metaPath = dir.resolve("skill.yaml");
```

得到：

```text
.mewcode/skills/hello-world/skill.yaml
```

如果该文件存在，程序进入 `loadFromYamlAndPrompt`，然后从同一个目录读取 `prompt.md`。如果没有 `skill.yaml`，代码才查找：

```text
.mewcode/skills/hello-world/SKILL.md
```

因此选择顺序为：

```text
skill.yaml + prompt.md
  ↓（skill.yaml 不存在）
SKILL.md
  ↓（也不存在）
返回 null，不注册这个目录
```

### 2.4 `SKILL.md` 如何拆成 YAML 和正文

**文件：`src/main/java/com/mewcode/skill/SkillCatalog.java`，`parseSkillMD` 方法中的关键部分**

```java
String trimmed = content.stripLeading();
if (trimmed.startsWith("---")) {
    int firstSep = content.indexOf("---");
    int secondSep = content.indexOf("---", firstSep + 3);
    if (secondSep >= 0) {
        String yamlBlock = content.substring(firstSep + 3, secondSep);
        body = content.substring(secondSep + 3).strip();
        ...
    }
}
```

`content` 是整个 `SKILL.md` 文件。例如：

```text
---                    ← firstSep
name: hello-world
description: 打招呼
---                    ← secondSep
# Hello World

生成问候语。
```

`firstSep` 是第一个 `---` 的位置，`secondSep` 是第二个 `---` 的位置。

```java
String yamlBlock = content.substring(firstSep + 3, secondSep);
```

从第一个分隔线之后开始取，到第二个分隔线之前结束，结果是：

```yaml
name: hello-world
description: 打招呼
```

```java
body = content.substring(secondSep + 3).strip();
```

从第二个分隔线之后开始取，结果是：

```md
# Hello World

生成问候语。
```

`.strip()` 去掉正文开始和结束处的空白字符与换行。

接着代码解析 YAML：

**文件：`src/main/java/com/mewcode/skill/SkillCatalog.java`**

```java
try {
    Yaml yaml = new Yaml();
    Map<String, Object> parsed = yaml.load(yamlBlock);
    if (parsed != null) {
        frontMatter = parsed;
    }
} catch (Exception ignored) {
}

SkillMeta meta = metaFromMap(frontMatter, dir);
return new Skill(meta, body, dir, true);
```

`new Yaml().load(yamlBlock)` 把 YAML 字符串转换成 Map。以上面的 YAML 为例，`parsed` 逻辑上相当于：

```java
Map.of(
    "name", "hello-world",
    "description", "打招呼"
)
```

解析成功时，`frontMatter` 被替换为 `parsed`。随后：

```java
SkillMeta meta = metaFromMap(frontMatter, dir);
```

从 map 中取 `name`、`description`、`when_to_use` 等字段，创建 `SkillMeta`。最后一行把元信息、正文和目录组合成一个 `Skill`：

```java
return new Skill(meta, body, dir, true);
```

---

## 3. 启动时如何建立 SkillCatalog

`MewCodeModel` 初始化时调用 `SkillCatalog.loadCatalog(workDir)`。

**文件：`src/main/java/com/mewcode/skill/SkillCatalog.java`**

```java
public static SkillCatalog loadCatalog(String workDir) {
    SkillCatalog c = new SkillCatalog();
    c.workDir = workDir;

    for (var skill : BuiltinSkills.load()) {
        c.register(skill, "builtin");
    }

    String home = System.getProperty("user.home");
    if (home != null) {
        c.loadTier(Path.of(home, ".mewcode", "skills"), "user");
    }

    c.loadTier(Path.of(workDir, ".mewcode", "skills"), "project");
    c.snapshotDirModTimes();
    return c;
}
```

这个方法创建一个新的 `SkillCatalog`，然后按固定顺序把不同来源的 Skill 加入其中：

```text
BuiltinSkills.load()
  ↓
~/.mewcode/skills/
  ↓
<workDir>/.mewcode/skills/
```

`register` 使用 Skill 名称作为 map key：

**文件：`src/main/java/com/mewcode/skill/SkillCatalog.java`**

```java
private final Map<String, Skill> skills = new LinkedHashMap<>();

public void register(Skill skill, String source) {
    skills.put(skill.meta().name(), skill);
    sources.put(skill.meta().name(), source);
}
```

例如用户级目录和项目级目录都存在名为 `hello-world` 的 Skill：

```text
~/.mewcode/skills/hello-world/
<workDir>/.mewcode/skills/hello-world/
```

用户级 Skill 先执行一次：

```java
skills.put("hello-world", userSkill);
```

项目级 Skill 后执行：

```java
skills.put("hello-world", projectSkill);
```

map 中最终保留的是 `projectSkill`。

`LinkedHashMap` 保留 key 的插入顺序，因此 `skillCatalog.list()` 会按 catalog 中的插入顺序返回 Skill 元信息。

---

## 4. 初始化时注册 LoadSkill 和 Slash Command

`MewCodeModel` 得到 catalog 后，会把 `LoadSkillTool` 放入工具注册表，并为每个 Skill 创建一个命令。

**文件：`src/main/java/com/mewcode/tui/MewCodeModel.java`**

```java
skillCatalog = SkillCatalog.loadCatalog(workDir);
skillActivator = new SkillActivator(skillCatalog);

var loadSkillTool = new LoadSkillTool();
loadSkillTool.setActivator(skillActivator);
loadSkillTool.setOnActivate((name, body) -> {
    if (conversation != null) {
        conversation.addSystemReminder(
                "<skill-name>" + name + "</skill-name>\n" + body);
    }
});
registry.register(loadSkillTool);

wireSkillsToAgent();
```

这一段有三件事。

第一，`skillCatalog` 保存启动时扫描到的全部 Skill。后面无论是工具还是 Slash Command，都通过这个对象按名称查 Skill。

第二，`SkillActivator` 是两条入口共用的激活服务。它通过 `getFull(name)` 重新读取磁盘内容，并负责 `$ARGUMENTS` 替换和空正文校验。`LoadSkillTool` 使用同一个服务。

第三，`setOnActivate` 设置 Skill 成功加载后的回调。这个回调不解析文件，它只把已经得到的 `name` 和 `body` 加进当前会话：

```java
conversation.addSystemReminder(...)
```

最后：

```java
registry.register(loadSkillTool);
```

把 `LoadSkillTool` 注册到 `ToolRegistry`。Agent 收到模型返回的 `LoadSkill` 工具调用时，就能通过名称找到这个对象。

### 4.1 如何生成 `/skill-name`

**文件：`src/main/java/com/mewcode/tui/MewCodeModel.java`**

```java
private void wireSkillsToAgent() {
    if (skillCatalog == null || cmdRegistry == null) return;
    syncSkillCommands();
}
```

`skillCatalog.list()` 返回每个 Skill 的 `SkillMeta`。循环只取 `meta.name()`，例如依次传入：

```text
hello-world
frontend-design
skill-creator
```

`registerSkillCommand` 为单个名称创建命令：

**文件：`src/main/java/com/mewcode/tui/MewCodeModel.java`**

```java
private void registerSkillCommand(String name) {
    if (cmdRegistry.find(name).isPresent()) return;
    var skill = skillCatalog.get(name);
    if (skill.isEmpty()) return;

    var meta = skill.get().meta();
    var cmd = new Command(name, meta.description() + " [skill]",
            new String[]{}, CommandType.PROMPT, false);

cmdRegistry.register(cmd, ctx -> "");
registeredSkillCommands.add(name);
}
```

前两句先防止冲突和不存在的名称：

```java
if (cmdRegistry.find(name).isPresent()) return;
var skill = skillCatalog.get(name);
if (skill.isEmpty()) return;
```

如果命令名已经被内置命令或之前注册的 Skill 使用，当前方法直接结束。如果 catalog 找不到同名 Skill，也直接结束。

然后创建命令对象：

```java
new Command(name, meta.description() + " [skill]", ..., CommandType.PROMPT, false)
```

因此名为 `hello-world` 的 Skill 对应：

```text
命令名：/hello-world
命令描述：<frontmatter description> [skill]
命令类型：PROMPT
```

Skill 命令的 handler 不再承载正文。TUI 根据 `registeredSkillCommands` 识别它，再由 `SkillActivator` 完成与 `LoadSkill` 相同的激活流程；因此 Slash Command 不会读取过期的 catalog 正文。

---

## 5. Skill 清单如何进入系统提示词

模型需要先知道哪些 Skill 可用，才能自行选择是否调用 `LoadSkill`。系统提示词通过 `buildSkillSection` 生成 Skill 清单。

**文件：`src/main/java/com/mewcode/tui/MewCodeModel.java`**

```java
private String rebuildSystemPrompt(String workDir) {
    var env = PromptBuilder.detectEnvironment(...);
    String skillSection = buildSkillSection(workDir);
    var options = new PromptBuilder.BuildOptions(
            skillSection, instructionsContent, memoryContentField);
    return PromptBuilder.buildSystemPrompt(env, options);
}
```

这里 `buildSkillSection(workDir)` 返回一段字符串，随后作为 `BuildOptions` 的参数交给 `PromptBuilder`。生成这段字符串的循环如下：

**文件：`src/main/java/com/mewcode/tui/MewCodeModel.java`**

```java
for (var meta : metas) {
    var desc = meta.description();
    if (desc.length() > 200) desc = desc.substring(0, 200) + "…";
    sb.append("- /").append(meta.name()).append(": ")
      .append(desc).append("\n");
}
```

假设 catalog 有一个 `hello-world` Skill，`description` 是“根据用户输入生成问候语”，循环会生成：

```text
- /hello-world: 根据用户输入生成问候语
```

完整片段前面还带有说明文字，表示模型应通过 `LoadSkill` 按需激活 Skill。系统提示词中保存的是名称和简短描述，而不是每个 Skill 的完整 Markdown 正文。

`SkillMeta.whenToUse` 在文件解析时会创建，但当前循环没有读取这个字段。因此系统提示词清单中没有输出 `when_to_use`。

---

## 6. 普通文本如何触发 `LoadSkill`

以用户输入下面这句话为例：

```text
你好，和我打个招呼
```

用户没有输入 `/hello-world`，而是走普通消息路径。

### 6.1 `MewCodeModel` 将消息交给 Agent

**文件：`src/main/java/com/mewcode/tui/MewCodeModel.java`**

```java
private UpdateResult<MewCodeModel> sendUserMessage() {
    refreshSkillsIfNeeded();

    String userText = inputBuffer.toString().trim();
    ...
    conversation.addUserMessage(userText);
    ...
    agent.run(conversation, queue);
}
```

`userText` 是输入框中的文本。方法开始时先调用：

```java
refreshSkillsIfNeeded();
```

它检查 Skill 根目录的修改时间；需要刷新时重新扫描 catalog 并重建系统提示词。之后：

```java
conversation.addUserMessage(userText);
```

将普通用户文本追加到对话历史。最后：

```java
agent.run(conversation, queue);
```

启动 Agent Loop。

此时模型看到的内容包含：系统提示词中的 Skill 名称列表、当前普通用户消息、已有对话历史，以及工具注册表导出的工具 schema。模型若判断请求和某个 Skill 相符，可以返回一个名为 `LoadSkill` 的工具调用。

### 6.2 Agent 如何把工具调用交给工具系统

**文件：`src/main/java/com/mewcode/agent/Agent.java`**

```java
var executor = new StreamingExecutor(registry, checker, hookEngine, queue,
        recoveryState, Path.of(workDir == null ? "." : workDir), sessionId);

var callInfos = toolCalls.stream()
        .map(tc -> new StreamingExecutor.ToolCallInfo(
                tc.toolId, tc.toolName, tc.args))
        .toList();
var results = executor.executeAll(callInfos);
```

模型返回的每个工具调用先转成 `ToolCallInfo`。其中 `toolName` 是工具名，`args` 是模型提供的参数。

例如模型要加载 `hello-world` 时，逻辑上的调用信息是：

```text
toolName = "LoadSkill"
args = { name: "hello-world" }
```

`executor.executeAll(callInfos)` 再将每个调用交给 `StreamingExecutor`。

**文件：`src/main/java/com/mewcode/agent/StreamingExecutor.java`**

```java
private ToolExecResult executeSingle(ToolCallInfo call) {
    Tool tool = registry.get(call.toolName());
    if (tool == null) {
        return new ToolExecResult(call.toolId(), "Unknown tool", true);
    }

    ToolResult result;
    try {
        result = tool.execute(call.args());
    } catch (Exception e) {
        result = ToolResult.error("Tool execution error: " + e.getMessage());
    }
    ...
}
```

这里的关键是：

```java
Tool tool = registry.get(call.toolName());
```

因为初始化阶段已经执行了：

```java
registry.register(loadSkillTool);
```

所以 `registry.get("LoadSkill")` 返回的是 `LoadSkillTool` 对象。下一句：

```java
result = tool.execute(call.args());
```

因此会进入 `LoadSkillTool.execute(...)`。

### 6.3 `LoadSkillTool` 如何读取正文

**文件：`src/main/java/com/mewcode/tool/impl/LoadSkillTool.java`**

```java
public ToolResult execute(Map<String, Object> args) {
    String name = args.getOrDefault("name", "").toString();
    var activation = activator.activate(name, "");
    if (onActivate != null) {
        onActivate.accept(activation.name(), activation.body());
    }
    return ToolResult.success("Skill \"" + activation.name() + "\" activated.");
}
```

`args` 是模型传进来的参数 map。第一句从中取出 `name`：

```java
String name = args.getOrDefault("name", "").toString();
```

当工具调用参数为：

```json
{ "name": "hello-world" }
```

这里得到：

```java
name = "hello-world";
```

随后工具委托 `SkillActivator`。它从当前 catalog 查找并重新读取这个 Skill，再返回已校验的正文。`getFull` 的代码如下。

**文件：`src/main/java/com/mewcode/skill/SkillCatalog.java`**

```java
public Optional<Skill> getFull(String name) {
    Skill skill = skills.get(name);
    if (skill == null) return Optional.empty();
    if (skill.sourceDir() == null) return Optional.of(skill);

    try {
        Skill reloaded = loadSkill(skill.sourceDir());
        if (reloaded != null) {
            skills.put(name, reloaded);
            return Optional.of(reloaded);
        }
    } catch (IOException ignored) {
    }
    return Optional.of(skill);
}
```

先执行：

```java
Skill skill = skills.get(name);
```

如果 map 中没有该名称，返回空 `Optional`，`LoadSkillTool` 随后返回：

```text
unknown skill: <name>
```

如果找到的 `Skill` 有 `sourceDir`，则调用：

```java
loadSkill(skill.sourceDir())
```

这会重新读取该目录中的 `SKILL.md`，或 `skill.yaml + prompt.md`。读取成功后，新对象写回：

```java
skills.put(name, reloaded);
```

正文为空时返回错误；正文存在时，工具执行激活回调并返回简短成功信息。完整正文只通过回调写入对话，不再同时放入工具结果，避免占用两份上下文。

### 6.4 激活回调如何写入对话

初始化时传给 `LoadSkillTool` 的回调是：

**文件：`src/main/java/com/mewcode/tui/MewCodeModel.java`**

```java
loadSkillTool.setOnActivate((name, body) -> {
    if (conversation != null) {
        conversation.addSystemReminder(
                "<skill-name>" + name + "</skill-name>\n" + body);
    }
});
```

当 `LoadSkillTool.execute` 执行：

```java
onActivate.accept(name, body);
```

它会回到上面的 lambda。`body` 被包上一行 Skill 名称，然后传给 `ConversationManager.addSystemReminder`。

**文件：`src/main/java/com/mewcode/conversation/ConversationManager.java`**

```java
public void addSystemReminder(String content) {
    history.add(new Message(
            "user",
            "<system-reminder>\n" + content + "\n</system-reminder>"));
}
```

因此加载 `hello-world` 后，当前 conversation 的末尾会新增一条 `user` 角色消息，其实际文本形如：

```text
<system-reminder>
<skill-name>hello-world</skill-name>
# Hello World

读取用户输入，生成简短的问候语。
</system-reminder>
```

工具本身还返回：

```java
ToolResult.success("# Skill: " + name + "\n\n" + body)
```

这个返回值会作为该次工具调用的结果加入对话。普通文本触发时，Skill 正文因此一部分出现在 system reminder 中，另一部分包含在工具结果中。

`LoadSkillTool` 属于工具系统，并且标记为读取类工具：

**文件：`src/main/java/com/mewcode/tool/impl/LoadSkillTool.java`**

```java
@Override
public ToolCategory category() { return ToolCategory.READ; }
```

---

## 7. `/skill-name` 的直接调用路径

用户输入：

```text
/hello-world Alice
```

不会让模型先调用 `LoadSkill`。输入被识别为动态 Skill 命令后，`MewCodeModel` 直接调用与工具共用的 `SkillActivator`。

```java
case PROMPT -> {
    if (registeredSkillCommands.contains(cmd.name())) {
        var activation = skillActivator.activate(cmd.name(), args);
        conversation.addSystemReminder("<skill-name>" + activation.name()
                + "</skill-name>\n" + activation.body());
    }
    agentQueue = agent.run(conversation);
}
```

`SkillActivator.activate` 会调用 `SkillCatalog.getFull`，所以每次 `/skill-name` 都会从磁盘重新读取正文。它按以下规则处理参数：

```text
正文包含 $ARGUMENTS  → 用命令参数替换该占位符
正文不包含占位符     → 在正文末尾追加 ## User Request 和命令参数
没有命令参数         → 保持正文不变
```

因此 `/hello-world Alice` 只会向 conversation 添加一条带有 `system-reminder` 包装的 Skill 正文；`Alice` 已经包含在正文中，不会再额外添加第二条 user message。

完整路径为：

```text
/hello-world Alice
  ↓
MewCodeModel.executeSlashCommand(...)
  ↓
SkillActivator.activate("hello-world", "Alice")
  ↓
SkillCatalog.getFull() 热重读 + 参数替换
  ↓
conversation.addSystemReminder(...)
  ↓
agent.run(conversation)
```

---

## 8. 重载和清空会话

### 8.1 `/skills reload`

Skill 管理命令名是 `/skills`，`reload` 是传给它的参数。

**文件：`src/main/java/com/mewcode/command/CommandRegistry.java`**

```java
if (ctx.args() != null && ctx.args().strip().equals("reload")) {
    int count = ctx.skillReload().getAsInt();
    return "Skills reloaded. %d skill(s) available.".formatted(count);
}
```

`skillReload()` 的实现由 `MewCodeModel.buildCommandContext` 提供：

**文件：`src/main/java/com/mewcode/tui/MewCodeModel.java`**

```java
() -> {
    if (skillCatalog == null) return 0;
    String wd = System.getProperty("user.dir");
    skillCatalog.reload(wd);
    syncSkillCommands();
    if (client != null) client.setSystemPrompt(rebuildSystemPrompt(wd));
    return skillCatalog.list().size();
}
```

这里按顺序做四件事。

第一，`skillCatalog.reload(wd)` 创建一个新的扫描结果，并用新结果替换当前 catalog 中的 `skills`、`sources` 和目录修改时间。

第二，`syncSkillCommands()` 将 catalog 与动态 Slash Command 集合对齐：新增 Skill 会注册命令，已从磁盘删除的 Skill 会调用 `CommandRegistry.unregister(name)` 清理旧命令。

第三，`rebuildSystemPrompt(wd)` 根据新的 catalog 重新生成 Available Skills 片段。

第四，`client.setSystemPrompt(...)` 把生成后的系统提示词设置给当前 LLM client。

普通文本发送前也会调用：

**文件：`src/main/java/com/mewcode/tui/MewCodeModel.java`**

```java
private UpdateResult<MewCodeModel> sendUserMessage() {
    refreshSkillsIfNeeded();
    ...
}
```

`refreshSkillsIfNeeded` 在 `skillCatalog.needsReload()` 返回 `true` 时执行与上面相同的 catalog reload 和 system prompt 更新。

### 8.2 `/clear`

自然语言调用 `LoadSkill` 后，Skill 正文保存于当前 `ConversationManager.history` 中。`/clear` 会创建新的 `ConversationManager`：

**文件：`src/main/java/com/mewcode/tui/MewCodeModel.java`**

```java
case "clear" -> {
    chatMessages.clear();
    committedUpTo = 0;
    conversation = new ConversationManager();
    ...
}
```

因此 `/clear` 的效果是替换整个 conversation 对象。旧对象中包含的 system reminder、工具结果和用户消息不在新 conversation 中。

可以把关系理解为：

```text
LoadSkill
  ↓
旧 conversation.history 增加 Skill system reminder

/clear
  ↓
conversation 指向新的 ConversationManager
  ↓
新的 history 是空列表
```

---

## 9. 代码中存在但未经过上述主链的方法

`SkillCatalog` 有一个按名称拼接多个 Skill 正文的方法：

**文件：`src/main/java/com/mewcode/skill/SkillCatalog.java`**

```java
public String buildActiveContext(Set<String> activeSkillNames) {
    if (activeSkillNames == null || activeSkillNames.isEmpty()) return "";
    ...
    sb.append(skill.promptBody()).append("\n\n");
    return sb.toString();
}
```

这个方法需要调用方传入一组激活中的 Skill 名称，然后返回一个包含多个 prompt body 的字符串。当前主调用链没有调用它；`LoadSkillTool` 走的是 `conversation.addSystemReminder(...)`。

`SkillExecutor` 也定义了 inline 和 fork 两个执行入口：

**文件：`src/main/java/com/mewcode/skill/SkillExecutor.java`**

```java
public static String executeInline(Skill skill, String args, SkillHost host) {
    String body = substituteArguments(skill.promptBody(), args);
    host.activateSkill(skill.meta().name(), body);
    host.recordSkillInvocation(skill.meta().name(), body);
    return body;
}
```

当前 TUI 的两条主链都会使用 `SkillExecutor.substituteArguments(...)` 完成参数替换，但仍不会调用 `SkillExecutor.executeInline` 或 `SkillExecutor.executeFork`。因此 frontmatter 中的 `mode: fork`、`model` 和 `fork_context` 暂未生效；当前 TUI 只支持 inline 注入。

当前 `SkillCatalog` 和 `LoadSkillTool` 的文件读取路径只包含：

```text
skill.yaml
prompt.md
SKILL.md
```

其中没有 `tool.json` 的读取或 Skill 专属工具注册调用。
