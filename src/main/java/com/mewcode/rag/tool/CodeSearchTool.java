package com.mewcode.rag.tool;

import com.mewcode.rag.RagService;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Deferred hybrid code-search tool backed by the workspace Lucene index. */
public final class CodeSearchTool implements Tool {
    private final RagService rag;
    public CodeSearchTool(Path workspace) { this.rag = new RagService(workspace); }
    @Override public String name() { return "CodeSearch"; }
    @Override public String description() { return "Search the indexed codebase using BM25, vector retrieval and RRF fusion."; }
    @Override public ToolCategory category() { return ToolCategory.READ; }
    @Override public boolean shouldDefer() { return true; }
    @Override public Map<String,Object> schema() {
        return Map.of("name", name(), "description", description(), "input_schema", Map.of(
                "type", "object", "properties", Map.of("query", Map.of("type", "string", "description", "Code or behavior to find")),
                "required", List.of("query")));
    }
    @Override public ToolResult execute(Map<String,Object> args) {
        String query = args == null ? "" : String.valueOf(args.getOrDefault("query", "")).trim();
        if (query.isEmpty()) return ToolResult.error("query is required");
        try {
            var response = rag.search(query);
            if (response.results().isEmpty()) return ToolResult.success("No relevant code found. Run `minicode index` first if the index is missing.");
            StringBuilder out = new StringBuilder(response.explain()).append("\n\n");
            for (var result : response.results()) {
                var c = result.chunk();
                out.append(c.path()).append(':').append(c.startLine()).append('-').append(c.endLine())
                        .append(" ").append(c.symbol()).append(" [").append(result.explanation()).append("]\n")
                        .append(c.content()).append("\n\n");
            }
            return ToolResult.success(out.toString().strip());
        } catch (Exception e) { return ToolResult.error("Code search failed: " + e.getMessage()); }
    }
}
