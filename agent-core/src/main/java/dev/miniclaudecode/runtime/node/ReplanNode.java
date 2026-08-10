package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.planning.Plan;
import dev.miniclaudecode.planning.PlanStep;
import dev.miniclaudecode.planning.PlanStepStatus;
import dev.miniclaudecode.planning.PlanningInput;
import dev.miniclaudecode.planning.TaskPlanner;
import dev.miniclaudecode.runtime.PlanProgressListener;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncNodeAction;

/** Revises only unfinished work; completed steps remain byte-for-byte immutable. */
public final class ReplanNode implements AsyncNodeAction<MiniClaudeState> {
  private final Clock clock;
  private final PlanProgressListener listener;
  private final TaskPlanner planner;

  public ReplanNode(TaskPlanner planner, Clock clock, PlanProgressListener listener) {
    this.planner = Objects.requireNonNull(planner, "planner must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.listener = Objects.requireNonNull(listener, "listener must not be null");
  }

  @Override
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    Plan plan = state.plan().orElseThrow(() -> new IllegalStateException("no Plan to revise"));
    Set<dev.miniclaudecode.domain.tool.ToolEffect> effects = new LinkedHashSet<>();
    plan.steps().stream()
        .filter(step -> step.status() != PlanStepStatus.COMPLETED)
        .forEach(step -> effects.addAll(step.expectedEffects()));
    String context =
        "Previous Plan version "
            + plan.version()
            + " failed after "
            + plan.steps().stream()
                .filter(step -> step.status() != PlanStepStatus.COMPLETED)
                .map(
                    step ->
                        step.id()
                            + ": "
                            + step.evidence()
                                .flatMap(evidence -> evidence.failureReason())
                                .orElse("not completed"))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("unknown failure")
            + "\nCompleted steps are immutable and must not be repeated: "
            + plan.steps().stream()
                .filter(step -> step.status() == PlanStepStatus.COMPLETED)
                .map(PlanStep::id)
                .toList();
    PlanningInput input = new PlanningInput(plan.goal(), context, List.of(), effects);
    return planner
        .createPlan(input, state.request())
        .thenApply(draft -> revisedState(state, plan, draft))
        .toCompletableFuture();
  }

  private Map<String, Object> revisedState(MiniClaudeState state, Plan plan, Plan draft) {
    int revision = plan.revisions() + 1;
    Map<String, String> replacementIds = new LinkedHashMap<>();
    draft.steps().forEach(step -> replacementIds.put(step.id(), "r" + revision + "-" + step.id()));
    List<PlanStep> revised = new ArrayList<>();
    List<PlanStep> completed =
        plan.steps().stream().filter(step -> step.status() == PlanStepStatus.COMPLETED).toList();
    revised.addAll(completed);
    for (PlanStep step : draft.steps()) {
      if (revised.size() >= Plan.MAX_STEPS) {
        break;
      }
      List<String> dependencies = new ArrayList<>(completed.stream().map(PlanStep::id).toList());
      dependencies.addAll(
          step.dependsOn().stream().map(id -> replacementIds.getOrDefault(id, id)).toList());
      revised.add(
          new PlanStep(
              replacementIds.get(step.id()),
              step.description(),
              dependencies.stream().distinct().toList(),
              step.acceptanceCriteria(),
              step.expectedEffects(),
              PlanStepStatus.PENDING,
              0,
              Optional.empty()));
    }
    Object configured = state.request().attributes().get("planningMaxRevisions");
    int maximumRevisions = configured instanceof Number number ? Math.min(1, number.intValue()) : 1;
    Plan changed = plan.revise(revised, maximumRevisions, clock.instant());
    safelyNotify("PLAN_REVISED", changed);
    return Map.of(
        MiniClaudeState.PLAN,
        changed,
        MiniClaudeState.PLANNING_PHASE,
        "SELECT_STEP",
        MiniClaudeState.STEP_DECISION,
        "",
        MiniClaudeState.TRACE,
        StateSchema.traceEntry("replan"));
  }

  private void safelyNotify(String event, Plan plan) {
    try {
      listener.onPlanChanged(event, plan);
    } catch (RuntimeException ignored) {
      // Best effort only.
    }
  }
}
