// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.memory;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Stream;

/**
 * 记忆管理器，以独立的 Markdown 文件和 MEMORY.md 索引作为唯一存储格式。
 *
 * <p>存储结构：用户级 {@code ~/.mewcode/memory/} 中只有 {@code user.md} 与
 * {@code feedback.md} 两个类别文件。项目规则与知识由 AGENTS.md、MEWCODE.md、docs 和源码承载，
 * 不再维护项目级记忆副本。
 */
public class MemoryManager {

    private static final int EXTRACTION_INTERVAL = 1;
    private static final String MEMORY_DIR = ".mewcode/memory";

    /** User-scoped categories are shared across workspaces. */
    private static final Set<String> USER_TYPES = Set.of("user", "feedback");
    /** Project-scoped facts are stored with the workspace. */
    private static final Set<String> PROJECT_TYPES = Set.of("project");
    private static final Set<String> MEMORY_TYPES = Set.of("user", "feedback", "project");

    private final Path userMemDirPath;
    private final Path projectMemDirPath;
    private int turnCount;

    public MemoryManager(String workDir) {
        this.userMemDirPath = Path.of(System.getProperty("user.home"), MEMORY_DIR);
        this.projectMemDirPath = Path.of(workDir, MEMORY_DIR);
        ensureDir(userMemDirPath);
        ensureDir(projectMemDirPath);
    }

    // ---- Directory accessors (for memory recall) ----

    /** 返回用户级记忆目录（~/.mewcode/memory/） */
    public Path userMemDir() {
        return userMemDirPath;
    }

    /** Returns the project-scoped memory directory ({@code .mewcode/memory/}). */
    public Path projectMemDir() {
        return projectMemDirPath;
    }

    // ---- Accessors ----

    /**
     * 返回所有记忆的摘要行，格式为 "[type] name — description"。
     * 直接读取 user.md 与 feedback.md 的条目标题和描述，Markdown 是唯一数据源。
     */
    public List<String> getMemories() {
        var out = new ArrayList<String>();
        for (String type : USER_TYPES) {
            for (CategoryEntry entry : readCategoryEntries(type)) {
                out.add("[%s] %s — %s".formatted(type, entry.name(), entry.description()));
            }
        }
        for (String type : PROJECT_TYPES) {
            for (CategoryEntry entry : readCategoryEntries(type)) {
                out.add("[%s] %s — %s".formatted(type, entry.name(), entry.description()));
            }
        }
        return out;
    }

    public boolean shouldExtract() {
        turnCount++;
        return turnCount % EXTRACTION_INTERVAL == 0;
    }

    /**
     * 清除两个目录下的所有 .md 文件（包括 MEMORY.md）。
     */
    public void clear() {
        for (String type : USER_TYPES) {
            try { Files.deleteIfExists(categoryPath(type)); } catch (IOException ignored) {}
        }
        for (String type : PROJECT_TYPES) {
            try { Files.deleteIfExists(categoryPath(type)); } catch (IOException ignored) {}
        }
    }

    // ---- Memory file record ----

    /** 一个记忆文件的元数据 */
    public record MemoryFile(String path, String filename, String name, String description, String type) {}

    /**
     * 扫描两个目录，加载所有记忆文件的 frontmatter 元数据。
     * 用户级在前，项目级在后。
     */
    List<MemoryFile> loadAll() {
        var out = new ArrayList<MemoryFile>();
        out.addAll(loadDir(userMemDirPath, USER_TYPES));
        out.addAll(loadDir(projectMemDirPath, PROJECT_TYPES));
        return out;
    }

    private static List<MemoryFile> loadDir(Path dir, Set<String> supportedTypes) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> mdFiles;
        try (Stream<Path> stream = Files.list(dir)) {
            mdFiles = stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return supportedTypes.contains(n.replaceFirst("\\.md$", ""));
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }

