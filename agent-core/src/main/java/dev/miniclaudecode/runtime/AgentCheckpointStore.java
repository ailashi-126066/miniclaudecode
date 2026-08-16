package dev.miniclaudecode.runtime;

import dev.miniclaudecode.domain.session.SessionId;
import java.util.Map;
import java.util.Optional;

/** Durable paused-state storage used for approval resume. */
public interface AgentCheckpointStore {
  Optional<Map<String, Object>> load(SessionId sessionId);

  void save(SessionId sessionId, Map<String, Object> state);

  void release(SessionId sessionId);
}
