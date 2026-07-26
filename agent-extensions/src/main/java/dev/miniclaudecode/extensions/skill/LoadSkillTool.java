package dev.miniclaudecode.extensions.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class LoadSkillTool implements AgentTool {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "skills",
          "load_skill",
          "Load one local SKILL.md instruction set; skill instructions never change permissions",
          "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}",
          RiskLevel.LOW);
  private final SkillCatalog catalog;

  public LoadSkillTool(SkillCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
  }

  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      JsonNode arguments = JSON.readTree(call.argumentsJson());
      JsonNode name = arguments.path("name");
      if (name.isTextual() && !name.asText().isBlank()) {
        SkillCatalog.LoadedSkill loaded = this.catalog.load(name.asText());
        return CompletableFuture.completedFuture(
            new ToolResult(
                call.toolCallId(),
                Status.COMPLETED,
                loaded.content(),
                Optional.empty(),
                Map.of(
                    "skill",
                    loaded.descriptor().name(),
                    "source",
                    loaded.descriptor().source().name(),
                    "truncated",
                    loaded.truncated(),
                    "totalBytes",
                    loaded.totalBytes(),
                    "permissionsUnchanged",
                    true)));
      } else {
        throw new IllegalArgumentException("name must be a non-blank string");
      }
    } catch (RuntimeException | IOException var6) {
      String message =
          var6.getMessage() == null ? var6.getClass().getSimpleName() : var6.getMessage();
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              Status.FAILED,
              "skill load failed: " + message,
              Optional.empty(),
              Map.of()));
    }
  }
}
