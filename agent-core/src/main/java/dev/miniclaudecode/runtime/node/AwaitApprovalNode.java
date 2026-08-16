package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.runtime.AsyncNodeAction;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class AwaitApprovalNode implements AsyncNodeAction<MiniClaudeState> {
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    if (state.pendingApproval().isEmpty()) {
      throw new IllegalStateException("await_approval requires a pending approval request");
    } else {
      return CompletableFuture.completedFuture(
          Map.of(
              "status",
              AgentStatus.WAITING_APPROVAL,
              "trace",
              StateSchema.traceEntry("await_approval")));
    }
  }
}
