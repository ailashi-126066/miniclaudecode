package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.runtime.retry.RetryPolicy;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bsc.langgraph4j.action.AsyncNodeAction;

public final class RecoverErrorNode implements AsyncNodeAction<MiniClaudeState> {
  private final RetryPolicy retryPolicy;

  public RecoverErrorNode(RetryPolicy retryPolicy) {
    this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
  }

  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    RetryPolicy.Decision decision =
        this.retryPolicy.decide(
            state.failureType().orElse("model_error"),
            state.failureRetryable(),
            state.retryCount(),
            Optional.empty(),
            maximumRetries(state));
    return !decision.retry()
        ? CompletableFuture.completedFuture(Map.of())
        : CompletableFuture.supplyAsync(
            () -> recovered(state),
            CompletableFuture.delayedExecutor(decision.delay().toMillis(), TimeUnit.MILLISECONDS));
  }

  private static int maximumRetries(MiniClaudeState state) {
    Object configured = state.request().attributes().get("maxRetries");
    return configured instanceof Number number ? number.intValue() : 3;
  }

  private static Map<String, Object> recovered(MiniClaudeState state) {
    Map<String, Object> update = new LinkedHashMap<>();
    update.put("error", "");
    update.put("status", AgentStatus.RUNNING);
    update.put("retryCount", state.retryCount() + 1);
    update.put("trace", StateSchema.traceEntry("recover_error"));
    return Map.copyOf(update);
  }
}
