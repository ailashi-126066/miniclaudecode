package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.planning.Plan;
import dev.miniclaudecode.planning.PlanScheduler;
import dev.miniclaudecode.planning.PlanStatus;
import dev.miniclaudecode.planning.PlanStep;
import dev.miniclaudecode.runtime.AsyncNodeAction;
import dev.miniclaudecode.runtime.PlanProgressListener;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class SelectPlanStepNode implements AsyncNodeAction<MiniClaudeState> {
  private final PlanScheduler scheduler = new PlanScheduler();
  private final Clock clock;
  private final PlanProgressListener listener;

  public SelectPlanStepNode(Clock clock, PlanProgressListener listener) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.listener = Objects.requireNonNull(listener, "listener must not be null");
  }

  @Override
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    Plan plan = state.plan().orElseThrow(() -> new IllegalStateException("no active Plan"));
    Map<String, Object> update = new LinkedHashMap<>();
    if (plan.status() == PlanStatus.COMPLETED) {
      update.put(MiniClaudeState.PLANNING_PHASE, "FINAL_VERIFICATION");
    } else {
      PlanStep next = scheduler.next(plan).orElse(null);
      if (next == null) {
        Plan blocked = plan.block(clock.instant());
        update.put(MiniClaudeState.PLAN, blocked);
        update.put(MiniClaudeState.PLANNING_PHASE, "BLOCKED");
        update.put(MiniClaudeState.STATUS, AgentStatus.FAILED);
        update.put(MiniClaudeState.ERROR, "Plan has no runnable step");
        safelyNotify("PLAN_BLOCKED", blocked);
      } else {
        Plan started = plan.replaceStep(next.start(maximumAttempts(state)), clock.instant());
        update.put(MiniClaudeState.PLAN, started);
        update.put(MiniClaudeState.PLANNING_PHASE, "EXECUTE_STEP");
        safelyNotify("PLAN_STEP_STARTED", started);
      }
    }
    update.put(MiniClaudeState.TRACE, StateSchema.traceEntry("select_step"));
    return CompletableFuture.completedFuture(Map.copyOf(update));
  }

  private static int maximumAttempts(MiniClaudeState state) {
    Object configured = state.request().attributes().get("planningMaxAttemptsPerStep");
    return configured instanceof Number number ? number.intValue() : 2;
  }

  private void safelyNotify(String event, Plan plan) {
    try {
      listener.onPlanChanged(event, plan);
    } catch (RuntimeException ignored) {
      // Best effort only.
    }
  }
}
