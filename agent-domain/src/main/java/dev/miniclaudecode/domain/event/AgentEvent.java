package dev.miniclaudecode.domain.event;

import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AgentEvent(
    UUID eventId,
    int version,
    SessionId sessionId,
    TurnId turnId,
    Instant occurredAt,
    AgentEventType type,
    Map<String, Object> payload) {
  public static final int CURRENT_VERSION = 1;

  public AgentEvent(
      UUID eventId,
      int version,
      SessionId sessionId,
      TurnId turnId,
      Instant occurredAt,
      AgentEventType type,
      Map<String, Object> payload) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    if (version < 1) {
      throw new IllegalArgumentException("version must be greater than zero");
    } else {
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(turnId, "turnId must not be null");
      Objects.requireNonNull(occurredAt, "occurredAt must not be null");
      Objects.requireNonNull(type, "type must not be null");
      payload = Map.copyOf(Objects.requireNonNull(payload, "payload must not be null"));
      this.eventId = eventId;
      this.version = version;
      this.sessionId = sessionId;
      this.turnId = turnId;
      this.occurredAt = occurredAt;
      this.type = type;
      this.payload = payload;
    }
  }

  public static AgentEvent create(
      SessionId sessionId,
      TurnId turnId,
      AgentEventType type,
      Map<String, Object> payload,
      Clock clock) {
    Objects.requireNonNull(clock, "clock must not be null");
    return new AgentEvent(UUID.randomUUID(), 1, sessionId, turnId, clock.instant(), type, payload);
  }
}
