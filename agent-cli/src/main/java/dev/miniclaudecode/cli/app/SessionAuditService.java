package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.session.SessionEventStore.ReadResult;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.persistence.config.SecretRedactor;
import dev.miniclaudecode.persistence.event.JsonlEventStore;
import dev.miniclaudecode.persistence.path.UserDataLayout;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

/** Owns durable session events and keeps audit-path construction out of the turn coordinator. */
final class SessionAuditService {
  private final Path eventsRoot;
  private final JsonlEventStore store;
  private final Clock clock;

  SessionAuditService(
      Path workspace, UserDataLayout layout, Set<String> knownSecrets, Clock clock) {
    this.eventsRoot = layout.sessionWorkspaceRoot(workspace).resolve("events");
    this.store = new JsonlEventStore(eventsRoot, new SecretRedactor(), knownSecrets);
    this.clock = clock;
  }

  void emit(SessionId sessionId, TurnId turnId, AgentEventType type, Map<String, Object> payload) {
    store.append(AgentEvent.create(sessionId, turnId, type, payload, clock));
  }

  ReadResult read(SessionId sessionId) {
    return store.read(sessionId);
  }

  JsonlEventStore store() {
    return store;
  }

  Path eventsRoot() {
    return eventsRoot;
  }
}
