package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.cli.StreamingRenderer.RenderEvent;
import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.model.ModelStreamEvent.TextDelta;
import dev.miniclaudecode.domain.model.ModelStreamEvent.ThinkingDelta;
import dev.miniclaudecode.domain.model.ModelStreamEvent.UsageReported;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class AuditedModelClientTest {
  @Test
  void auditsAndObservesAllPromptCacheUsageFields() {
    UsageReported usage = new UsageReported(100L, 12L, 70L, 10L);
    ModelClient delegate = request -> publisher(usage);
    List<AgentEvent> audit = new ArrayList<>();
    AtomicReference<UsageReported> observed = new AtomicReference<>();
    AuditedModelClient client =
        new AuditedModelClient(
            delegate,
            new SessionId("session-1"),
            new TurnId(1L),
            audit::add,
            ignored -> {},
            Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC),
            observed::set);
    client.stream(request()).subscribe(new AuditedModelClientTest.RequestAllSubscriber());
    Assertions.assertThat(observed).hasValue(usage);
    Assertions.assertThat(audit)
        .singleElement()
        .satisfies(
            event ->
                Assertions.assertThat(event.payload())
                    .containsEntry("inputTokens", 100L)
                    .containsEntry("outputTokens", 12L)
                    .containsEntry("cacheReadTokens", 70L)
                    .containsEntry("cacheWriteTokens", 10L));
  }

  @Test
  void mergesConsecutiveTextDeltasIntoOneAuditEventWithoutDelayingTheRenderer() {
    List<AgentEvent> audit = new ArrayList<>();
    List<String> rendered = new ArrayList<>();
    AuditedModelClient client =
        client(
            audit,
            event -> {
              if (event instanceof RenderEvent.Text text) {
                rendered.add(text.text());
              }
            },
            new TextDelta("Hel"),
            new TextDelta("lo "),
            new TextDelta("world"));
    client.stream(request()).subscribe(new AuditedModelClientTest.RequestAllSubscriber());
    // The renderer saw every delta as it streamed; the audit log got one merged entry at
    // completion.
    Assertions.assertThat(rendered).containsExactly("Hel", "lo ", "world");
    Assertions.assertThat(audit)
        .singleElement()
        .satisfies(
            event -> {
              Assertions.assertThat(event.type()).isEqualTo(AgentEventType.ASSISTANT_MESSAGE);
              Assertions.assertThat(event.payload()).containsEntry("delta", "Hello world");
            });
  }

  @Test
  void flushesBufferedDeltasBeforeAnyNonDeltaEventToKeepAuditOrder() {
    List<AgentEvent> audit = new ArrayList<>();
    AuditedModelClient client =
        client(audit, ignored -> {}, new TextDelta("answer"), new UsageReported(10L, 2L, 0L, 0L));
    client.stream(request()).subscribe(new AuditedModelClientTest.RequestAllSubscriber());
    Assertions.assertThat(audit)
        .extracting(AgentEvent::type)
        .containsExactly(AgentEventType.ASSISTANT_MESSAGE, AgentEventType.MODEL_USAGE);
  }

  @Test
  void switchingBetweenThinkingAndTextFlushesSoOrderIsPreserved() {
    List<AgentEvent> audit = new ArrayList<>();
    AuditedModelClient client =
        client(
            audit,
            ignored -> {},
            new ThinkingDelta("planning"),
            new TextDelta("doing"),
            new ThinkingDelta("more"));
    client.stream(request()).subscribe(new AuditedModelClientTest.RequestAllSubscriber());
    Assertions.assertThat(audit)
        .extracting(AgentEvent::type)
        .containsExactly(
            AgentEventType.PROVIDER_THINKING,
            AgentEventType.ASSISTANT_MESSAGE,
            AgentEventType.PROVIDER_THINKING);
    Assertions.assertThat(audit.get(0).payload()).containsEntry("text", "planning");
    Assertions.assertThat(audit.get(1).payload()).containsEntry("delta", "doing");
    Assertions.assertThat(audit.get(2).payload()).containsEntry("text", "more");
  }

  @Test
  void flushesWhenTheBufferedTextReachesTheSizeThreshold() {
    List<AgentEvent> audit = new ArrayList<>();
    String half = "x".repeat(3000);
    AuditedModelClient client =
        client(audit, ignored -> {}, new TextDelta(half), new TextDelta(half));
    client.stream(request()).subscribe(new AuditedModelClientTest.RequestAllSubscriber());
    // 6000 chars crosses the 4096 threshold on the second delta: flushed right there, and the
    // stream-end flush finds nothing left.
    Assertions.assertThat(audit)
        .singleElement()
        .satisfies(
            event -> Assertions.assertThat(event.payload()).containsEntry("delta", half + half));
  }

  private static AuditedModelClient client(
      List<AgentEvent> audit,
      java.util.function.Consumer<RenderEvent> renderer,
      ModelStreamEvent... events) {
    return new AuditedModelClient(
        request -> publisher(events),
        new SessionId("session-1"),
        new TurnId(1L),
        audit::add,
        renderer,
        Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC),
        ignored -> {});
  }

  private static Publisher<ModelStreamEvent> publisher(ModelStreamEvent... events) {
    return subscriber ->
        subscriber.onSubscribe(
            new Subscription() {
              private boolean done;

              @Override
              public void request(long count) {
                if (!this.done) {
                  this.done = true;
                  for (ModelStreamEvent event : events) {
                    subscriber.onNext(event);
                  }
                  subscriber.onComplete();
                }
              }

              @Override
              public void cancel() {
                this.done = true;
              }
            });
  }

  private static ModelRequest request() {
    return new ModelRequest(
        "test", "model", List.of(new UserMessage("hello")), List.of(), false, 100, Map.of());
  }

  private static final class RequestAllSubscriber implements Subscriber<ModelStreamEvent> {
    @Override
    public void onSubscribe(Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    public void onNext(ModelStreamEvent item) {}

    @Override
    public void onError(Throwable throwable) {
      throw new AssertionError(throwable);
    }

    @Override
    public void onComplete() {}
  }
}
