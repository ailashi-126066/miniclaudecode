package dev.miniclaudecode.planning;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class PlanScheduler {
  public Optional<PlanStep> next(Plan plan) {
    if (plan.status() != PlanStatus.ACTIVE || plan.currentStep().isPresent()) {
      return Optional.empty();
    }
    Map<String, PlanStep> byId =
        plan.steps().stream().collect(Collectors.toMap(PlanStep::id, Function.identity()));
    return plan.steps().stream()
        .filter(
            step ->
                step.status() == PlanStepStatus.PENDING || step.status() == PlanStepStatus.FAILED)
        .filter(
            step ->
                step.dependsOn().stream()
                    .allMatch(id -> byId.get(id).status() == PlanStepStatus.COMPLETED))
        .findFirst();
  }
}
