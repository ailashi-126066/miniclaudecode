package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncNodeAction;

public final class FinishNode implements AsyncNodeAction<MiniClaudeState> {
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    // A cancelled turn also carries an error message so the reason reaches the transcript, but it
    // is not a failure: recomputing the status purely from `error` used to turn every Ctrl-C into a
    // red FAILED banner and an ERROR audit event, leaving the CANCELLED branch downstream dead.
    AgentStatus status = status(state);
    return CompletableFuture.completedFuture(
        Map.of("status", status, "trace", StateSchema.traceEntry("finish")));
  }

  private static AgentStatus status(MiniClaudeState state) {
    if (state.status() == AgentStatus.CANCELLED) {
      return AgentStatus.CANCELLED;
    }
    return state.error().isPresent() ? AgentStatus.FAILED : AgentStatus.COMPLETED;
  }
}
