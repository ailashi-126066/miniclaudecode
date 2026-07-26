package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.runtime.context.DeterministicContextReducer;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncNodeAction;

public final class CompactContextNode implements AsyncNodeAction<MiniClaudeState> {
  private final DeterministicContextReducer reducer;

  public CompactContextNode(DeterministicContextReducer reducer) {
    this.reducer = Objects.requireNonNull(reducer, "reducer must not be null");
  }

  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    Map<String, Object> update = new LinkedHashMap<>();
    update.put("messages", this.reducer.reduce(state.messages()));
    update.put("error", "");
    update.put("failureType", "");
    update.put("failureRetryable", false);
    update.put("status", AgentStatus.RUNNING);
    update.put("compactionCount", state.compactionCount() + 1);
    update.put("trace", StateSchema.traceEntry("compact_context"));
    return CompletableFuture.completedFuture(Map.copyOf(update));
  }
}
