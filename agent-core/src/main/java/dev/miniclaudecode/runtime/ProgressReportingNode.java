package dev.miniclaudecode.runtime;

import dev.miniclaudecode.context.ContextPlanner;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Adds best-effort progress notifications around a real graph node. */
final class ProgressReportingNode implements AsyncNodeAction<MiniClaudeState> {
  private final AsyncNodeAction<MiniClaudeState> delegate;
  private final String beforePhase;
  private final String afterPhase;
  private final TurnProgressListener listener;
  private final ContextPlanner planner = new ContextPlanner();

  ProgressReportingNode(
      AsyncNodeAction<MiniClaudeState> delegate,
      String beforePhase,
      String afterPhase,
      TurnProgressListener listener) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    this.beforePhase = Objects.requireNonNull(beforePhase, "beforePhase must not be null");
    this.afterPhase = Objects.requireNonNull(afterPhase, "afterPhase must not be null");
    this.listener = Objects.requireNonNull(listener, "listener must not be null");
  }

  @Override
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    notify(this.beforePhase, state);
    return this.delegate
        .apply(state)
        .thenApply(
            update -> {
              Map<String, Object> data = new LinkedHashMap<>(state.data());
              update.forEach((key, value) -> data.put(key, value));
              notify(this.afterPhase, new MiniClaudeState(Map.copyOf(data)));
              return update;
            });
  }

  private void notify(String phase, MiniClaudeState state) {
    ContextPlanner.Plan plan = plan(state);
    try {
      this.listener.onProgress(
          new TurnProgressListener.Progress(
              phase,
              state.modelSteps(),
              state.toolSteps(),
              state.compactionCount(),
              plan.estimatedInputTokens(),
              plan.inputBudgetTokens(),
              "",
              0));
    } catch (RuntimeException ignored) {
      // Rendering or audit observers must not alter model/tool safety semantics.
    }
  }

  private ContextPlanner.Plan plan(MiniClaudeState state) {
    Object value = state.providerMetadata().get("inputTokens");
    long providerTokens = value instanceof Number number ? number.longValue() : 0L;
    return this.planner.plan(state.request(), state.messages(), providerTokens);
  }
}
