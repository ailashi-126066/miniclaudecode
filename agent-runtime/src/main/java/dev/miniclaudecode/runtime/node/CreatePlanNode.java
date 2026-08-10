package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.tool.ToolEffect;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.planning.Plan;
import dev.miniclaudecode.planning.PlanningInput;
import dev.miniclaudecode.planning.TaskPlanner;
import dev.miniclaudecode.runtime.PlanProgressListener;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncNodeAction;

public final class CreatePlanNode implements AsyncNodeAction<MiniClaudeState> {
  private final TaskPlanner planner;
  private final Clock clock;
  private final PlanProgressListener listener;

  public CreatePlanNode(TaskPlanner planner, Clock clock, PlanProgressListener listener) {
    this.planner = Objects.requireNonNull(planner, "planner must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.listener = Objects.requireNonNull(listener, "listener must not be null");
  }

  @Override
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    PlanningInput input = input(state);
    return planner
        .createPlan(input, state.request())
        .thenApply(
            draft -> {
              Plan plan = draft.activate(clock.instant());
              safelyNotify("PLAN_CREATED", plan);
              Map<String, Object> update = new LinkedHashMap<>();
              update.put(MiniClaudeState.PLAN, plan);
              update.put(MiniClaudeState.PLANNING_PHASE, "SELECT_STEP");
              update.put(MiniClaudeState.PENDING_TOOL_CALLS, List.of());
              update.put(MiniClaudeState.FINAL_TEXT, "");
              update.put(MiniClaudeState.STEP_DECISION, "");
              update.put(MiniClaudeState.TRACE, StateSchema.traceEntry("create_plan"));
              return Map.copyOf(update);
            })
        .toCompletableFuture();
  }

  private PlanningInput input(MiniClaudeState state) {
    String goal =
        state.messages().stream()
            .filter(UserMessage.class::isInstance)
            .map(AgentMessage::text)
            .reduce((first, ignored) -> first)
            .orElse("Complete the requested task");
    Set<ToolEffect> effects = new LinkedHashSet<>();
    for (ToolResult result : state.toolResults()) {
      if (Boolean.TRUE.equals(result.metadata().get("planningRequested"))) {
        Object requestedGoal = result.metadata().get("goal");
        if (requestedGoal instanceof String text && !text.isBlank()) {
          goal = text;
        }
        Object raw = result.metadata().get("expectedEffects");
        if (raw instanceof Iterable<?> values) {
          for (Object value : values) {
            effects.add(
                value instanceof ToolEffect effect
                    ? effect
                    : ToolEffect.valueOf(String.valueOf(value)));
          }
        }
      }
    }
    String discovery =
        state.messages().stream()
            .skip(Math.max(0, state.messages().size() - 12L))
            .map(message -> message.getClass().getSimpleName() + ": " + message.text())
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
    return new PlanningInput(goal, discovery, List.of(), effects);
  }

  private void safelyNotify(String event, Plan plan) {
    try {
      listener.onPlanChanged(event, plan);
    } catch (RuntimeException ignored) {
      // Plan execution must not fail because progress reporting failed.
    }
  }
}
