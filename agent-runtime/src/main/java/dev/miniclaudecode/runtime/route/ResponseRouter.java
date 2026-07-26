package dev.miniclaudecode.runtime.route;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.runtime.context.ContextPlanner;
import dev.miniclaudecode.runtime.retry.RetryPolicy;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncEdgeAction;

public final class ResponseRouter {
  private final ContextPlanner contextPlanner;
  private final RetryPolicy retryPolicy;

  public ResponseRouter(ContextPlanner contextPlanner, RetryPolicy retryPolicy) {
    this.contextPlanner = Objects.requireNonNull(contextPlanner, "contextPlanner must not be null");
    this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
  }

  public AsyncEdgeAction<MiniClaudeState> afterPrepare() {
    return state ->
        CompletableFuture.completedFuture(
            this.contextPlanner.plan(state.request(), state.messages()).compact()
                    && state.compactionCount() == 0
                ? "compact"
                : "model");
  }

  public AsyncEdgeAction<MiniClaudeState> afterModel() {
    return state -> CompletableFuture.completedFuture(this.routeAfterModel(state));
  }

  private String routeAfterModel(MiniClaudeState state) {
    // Cancellation is terminal and must never be retried. Without this check the cancellation error
    // was fed to the retry policy, so a Ctrl-C during a retry sequence still burned the remaining
    // backoff attempts — each one starting and instantly re-cancelling — before finishing.
    if (state.status() == dev.miniclaudecode.domain.session.AgentStatus.CANCELLED) {
      return "finish";
    }
    if (state.error().isPresent()) {
      if (state.compactionCount() == 0
          && this.contextPlanner.isContextOverflow(
              state.failureType().orElse(""), state.error().orElse(""))) {
        return "compact";
      } else {
        RetryPolicy.Decision retry =
            this.retryPolicy.decide(
                state.failureType().orElse(""),
                state.failureRetryable(),
                state.retryCount(),
                Optional.empty());
        return retry.retry() ? "retry" : "finish";
      }
    } else if (!state.pendingToolCalls().isEmpty()) {
      return "tools";
    } else {
      return !requiresVerification(state) && !hasIncompleteTasks(state) ? "finish" : "verify";
    }
  }

  private static boolean requiresVerification(MiniClaudeState state) {
    if (Boolean.TRUE.equals(state.request().attributes().get("requireVerification"))
        && state.verificationPrompts() < 2) {
      int lastMutation = -1;
      int lastSuccessfulVerification = -1;
      List<AgentMessage> messages = state.messages();

      for (int index = 0; index < messages.size(); index++) {
        Object var6 = messages.get(index);
        if (var6 instanceof ToolMessage) {
          ToolMessage tool = (ToolMessage) var6;
          if (isMutation(tool.qualifiedToolName()) && !tool.error()) {
            lastMutation = index;
          } else if ("shell:run".equals(tool.qualifiedToolName()) && !tool.error()) {
            lastSuccessfulVerification = index;
          }
        }
      }

      return lastMutation > lastSuccessfulVerification;
    } else {
      return false;
    }
  }

  private static boolean isMutation(String name) {
    return "workspace:write".equals(name)
        || "workspace:edit".equals(name)
        || "workspace:apply_patch".equals(name);
  }

  private static boolean hasIncompleteTasks(MiniClaudeState state) {
    if (Boolean.TRUE.equals(state.request().attributes().get("requireTaskCompletion"))
        && state.verificationPrompts() < 2) {
      ToolMessage latestTaskState = null;

      for (AgentMessage message : state.messages()) {
        if (message instanceof ToolMessage) {
          ToolMessage tool = (ToolMessage) message;
          if ("task:todo".equals(tool.qualifiedToolName()) && !tool.error()) {
            latestTaskState = tool;
          }
        }
      }

      return latestTaskState != null
          && (latestTaskState.text().contains("[ ]") || latestTaskState.text().contains("[>]"));
    } else {
      return false;
    }
  }

  public AsyncEdgeAction<MiniClaudeState> afterTools() {
    return state ->
        CompletableFuture.completedFuture(
            state.error().isPresent()
                ? "finish"
                : (state.pendingApproval().isPresent() ? "approval" : "model"));
  }
}
