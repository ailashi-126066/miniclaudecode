package dev.miniclaudecode.planning;

import dev.miniclaudecode.domain.tool.ToolEffect;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record PlanStep(
    String id,
    String description,
    List<String> dependsOn,
    List<String> acceptanceCriteria,
    Set<ToolEffect> expectedEffects,
    PlanStepStatus status,
    int attempts,
    Optional<StepEvidence> evidence)
    implements Serializable {
  public PlanStep {
    id = text(id, "id");
    description = text(description, "description");
    dependsOn = normalized(dependsOn, "dependsOn");
    acceptanceCriteria = normalized(acceptanceCriteria, "acceptanceCriteria");
    if (acceptanceCriteria.isEmpty()) {
      throw new IllegalArgumentException("acceptanceCriteria must not be empty");
    }
    expectedEffects =
        Set.copyOf(Objects.requireNonNull(expectedEffects, "expectedEffects must not be null"));
    Objects.requireNonNull(status, "status must not be null");
    if (attempts < 0) {
      throw new IllegalArgumentException("attempts must not be negative");
    }
    evidence = Objects.requireNonNull(evidence, "evidence must not be null");
  }

  @Override
  public List<String> dependsOn() {
    return List.copyOf(this.dependsOn);
  }

  @Override
  public List<String> acceptanceCriteria() {
    return List.copyOf(this.acceptanceCriteria);
  }

  public PlanStep start(int maximumAttempts) {
    if (status != PlanStepStatus.PENDING && status != PlanStepStatus.FAILED) {
      throw new IllegalStateException("only pending or failed steps can start");
    }
    if (attempts >= maximumAttempts) {
      throw new IllegalStateException("step attempt limit exceeded");
    }
    return new PlanStep(
        id,
        description,
        dependsOn,
        acceptanceCriteria,
        expectedEffects,
        PlanStepStatus.IN_PROGRESS,
        attempts + 1,
        evidence);
  }

  public PlanStep complete(StepEvidence completedEvidence) {
    if (status != PlanStepStatus.IN_PROGRESS) {
      throw new IllegalStateException("only an in-progress step can complete");
    }
    return withStatus(PlanStepStatus.COMPLETED, Optional.of(completedEvidence));
  }

  public PlanStep fail(StepEvidence failedEvidence) {
    if (status != PlanStepStatus.IN_PROGRESS) {
      throw new IllegalStateException("only an in-progress step can fail");
    }
    return withStatus(PlanStepStatus.FAILED, Optional.of(failedEvidence));
  }

  private PlanStep withStatus(PlanStepStatus next, Optional<StepEvidence> nextEvidence) {
    return new PlanStep(
        id,
        description,
        dependsOn,
        acceptanceCriteria,
        expectedEffects,
        next,
        attempts,
        nextEvidence);
  }

  private Object writeReplace() {
    return new SerializedForm(
        id,
        description,
        dependsOn,
        acceptanceCriteria,
        expectedEffects,
        status,
        attempts,
        evidence.orElse(null));
  }

  private record SerializedForm(
      String id,
      String description,
      List<String> dependsOn,
      List<String> acceptanceCriteria,
      Set<ToolEffect> expectedEffects,
      PlanStepStatus status,
      int attempts,
      StepEvidence evidence)
      implements Serializable {
    private Object readResolve() {
      return new PlanStep(
          id,
          description,
          dependsOn,
          acceptanceCriteria,
          expectedEffects,
          status,
          attempts,
          Optional.ofNullable(evidence));
    }
  }

  private static List<String> normalized(List<String> values, String field) {
    Objects.requireNonNull(values, field + " must not be null");
    return values.stream().map(value -> text(value, field)).distinct().toList();
  }

  private static String text(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.replaceAll("\\s+", " ").strip();
  }
}
