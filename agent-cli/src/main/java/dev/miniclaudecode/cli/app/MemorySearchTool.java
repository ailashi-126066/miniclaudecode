package dev.miniclaudecode.cli.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.persistence.memory.JsonlMemoryStore;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class MemorySearchTool implements AgentTool {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "memory",
          "search",
          "Retrieve compact cross-session path, repair, outcome, and explicit preference memories",
          "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10}},\"required\":[\"query\"]}",
          RiskLevel.LOW);
  private final JsonlMemoryStore store;

  MemorySearchTool(JsonlMemoryStore store) {
    this.store = Objects.requireNonNull(store, "store must not be null");
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      JsonNode arguments = JSON.readTree(call.argumentsJson());
      String query = arguments.path("query").asText("").trim();
      int limit = arguments.path("limit").isMissingNode() ? 5 : arguments.path("limit").asInt();
      var hits = this.store.search(query, limit);
      StringBuilder output = new StringBuilder();
      hits.forEach(
          hit ->
              output
                  .append(hit.memory().id(), 0, Math.min(12, hit.memory().id().length()))
                  .append(" [")
                  .append(hit.memory().category().name().toLowerCase(java.util.Locale.ROOT))
                  .append("] score=")
                  .append(String.format(java.util.Locale.ROOT, "%.2f", hit.score()))
                  .append('\n')
                  .append("objective: ")
                  .append(hit.memory().objective())
                  .append('\n')
                  .append("outcome: ")
                  .append(hit.memory().summary())
                  .append("\n\n"));
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.COMPLETED,
              output.isEmpty() ? "No reusable memory matched." : output.toString().stripTrailing(),
              Optional.empty(),
              Map.of("matches", hits.size())));
    } catch (RuntimeException | java.io.IOException error) {
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.FAILED,
              "memory search failed: "
                  + Objects.requireNonNullElse(
                      error.getMessage(), error.getClass().getSimpleName()),
              Optional.empty(),
              Map.of()));
    }
  }
}
