package dev.miniclaudecode.domain.event;

import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentEventTest {
  @Test
  void snapshotsPayloadAndCarriesAuditIdentity() {
    Map<String, Object> payload = new HashMap<>();
    payload.put("text", "fix the tests");
    AgentEvent event =
        new AgentEvent(
            UUID.randomUUID(),
            1,
            SessionId.of("session-1"),
            TurnId.of(1L),
            Instant.parse("2026-07-20T12:00:00Z"),
            AgentEventType.USER_MESSAGE,
            payload);
    payload.put("text", "mutated");
    Assertions.assertThat(event.payload()).containsEntry("text", "fix the tests");
    Assertions.assertThat(event.version()).isEqualTo(1);
    Assertions.assertThat(event.eventId()).isNotNull();
  }

  @Test
  void rejectsIncompleteAuditFields() {
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(
                    () ->
                        new AgentEvent(
                            null,
                            1,
                            SessionId.of("session-1"),
                            TurnId.of(1L),
                            Instant.now(),
                            AgentEventType.ERROR,
                            Map.of()))
                .isInstanceOf(NullPointerException.class))
        .hasMessageContaining("eventId");
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(
                    () ->
                        new AgentEvent(
                            UUID.randomUUID(),
                            0,
                            SessionId.of("session-1"),
                            TurnId.of(1L),
                            Instant.now(),
                            AgentEventType.ERROR,
                            Map.of()))
                .isInstanceOf(IllegalArgumentException.class))
        .hasMessageContaining("version");
  }
}
