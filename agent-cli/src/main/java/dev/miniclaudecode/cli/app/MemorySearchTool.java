package dev.miniclaudecode.cli.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.persistence.memory.AceBulletStore;
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
          "Retrieve project ACE lessons",
          "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10}},\"required\":[\"query\"]}",
          RiskLevel.LOW);
  private final AceBulletStore bullets;

  MemorySearchTool(AceBulletStore bullets) {
    this.bullets = Objects.requireNonNull(bullets, "bullets must not be null");
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
      StringBuilder output = new StringBuilder();
      var aceBullets = this.bullets.search(query, limit);
      aceBullets.forEach(
          bullet ->
              output
                  .append(bullet.id(), 0, Math.min(12, bullet.id().length()))
                  .append(" [ace-bullet x")
                  .append(bullet.occurrences())
                  .append(", confidence=")
                  .append(String.format(java.util.Locale.ROOT, "%.2f", bullet.confidence()))
                  .append("]\ntrigger: ")
                  .append(bullet.trigger())
                  .append("\nlesson: ")
                  .append(bullet.lesson())
                  .append(
                      bullet.applicablePaths().isEmpty()
                          ? ""
                          : "\npaths: " + String.join(", ", bullet.applicablePaths()))
                  .append("\n\n"));
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.COMPLETED,
              output.isEmpty() ? "No reusable memory matched." : output.toString().stripTrailing(),
              Optional.empty(),
              Map.of("matches", aceBullets.size())));
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
