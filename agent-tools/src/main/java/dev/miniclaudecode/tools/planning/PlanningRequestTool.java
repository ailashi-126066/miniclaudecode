package dev.miniclaudecode.tools.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolEffect;
import dev.miniclaudecode.domain.tool.ToolResult;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Signals that discovery is complete and execution now needs a durable Plan. */
public final class PlanningRequestTool implements AgentTool {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "planning",
          "request",
          "Create and activate a Plan before using tools that mutate state, start processes, or cause external effects",
          "{\"type\":\"object\",\"properties\":{\"goal\":{\"type\":\"string\"},\"expectedEffects\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"MUTATION\",\"PROCESS\",\"EXTERNAL_EFFECT\"]}}},\"required\":[\"goal\",\"expectedEffects\"]}",
          RiskLevel.LOW,
          ToolEffect.READ_ONLY_LOCAL);

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      JsonNode root = MAPPER.readTree(call.argumentsJson());
      String goal = root.path("goal").asText("").strip();
      if (goal.isEmpty()) {
        throw new IllegalArgumentException("goal must not be blank");
      }
      Set<ToolEffect> effects = new LinkedHashSet<>();
      JsonNode rawEffects = root.path("expectedEffects");
      if (!rawEffects.isArray()) {
        throw new IllegalArgumentException("expectedEffects must be an array");
      }
      rawEffects.forEach(value -> effects.add(ToolEffect.valueOf(value.asText())));
      if (effects.stream().anyMatch(effect -> !effect.requiresPlan())) {
        throw new IllegalArgumentException("expectedEffects may contain only side effects");
      }
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.COMPLETED,
              "Planning requested for: " + goal,
              Optional.empty(),
              Map.of("planningRequested", true, "goal", goal, "expectedEffects", effects)));
    } catch (Exception error) {
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.FAILED,
              "planning request failed: "
                  + java.util.Objects.requireNonNullElse(
                      error.getMessage(), error.getClass().getSimpleName()),
              Optional.empty(),
              Map.of()));
    }
  }
}
