package dev.miniclaudecode.persistence.event;

import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.session.SessionEventStore.ReadResult;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.persistence.config.SecretRedactor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JsonlTailRecoveryTest {
  private Path eventRoot;
  private EventJsonCodec codec;

  @BeforeEach
  void setUp() throws Exception {
    this.eventRoot = Path.of("target", "test-work", UUID.randomUUID().toString());
    Files.createDirectories(this.eventRoot);
    this.codec = new EventJsonCodec(new SecretRedactor(), Set.of());
  }

  @Test
  void ignoresAnIncompleteTailWithoutLosingCompleteHistory() throws Exception {
    SessionId sessionId = SessionId.of("recover-tail");
    Path file = this.eventRoot.resolve("recover-tail.jsonl");
    Files.writeString(
        file, this.codec.encode(event(sessionId)) + System.lineSeparator() + "{\"version\":1");
    ReadResult result = new JsonlEventStore(this.eventRoot, this.codec).read(sessionId);
    Assertions.assertThat(result.events()).hasSize(1);
    Assertions.assertThat(result.warnings()).singleElement().asString().contains("incomplete tail");
  }

  @Test
  void skipsFutureEventVersionsAndContinuesReading() throws Exception {
    SessionId sessionId = SessionId.of("future-version");
    Path file = this.eventRoot.resolve("future-version.jsonl");
    String future = this.codec.encode(event(sessionId)).replace("\"version\":1", "\"version\":999");
    Files.writeString(
        file,
        future
            + System.lineSeparator()
            + this.codec.encode(event(sessionId))
            + System.lineSeparator());
    ReadResult result = new JsonlEventStore(this.eventRoot, this.codec).read(sessionId);
    Assertions.assertThat(result.events()).hasSize(1);
    Assertions.assertThat(result.warnings()).singleElement().asString().contains("version 999");
  }

  private static AgentEvent event(SessionId sessionId) {
    return new AgentEvent(
        UUID.randomUUID(),
        1,
        sessionId,
        TurnId.of(1L),
        Instant.parse("2026-07-20T12:00:00Z"),
        AgentEventType.USER_MESSAGE,
        Map.of("message", "hello"));
  }
}
