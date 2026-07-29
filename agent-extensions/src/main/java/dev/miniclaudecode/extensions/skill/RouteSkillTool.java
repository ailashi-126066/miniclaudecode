package dev.miniclaudecode.extensions.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class RouteSkillTool implements AgentTool {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "skills",
          "route_skill",
          "Two-stage metadata recall and reranking for local skills without loading their bodies",
          "{\"type\":\"object\",\"properties\":{\"intent\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10}},\"required\":[\"intent\"]}",
          RiskLevel.LOW);
  private final SkillCatalog catalog;

  public RouteSkillTool(SkillCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      JsonNode arguments = JSON.readTree(call.argumentsJson());
      String intent = arguments.path("intent").asText("").trim();
      int limit = arguments.path("limit").isMissingNode() ? 5 : arguments.path("limit").asInt();
      List<SkillRouter.RouteMatch> matches = this.catalog.route(intent, limit);
      StringBuilder output = new StringBuilder();
      for (SkillRouter.RouteMatch match : matches) {
        SkillDescriptor skill = match.skill();
        output
            .append(skill.name())
            .append(" score=")
            .append(String.format(java.util.Locale.ROOT, "%.2f", match.score()))
            .append(" source=")
            .append(skill.source().name().toLowerCase(java.util.Locale.ROOT))
            .append('\n')
            .append("  ")
            .append(skill.description())
            .append('\n');
        if (!skill.tags().isEmpty()) {
          output.append("  tags: ").append(String.join(", ", skill.tags())).append('\n');
        }
        if (!skill.boundaries().isEmpty()) {
          output
              .append("  boundaries: ")
              .append(String.join("; ", skill.boundaries()))
              .append('\n');
        }
        if (!match.reasons().isEmpty()) {
          output.append("  matched: ").append(String.join(", ", match.reasons())).append('\n');
        }
      }
      String rendered =
          output.isEmpty()
              ? "No skill metadata matched this intent. Continue with atomic tools."
              : output.toString().stripTrailing();
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.COMPLETED,
              rendered,
              Optional.empty(),
              Map.of("matches", matches.size(), "bodiesLoaded", false)));
    } catch (RuntimeException | java.io.IOException error) {
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.FAILED,
              "skill routing failed: "
                  + Objects.requireNonNullElse(
                      error.getMessage(), error.getClass().getSimpleName()),
              Optional.empty(),
              Map.of()));
    }
  }
}
