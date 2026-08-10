package dev.miniclaudecode.runtime;

import dev.miniclaudecode.domain.tool.ToolEffect;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable binding between a tool invocation and the only step allowed to perform it. */
public record PlanExecutionContext(UUID planId, String stepId, Set<ToolEffect> expectedEffects) {
  public PlanExecutionContext {
    Objects.requireNonNull(planId, "planId must not be null");
    if (stepId == null || stepId.isBlank()) {
      throw new IllegalArgumentException("stepId must not be blank");
    }
    stepId = stepId.strip();
    expectedEffects =
        Set.copyOf(Objects.requireNonNull(expectedEffects, "expectedEffects must not be null"));
  }
}
