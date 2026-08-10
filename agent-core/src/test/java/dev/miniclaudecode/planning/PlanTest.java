package dev.miniclaudecode.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.miniclaudecode.domain.tool.ToolEffect;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanTest {
  private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

  @Test
  void schedulerHonorsDependenciesAndSingleActiveStep() {
    PlanStep inspect = step("inspect", List.of(), PlanStepStatus.COMPLETED);
    PlanStep change = step("change", List.of("inspect"), PlanStepStatus.PENDING);
    Plan plan = plan(List.of(inspect, change)).activate(NOW.plusSeconds(1));

    assertThat(new PlanScheduler().next(plan)).contains(change);
    Plan started = plan.replaceStep(change.start(2), NOW.plusSeconds(2));
    assertThat(new PlanScheduler().next(started)).isEmpty();
  }

  @Test
  void rejectsCyclesAndChangesToCompletedSteps() {
    assertThatThrownBy(
            () ->
                plan(
                    List.of(
                        step("one", List.of("two"), PlanStepStatus.PENDING),
                        step("two", List.of("one"), PlanStepStatus.PENDING))))
        .isInstanceOf(IllegalArgumentException.class);

    Plan original = plan(List.of(step("done", List.of(), PlanStepStatus.COMPLETED)));
    assertThatThrownBy(
            () ->
                original.replaceStep(
                    step("done", List.of(), PlanStepStatus.PENDING), NOW.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class);
  }

  private static Plan plan(List<PlanStep> steps) {
    return new Plan(UUID.randomUUID(), "goal", PlanStatus.DRAFT, 1, 0, steps, NOW, NOW);
  }

  private static PlanStep step(String id, List<String> dependencies, PlanStepStatus status) {
    return new PlanStep(
        id,
        id,
        dependencies,
        List.of("verified"),
        Set.of(ToolEffect.READ_ONLY_LOCAL),
        status,
        0,
        Optional.empty());
  }
}
