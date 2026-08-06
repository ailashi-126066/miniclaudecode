package dev.miniclaudecode.runtime;

import dev.miniclaudecode.context.ContextPlanner;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncNodeAction;

/** A graph-visible context compaction step with deterministic fallback. */
final class SemanticCompactContextNode implements AsyncNodeAction<MiniClaudeState> {
  private final SemanticContextCompactor compactor;
  private final TurnProgressListener listener;
  private final ContextPlanner planner = new ContextPlanner();

  SemanticCompactContextNode(
      dev.miniclaudecode.domain.model.ModelClient model, TurnProgressListener listener) {
    this.compactor = new SemanticContextCompactor(Objects.requireNonNull(model));
    this.listener = Objects.requireNonNull(listener, "listener must not be null");
  }

  @Override
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    ContextPlanner.Plan before = plan(state);
    String reason =
        this.planner.isContextOverflow(state.failureType().orElse(""), state.error().orElse(""))
            ? "provider_overflow"
            : "preflight_threshold";
    return this.compactor
        .compact(state.request(), state.messages())
        .thenApply(
            messages -> {
              ContextPlanner.Plan after =
                  this.planner.plan(state.request(), messages, providerInputTokens(state));
              notify(
                  new TurnProgressListener.Progress(
                      "compaction",
                      state.modelSteps(),
                      state.toolSteps(),
                      state.compactionCount() + 1,
                      after.estimatedInputTokens(),
                      after.inputBudgetTokens(),
                      reason,
                      before.estimatedInputTokens()));
              return Map.of(
                  MiniClaudeState.MESSAGES,
                  messages,
                  MiniClaudeState.COMPACTION_COUNT,
                  state.compactionCount() + 1,
                  MiniClaudeState.TRACE,
                  StateSchema.traceEntry("compact_context"));
            });
  }

  private ContextPlanner.Plan plan(MiniClaudeState state) {
    return this.planner.plan(state.request(), state.messages(), providerInputTokens(state));
  }

  private static long providerInputTokens(MiniClaudeState state) {
    Object value = state.providerMetadata().get("inputTokens");
    return value instanceof Number number ? number.longValue() : 0L;
  }

  private void notify(TurnProgressListener.Progress progress) {
    try {
      this.listener.onProgress(progress);
    } catch (RuntimeException ignored) {
      // Rendering or audit observers must not alter compaction semantics.
    }
  }
}
