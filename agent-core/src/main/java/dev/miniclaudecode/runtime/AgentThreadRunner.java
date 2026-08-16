package dev.miniclaudecode.runtime;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.Map;
import java.util.Objects;

public final class AgentThreadRunner {
  private final AgentLoop loop;
  private final AgentCheckpointStore checkpointStore;

  public AgentThreadRunner(AgentLoop loop, AgentCheckpointStore checkpointStore) {
    this.loop = Objects.requireNonNull(loop, "loop must not be null");
    this.checkpointStore =
        Objects.requireNonNull(checkpointStore, "checkpointStore must not be null");
  }

  public MiniClaudeState start(SessionId sessionId, ModelRequest request) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(request, "request must not be null");
    MiniClaudeState result = this.loop.run(request);
    persist(sessionId, result);
    return result;
  }

  public MiniClaudeState resume(SessionId sessionId, ApprovalDecision decision) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(decision, "decision must not be null");
    Map<String, Object> checkpoint =
        checkpointStore
            .load(sessionId)
            .orElseThrow(() -> new IllegalStateException("no paused agent loop state"));
    MiniClaudeState result = this.loop.resume(new MiniClaudeState(checkpoint), decision);
    persist(sessionId, result);
    return result;
  }

  private void persist(SessionId sessionId, MiniClaudeState state) {
    try {
      if (state.status() == dev.miniclaudecode.domain.session.AgentStatus.WAITING_APPROVAL) {
        checkpointStore.save(sessionId, Map.copyOf(state.data()));
      } else if (checkpointStore.load(sessionId).isPresent()) {
        checkpointStore.release(sessionId);
      }
    } catch (Exception failure) {
      throw new IllegalStateException("cannot persist agent loop checkpoint", failure);
    }
  }
}
