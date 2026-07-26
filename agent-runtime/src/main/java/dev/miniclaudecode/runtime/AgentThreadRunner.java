package dev.miniclaudecode.runtime;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.Objects;

public final class AgentThreadRunner {

  private final AgentGraphFactory graph;

  public AgentThreadRunner(AgentGraphFactory graph) {
    this.graph = Objects.requireNonNull(graph, "graph must not be null");
  }

  public MiniClaudeState start(SessionId sessionId, ModelRequest request) {
    return graph.start(sessionId, request);
  }

  public MiniClaudeState resume(SessionId sessionId, ApprovalDecision decision) {
    return graph.resume(sessionId, decision);
  }
}
