package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.runtime.AsyncNodeAction;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class PrepareContextNode implements AsyncNodeAction<MiniClaudeState> {
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    return CompletableFuture.completedFuture(
        Map.of(
            "messages",
            state.request().messages(),
            "status",
            AgentStatus.RUNNING,
            "trace",
            StateSchema.traceEntry("prepare_context")));
  }
}
