package dev.miniclaudecode.planning;

import dev.miniclaudecode.domain.tool.ToolEffect;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PlanningInput(
    String goal,
    String discoveryContext,
    List<String> relevantMemories,
    Set<ToolEffect> requestedEffects) {
  public PlanningInput {
    if (goal == null || goal.isBlank()) {
      throw new IllegalArgumentException("goal must not be blank");
    }
    goal = goal.strip();
    discoveryContext = Objects.requireNonNullElse(discoveryContext, "").strip();
    relevantMemories =
        List.copyOf(Objects.requireNonNull(relevantMemories, "relevantMemories must not be null"));
    requestedEffects =
        Set.copyOf(Objects.requireNonNull(requestedEffects, "requestedEffects must not be null"));
  }
}
