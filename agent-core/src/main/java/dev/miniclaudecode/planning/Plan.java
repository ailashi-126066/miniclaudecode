package dev.miniclaudecode.planning;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record Plan(
    UUID id,
    String goal,
    PlanStatus status,
    int version,
    int revisions,
    List<PlanStep> steps,
    Instant createdAt,
    Instant updatedAt)
    implements Serializable {
  public static final int MAX_STEPS = 12;

  public Plan {
    Objects.requireNonNull(id, "id must not be null");
    if (goal == null || goal.isBlank()) {
      throw new IllegalArgumentException("goal must not be blank");
    }
    goal = goal.replaceAll("\\s+", " ").strip();
    Objects.requireNonNull(status, "status must not be null");
    if (version < 1 || revisions < 0) {
      throw new IllegalArgumentException("version must be positive and revisions non-negative");
    }
    steps = List.copyOf(Objects.requireNonNull(steps, "steps must not be null"));
    if (steps.isEmpty() || steps.size() > MAX_STEPS) {
      throw new IllegalArgumentException("plan must contain between 1 and 12 steps");
    }
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    validateGraph(steps);
  }

  public Optional<PlanStep> currentStep() {
    return steps.stream().filter(step -> step.status() == PlanStepStatus.IN_PROGRESS).findFirst();
  }

  public Plan activate(Instant now) {
    if (status != PlanStatus.DRAFT) {
      throw new IllegalStateException("only a draft plan can be activated");
    }
    return copy(PlanStatus.ACTIVE, version + 1, revisions, steps, now);
  }

  public Plan replaceStep(PlanStep replacement, Instant now) {
    List<PlanStep> changed = new ArrayList<>(steps.size());
    boolean found = false;
    for (PlanStep step : steps) {
      if (step.id().equals(replacement.id())) {
        if (step.status() == PlanStepStatus.COMPLETED && !step.equals(replacement)) {
          throw new IllegalStateException("completed steps are immutable");
        }
        changed.add(replacement);
        found = true;
      } else {
        changed.add(step);
      }
    }
    if (!found) {
      throw new IllegalArgumentException("unknown step: " + replacement.id());
    }
    PlanStatus nextStatus =
        changed.stream().allMatch(step -> step.status() == PlanStepStatus.COMPLETED)
            ? PlanStatus.COMPLETED
            : status;
    return copy(nextStatus, version + 1, revisions, changed, now);
  }

  public Plan revise(List<PlanStep> revisedSteps, int maximumRevisions, Instant now) {
    if (revisions >= maximumRevisions) {
      throw new IllegalStateException("plan revision limit exceeded");
    }
    for (PlanStep completed :
        steps.stream().filter(step -> step.status() == PlanStepStatus.COMPLETED).toList()) {
      if (revisedSteps.stream().noneMatch(completed::equals)) {
        throw new IllegalStateException("revision changed a completed step");
      }
    }
    return copy(PlanStatus.ACTIVE, version + 1, revisions + 1, revisedSteps, now);
  }

  public Plan block(Instant now) {
    return copy(PlanStatus.BLOCKED, version + 1, revisions, steps, now);
  }

  private Plan copy(
      PlanStatus nextStatus,
      int nextVersion,
      int nextRevisions,
      List<PlanStep> nextSteps,
      Instant now) {
    return new Plan(id, goal, nextStatus, nextVersion, nextRevisions, nextSteps, createdAt, now);
  }

  private static void validateGraph(List<PlanStep> steps) {
    Set<String> ids = new HashSet<>();
    int inProgress = 0;
    for (PlanStep step : steps) {
      if (!ids.add(step.id())) {
        throw new IllegalArgumentException("duplicate step id: " + step.id());
      }
      if (step.status() == PlanStepStatus.IN_PROGRESS) {
        inProgress++;
      }
    }
    if (inProgress > 1) {
      throw new IllegalArgumentException("only one step may be in progress");
    }
    for (PlanStep step : steps) {
      if (step.dependsOn().contains(step.id()) || !ids.containsAll(step.dependsOn())) {
        throw new IllegalArgumentException("step has an invalid dependency: " + step.id());
      }
      assertAcyclic(step.id(), steps, new HashSet<>(), new HashSet<>());
    }
  }

  private static void assertAcyclic(
      String id, List<PlanStep> steps, Set<String> visiting, Set<String> visited) {
    if (visited.contains(id)) {
      return;
    }
    if (!visiting.add(id)) {
      throw new IllegalArgumentException("plan dependencies contain a cycle");
    }
    PlanStep step = steps.stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    for (String dependency : step.dependsOn()) {
      assertAcyclic(dependency, steps, visiting, visited);
    }
    visiting.remove(id);
    visited.add(id);
  }
}
