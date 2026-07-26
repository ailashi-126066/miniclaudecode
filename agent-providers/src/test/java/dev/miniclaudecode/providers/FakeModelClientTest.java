package dev.miniclaudecode.providers;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class FakeModelClientTest {

  @Test
  void publishesScriptedThinkingTextToolUsageAndCompletionInOrder() {
    List<ModelStreamEvent> script =
        List.of(
            new ModelStreamEvent.ThinkingDelta("inspect"),
            new ModelStreamEvent.TextDelta("I will read it."),
            new ModelStreamEvent.ToolCallStarted("call-1", "workspace.read_file"),
            new ModelStreamEvent.ToolCallDelta("call-1", "{\"path\":\"pom.xml\"}"),
            new ModelStreamEvent.UsageReported(120, 16),
            new ModelStreamEvent.Completed("tool_calls", Map.of()));
    FakeModelClient client = FakeModelClient.respondingWith(script);
    RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE, false);

    client.stream(request()).subscribe(subscriber);

    assertThat(subscriber.events).containsExactlyElementsOf(script);
    assertThat(subscriber.completed).isTrue();
    assertThat(subscriber.error).isNull();
    assertThat(client.requests()).containsExactly(request());
  }

  @Test
  void stopsPublishingAndRecordsCancellation() {
    FakeModelClient client =
        FakeModelClient.respondingWith(
            List.of(
                new ModelStreamEvent.TextDelta("first"),
                new ModelStreamEvent.TextDelta("second"),
                new ModelStreamEvent.Completed("stop", Map.of())));
    RecordingSubscriber subscriber = new RecordingSubscriber(1, true);

    client.stream(request()).subscribe(subscriber);

    assertThat(subscriber.events).containsExactly(new ModelStreamEvent.TextDelta("first"));
    assertThat(subscriber.completed).isFalse();
    assertThat(client.cancellationCount()).isEqualTo(1);
  }

  @Test
  void servesSuccessiveScriptsIncludingAProviderFailure() {
    ModelStreamEvent.Failed failure = new ModelStreamEvent.Failed("rate_limit", "try later", true);
    FakeModelClient client =
        FakeModelClient.scripted(
            List.of(
                List.of(failure),
                List.of(
                    new ModelStreamEvent.TextDelta("recovered"),
                    new ModelStreamEvent.Completed("stop", Map.of()))));
    RecordingSubscriber first = new RecordingSubscriber(Long.MAX_VALUE, false);
    RecordingSubscriber second = new RecordingSubscriber(Long.MAX_VALUE, false);

    client.stream(request()).subscribe(first);
    client.stream(request()).subscribe(second);

    assertThat(first.events).containsExactly(failure);
    assertThat(second.events)
        .containsExactly(
            new ModelStreamEvent.TextDelta("recovered"),
            new ModelStreamEvent.Completed("stop", Map.of()));
    assertThat(first.completed).isTrue();
    assertThat(second.completed).isTrue();
  }

  private static ModelRequest request() {
    return new ModelRequest("test", "fake", List.of(), List.of(), true, 1024, Map.of());
  }

  private static final class RecordingSubscriber implements Flow.Subscriber<ModelStreamEvent> {

    private final long initialDemand;
    private final boolean cancelAfterFirst;
    private final List<ModelStreamEvent> events = new ArrayList<>();
    private Flow.Subscription subscription;
    private Throwable error;
    private boolean completed;

    private RecordingSubscriber(long initialDemand, boolean cancelAfterFirst) {
      this.initialDemand = initialDemand;
      this.cancelAfterFirst = cancelAfterFirst;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(initialDemand);
    }

    @Override
    public void onNext(ModelStreamEvent item) {
      events.add(item);
      if (cancelAfterFirst) {
        subscription.cancel();
      }
    }

    @Override
    public void onError(Throwable throwable) {
      error = throwable;
    }

    @Override
    public void onComplete() {
      completed = true;
    }
  }
}
