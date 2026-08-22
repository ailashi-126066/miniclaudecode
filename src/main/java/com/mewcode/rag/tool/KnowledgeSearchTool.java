package com.mewcode.rag.tool;

import com.mewcode.rag.KnowledgeRagService;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Searches the manually maintained private knowledge base. */
public final class KnowledgeSearchTool implements Tool {
    private final KnowledgeRagService knowledge;

    public KnowledgeSearchTool(Path workspace) { this.knowledge = new KnowledgeRagService(workspace); }
    @Override public String name() { return "KnowledgeSearch"; }
    @Override public String description() {
        return "Search private workspace knowledge documents such as specifications, business rules and API references. "
                + "If the index is missing or stale, ask the user to run /knowledge index.";
    }
    @Override public ToolCategory category() { return ToolCategory.READ; }
    @Override public Map<String, Object> schema() {
        return Map.of("name", name(), "description", description(), "input_schema", Map.of(
                "type", "object", "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query")));
    }

    @Override public ToolResult execute(Map<String, Object> args) {
        String query = args == null ? "" : String.valueOf(args.getOrDefault("query", "")).trim();
        if (query.isEmpty()) return ToolResult.error("query is required");
        try {
            var status = knowledge.status();
            if (!status.indexed()) return ToolResult.error("Knowledge index is not available. Add documents to "
                    + status.knowledgeRoot() + " and run /knowledge index.");
            if (status.stale()) return ToolResult.error("Knowledge index is stale. Run /knowledge index before relying on it.");
            var response = knowledge.search(query);
            if (response.results().isEmpty()) return ToolResult.success("No relevant knowledge found.");
            return ToolResult.success(render(response.results()));
        } catch (Exception error) {
            return ToolResult.error("Knowledge search failed: " + error.getMessage());
        }
    }

    public static String render(List<com.mewcode.rag.search.SearchResult> results) {
        StringBuilder out = new StringBuilder("Knowledge sources:\n");
        for (var result : results) {
            var chunk = result.chunk();
            out.append("\n- ").append(chunk.path()).append(':').append(chunk.startLine())
                    .append('-').append(chunk.endLine()).append(" ").append(chunk.symbol()).append("\n")
                    .append(chunk.content()).append("\n");
        }
        return out.toString().strip();
    }
}
