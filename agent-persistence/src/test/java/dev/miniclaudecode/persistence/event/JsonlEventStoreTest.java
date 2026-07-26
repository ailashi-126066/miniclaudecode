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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JsonlEventStoreTest {
  private Path eventRoot;

  @BeforeEach
  void createEventRoot() throws Exception {
    this.eventRoot = Path.of("target", "test-work", UUID.randomUUID().toString());
    Files.createDirectories(this.eventRoot);
  }

  @Test
  void appendsEventsInOrderAndRedactsSecretsBeforeFlush() throws Exception {
    SessionId sessionId = SessionId.of("session-a");
    JsonlEventStore store =
        new JsonlEventStore(this.eventRoot, new SecretRedactor(), Set.of("sk-do-not-write"));
    store.append(event(sessionId, 1L, Map.of("message", "first sk-do-not-write")));
    store.append(event(sessionId, 2L, Map.of("authorization", "Bearer unlisted-secret")));
    ReadResult result = store.read(sessionId);
    Assertions.assertThat(result.events())
        .extracting(event -> event.turnId().value())
        .containsExactly(new Long[] {1L, 2L});
    Assertions.assertThat(((AgentEvent) result.events().get(0)).payload().get("message"))
        .isEqualTo("first ***");
    Assertions.assertThat(((AgentEvent) result.events().get(1)).payload().get("authorization"))
        .isEqualTo("***");
    Assertions.assertThat(result.warnings()).isEmpty();
    Assertions.assertThat(Files.readString(this.eventRoot.resolve("session-a.jsonl")))
        .doesNotContain(new CharSequence[] {"sk-do-not-write", "unlisted-secret"});
  }

  @Test
  void serializesConcurrentAppendsAsCompleteLines() throws Exception {
    SessionId sessionId = SessionId.of("session-concurrent");
    JsonlEventStore store = new JsonlEventStore(this.eventRoot, new SecretRedactor(), Set.of());

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (Future<?> future :
          IntStream.rangeClosed(1, 40)
              .mapToObj(
                  index ->
                      executor.submit(() -> store.append(event(sessionId, (long) index, Map.of()))))
              .toList()) {
        future.get();
      }
    }

    ReadResult result = store.read(sessionId);
    Assertions.assertThat(result.events()).hasSize(40);
    Assertions.assertThat(result.events()).extracting(AgentEvent::eventId).doesNotHaveDuplicates();
    Assertions.assertThat(result.warnings()).isEmpty();
    Assertions.assertThat(Files.readAllLines(this.eventRoot.resolve("session-concurrent.jsonl")))
        .hasSize(40);
  }

  @Test
  void appendAllWritesEveryEventInOrderAcrossSessions() throws Exception {
    SessionId first = SessionId.of("session-batch-a");
    SessionId second = SessionId.of("session-batch-b");
    JsonlEventStore store = new JsonlEventStore(this.eventRoot, new SecretRedactor(), Set.of());
    store.appendAll(
        java.util.List.of(
            event(first, 1L, Map.of()),
            event(second, 2L, Map.of()),
            event(first, 3L, Map.of()),
            event(first, 4L, Map.of())));
    Assertions.assertThat(store.read(first).events())
        .extracting(event -> event.turnId().value())
        .containsExactly(new Long[] {1L, 3L, 4L});
    Assertions.assertThat(store.read(second).events())
        .extracting(event -> event.turnId().value())
        .containsExactly(new Long[] {2L});
    Assertions.assertThatCode(() -> store.appendAll(java.util.List.of()))
        .doesNotThrowAnyException();
  }

  private static AgentEvent event(SessionId sessionId, long turn, Map<String, Object> payload) {
    return new AgentEvent(
        UUID.randomUUID(),
        1,
        sessionId,
        TurnId.of(turn),
        Instant.parse("2026-07-20T12:00:00Z").plusSeconds(turn),
        AgentEventType.USER_MESSAGE,
        payload);
  }
}
