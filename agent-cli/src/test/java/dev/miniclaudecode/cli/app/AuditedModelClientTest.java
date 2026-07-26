package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
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

  private static Publisher<ModelStreamEvent> publisher(ModelStreamEvent event) {
    return subscriber ->
        subscriber.onSubscribe(
            new Subscription() {
              private boolean done;

              @Override
              public void request(long count) {
                if (!this.done) {
                  this.done = true;
                  subscriber.onNext(event);
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
