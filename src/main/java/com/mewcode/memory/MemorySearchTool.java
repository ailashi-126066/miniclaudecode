package com.mewcode.memory;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Searches the two user-memory Markdown category files directly. */
public final class MemorySearchTool implements Tool {
    private final Path userMemoryDir;

    public MemorySearchTool() {
        this(Path.of(System.getProperty("user.home"), ".mewcode", "memory"));
    }

    /** Package-visible constructor for tests and alternate local runtimes. */
    MemorySearchTool(Path userMemoryDir) {
        this.userMemoryDir = userMemoryDir;
    }

    public String name() { return "MemorySearch"; }

    public String description() { return "Search durable user memories stored in user.md and feedback.md."; }

    public ToolCategory category() { return ToolCategory.READ; }

    public boolean shouldDefer() { return true; }

    public Map<String, Object> schema() {
        return Map.of("name", name(), "description", description(), "input_schema", Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query")));
    }

    public ToolResult execute(Map<String, Object> args) {
        String query = String.valueOf(args.getOrDefault("query", "")).trim();
        if (query.isEmpty()) return ToolResult.error("query is required");

        var matches = new ArrayList<Match>();
        matches.addAll(searchDirectory(userMemoryDir, query));
        if (matches.isEmpty()) return ToolResult.success("No matching memories");

        matches.sort(Comparator.comparingInt(Match::score).reversed()
                .thenComparing(Match::path));
        var out = new StringBuilder();
        matches.stream().limit(8).forEach(m -> out.append('[').append(m.type())
                .append("] ").append(m.name()).append(": ").append(m.content()).append('\n'));
        return ToolResult.success(out.toString().strip());
    }

    private static List<Match> searchDirectory(Path directory, String query) {
        if (!Files.isDirectory(directory)) return List.of();
        String needle = query.toLowerCase(Locale.ROOT);
        var matches = new ArrayList<Match>();
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> Set.of("user.md", "feedback.md").contains(path.getFileName().toString()))
                    .forEach(path -> addMatch(path, needle, matches));
        } catch (IOException ignored) {
            // Search is best-effort; unreadable directories simply contribute no matches.
        }
        return matches;
    }

    private static void addMatch(Path path, String needle, List<Match> matches) {
        try {
            String content = Files.readString(path);
            int score = occurrences(content.toLowerCase(Locale.ROOT), needle);
            if (score == 0) return;
            var frontmatter = MemoryScanner.parseFrontmatter(content);
            String name = frontmatter.name().isBlank()
                    ? path.getFileName().toString().replaceFirst("\\.md$", "")
                    : frontmatter.name();
            String type = frontmatter.type().isBlank() ? "?" : frontmatter.type();
            matches.add(new Match(path.toAbsolutePath().toString(), type, name, content, score));
        } catch (IOException ignored) {
            // Skip a single unreadable memory file.
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int start = 0; ; ) {
            int found = text.indexOf(needle, start);
            if (found < 0) return count;
            count++;
            start = found + needle.length();
        }
    }

    private record Match(String path, String type, String name, String content, int score) {}
}
