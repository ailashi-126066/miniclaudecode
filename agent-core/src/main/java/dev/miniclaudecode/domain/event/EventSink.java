package dev.miniclaudecode.domain.event;

@FunctionalInterface
public interface EventSink {
  EventSink NOOP = event -> {};

  void emit(AgentEvent event);
}