        var out = new ArrayList<MemoryFile>();
        for (Path fp : mdFiles) {
            try {
                String content = Files.readString(fp);
                var fm = MemoryScanner.parseFrontmatter(content);
                String name = fm.name().isEmpty()
                        ? fp.getFileName().toString().replace(".md", "")
                        : fm.name();
                out.add(new MemoryFile(
                        fp.toAbsolutePath().toString(),
                        fp.getFileName().toString(),
                        name, fm.description(), fm.type()));
            } catch (IOException ignored) {
                // 跳过不可读的文件
            }
        }
        return out;
    }

    // ---- Build system-reminder section ----

    /**
     * Builds a small reminder pointing at the user and project memory files.
     */
    public String buildSystemReminder() {
        ensureDir(userMemDirPath);
        ensureDir(projectMemDirPath);

        var paths = MEMORY_TYPES.stream().map(this::categoryPath).filter(Files::isRegularFile).toList();
        if (paths.isEmpty()) return "";
        var sb = new StringBuilder("# auto memory\n\n");
        for (Path path : paths) sb.append("- ").append(path).append('\n');
        return sb.toString();
    }

    // ---- Extraction via LLM ----

    /**
     * 扫描已有记忆文件，生成 manifest 给 LLM 做去重。
     */
    private String scanExistingMemories() {
        var entries = new ArrayList<String>();
        for (String memory : getMemories()) entries.add("- " + memory);
        return String.join("\n", entries);
    }

    /**
     * 通过 LLM 从对话中提取记忆（参照 Go 版 extractor.go）。
     * 发送已有记忆 manifest 做去重，使用 MEMORY_NAME/TYPE/DESC/BODY 格式解析输出。
     */
    public void extract(LlmClient client, ConversationManager conv) {
        List<Message> messages = conv.getMessages();
        if (messages.size() < 4) return;

        // 只取最近 40 条消息
        int start = Math.max(0, messages.size() - 40);
        var sb = new StringBuilder();
        for (int i = start; i < messages.size(); i++) {
            var msg = messages.get(i);
            sb.append('[').append(msg.getRole()).append("]: ").append(msg.getContent()).append('\n');
            if (msg.getToolResults() != null) {
                for (var result : msg.getToolResults()) {
                    String output = result.content() == null ? "" : result.content();
                    if (output.length() > 1_000) output = output.substring(0, 1_000) + "…";
                    sb.append("[tool_result ").append(result.toolUseId()).append("]: ")
                      .append(output).append('\n');
                }
            }
        }

        // 扫描已有记忆做去重
        String manifest = scanExistingMemories();
        String manifestSection = manifest.isEmpty() ? "" :
                "\n\n## Existing memory files\n\n" + manifest +
                "\n\nCheck this list before creating — update an existing file rather than creating a duplicate.";

        ConversationManager extractConv = new ConversationManager();
        extractConv.addUserMessage(
                "Analyze the conversation below and extract memories worth saving.\n\n"
                + "For each memory, output in this exact format:\n"
                + "MEMORY_NAME: <kebab-case-name>\n"
                + "MEMORY_TYPE: <user|feedback|project>\n"
                + "MEMORY_DESC: <one-line description>\n"
                + "ACE_EVIDENCE: <one-line observed fact, user statement, or tool output>\n"
                + "ACE_INFERENCE: <one-line conclusion derived from the evidence>\n"
                + "ACE_VERIFICATION: <one-line command/outcome that validates it, or NONE>\n"
                + "MEMORY_BODY: <one-line durable fact to remember>\n"
                + "---\n\n"
                + "Types:\n"
                + "- user: user preferences, role, and long-term habits\n"
                + "- feedback: corrections and validated collaboration rules\n"
                + "- project: durable repository-specific decisions, invariants, and conventions\n\n"
                + "What NOT to save:\n"
                + "- Transient code details that are cheaper to re-read from source\n"
                + "- Git history, debugging solutions\n"
                + "- Ephemeral task details\n\n"
                + "Keep evidence, inference, and verification distinct. Never present an unverified inference as evidence. "
                + "Only save verification that comes from an actual tool result in this conversation.\n\n"
                + "If nothing is worth saving, output NONE." + manifestSection + "\n\n"
                + "Conversation:\n" + sb
        );

        BlockingQueue<StreamEvent> events = client.stream(extractConv, null);
        var result = new StringBuilder();
        try {
            while (true) {
                StreamEvent event = events.take();
                if (event instanceof StreamEvent.TextDelta td) {
                    result.append(td.text());
                } else if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String output = result.toString().trim();
        if (output.isEmpty() || output.equals("NONE") || !output.contains("MEMORY_NAME:")) return;

        // 解析 MEMORY_NAME/TYPE/DESC/BODY 格式
        for (String block : output.split("---")) {
            if (!block.contains("MEMORY_NAME:")) continue;
            String name = extractField(block, "MEMORY_NAME");
            String type = extractField(block, "MEMORY_TYPE");
            String desc = extractField(block, "MEMORY_DESC");
            String body = extractField(block, "MEMORY_BODY");
            if (name.isEmpty() || body.isEmpty()) continue;
            if (!MEMORY_TYPES.contains(type)) continue;

            var ace = new AceBullet(
                    extractField(block, "ACE_EVIDENCE"),
                    extractField(block, "ACE_INFERENCE"),
                    extractField(block, "ACE_VERIFICATION"));

            writeMemoryFile(name, type, desc, ace.render(body));
        }
    }

    private static String extractField(String block, String field) {
        var m = java.util.regex.Pattern.compile(field + ":\\s*(.+?)(?:\\n|$)").matcher(block);
        return m.find() ? m.group(1).trim() : "";
    }

    /**
     * 将一条记忆写为独立的 .md 文件，并在 MEMORY.md 索引中追加指针。
     */
    private void writeMemoryFile(String name, String type, String description, String body) {
        Path filePath = categoryPath(type);
        String entry = "## %s\n\nDescription: %s\n\n%s\n".formatted(name, description, body.strip());
        try {
            String existing = Files.isRegularFile(filePath) ? Files.readString(filePath) : categoryHeader(type);
            String updated = replaceCategoryEntry(existing, name, entry);
            if (!existing.equals(updated)) Files.writeString(filePath, updated);
        } catch (IOException e) {
            return;
        }
    }

    private Path categoryPath(String type) {
        return PROJECT_TYPES.contains(type)
                ? projectMemDirPath.resolve(type + ".md")
                : userMemDirPath.resolve(type + ".md");
    }

    private static String categoryHeader(String type) {
        String title = switch (type) {
            case "user" -> "User memories";
            case "feedback" -> "Feedback memories";
            case "project" -> "Project memories";
            default -> "Memories";
        };
        return "---\nname: %s\ndescription: %s\nmetadata:\n  type: %s\n---\n\n# %s\n\n"
                .formatted(type, title, type, title);
    }

    private static String replaceCategoryEntry(String source, String name, String entry) {
        String pattern = "(?ms)^## " + java.util.regex.Pattern.quote(name) + "\\R.*?(?=^## |\\z)";
        if (java.util.regex.Pattern.compile(pattern).matcher(source).find()) return source.replaceFirst(pattern, entry);
        return source.stripTrailing() + "\n\n" + entry;
    }

    private List<CategoryEntry> readCategoryEntries(String type) {
        Path path = categoryPath(type);
        if (!Files.isRegularFile(path)) return List.of();
        try {
            var matcher = java.util.regex.Pattern.compile("(?m)^## ([^\\r\\n]+)\\R+Description: ([^\\r\\n]*)").matcher(Files.readString(path));
            var entries = new ArrayList<CategoryEntry>();
            while (matcher.find()) entries.add(new CategoryEntry(matcher.group(1).strip(), matcher.group(2).strip()));
            return entries;
        } catch (IOException ignored) { return List.of(); }
    }

    private record CategoryEntry(String name, String description) {}

    /**
     * 按 `### <type>` 分组解析 LLM 提取输出。
     * 大小写不敏感，归一化为小写。
     */
    static Map<String, String> parseTypedSections(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        String currentType = null;
        StringBuilder buf = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("### ")) {
                if (currentType != null) {
                    String body = buf.toString().trim();
                    if (!body.isEmpty()) {
                        out.merge(currentType, body, (a, b) -> a + "\n" + b);
                    }
                }
                currentType = trimmed.substring(4).trim().toLowerCase(Locale.ROOT);
                buf.setLength(0);
            } else if (currentType != null) {
                buf.append(line).append('\n');
            }
        }
        if (currentType != null) {
            String body = buf.toString().trim();
            if (!body.isEmpty()) {
                out.merge(currentType, body, (a, b) -> a + "\n" + b);
            }
        }
        return out;
    }

    // ---- Injection ----

    /**
     * 向对话注入已有的记忆内容（MEMORY.md 索引）。
     */
    public void injectMemories(ConversationManager conv) {
        String reminder = buildSystemReminder();
        if (reminder.isBlank()) {
            return;
        }
        if (conv.getMessages().isEmpty()) {
            conv.addUserMessage(reminder);
            conv.addAssistantMessage("Understood, I'll keep this context in mind.");
        }
    }

    // ---- Custom instructions ----

    /**
     * 加载指令文件：支持用户级（~/.mewcode/MEWCODE.md）、项目级（git root 到 workDir 逐层）、
     * 兼容旧版 INSTRUCTIONS.md、私有 MEWCODE.local.md，以及 @include 递归展开。
     * 委托给 {@link InstructionLoader} 实现完整的发现和展开逻辑。
     */
    public static String loadInstructions(String workDir) {
        return InstructionLoader.loadInstructions(workDir);
    }

    // ---- Helpers ----

    private static void ensureDir(Path dir) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {}
    }
}
