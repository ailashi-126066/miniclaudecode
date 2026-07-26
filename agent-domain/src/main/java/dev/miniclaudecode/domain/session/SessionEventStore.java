package dev.miniclaudecode.domain.session;

import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.EventSink;
import java.util.List;
import java.util.Objects;

public interface SessionEventStore extends EventSink {
  void append(AgentEvent event);

  SessionEventStore.ReadResult read(SessionId sessionId);

  @Override
  default void emit(AgentEvent event) {
    this.append(event);
  }

  public static record ReadResult(List<AgentEvent> events, List<String> warnings) {
    public ReadResult(List<AgentEvent> events, List<String> warnings) {
      events = List.copyOf(Objects.requireNonNull(events, "events must not be null"));
      warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings must not be null"));
      this.events = events;
      this.warnings = warnings;
    }
  }
}
