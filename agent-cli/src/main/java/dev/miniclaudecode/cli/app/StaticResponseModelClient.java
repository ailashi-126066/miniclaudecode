package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.model.ModelStreamEvent.Completed;
import dev.miniclaudecode.domain.model.ModelStreamEvent.TextDelta;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscription;

final class StaticResponseModelClient implements ModelClient {
  private final String response;

  StaticResponseModelClient(String response) {
    this.response = Objects.requireNonNull(response, "response must not be null");
  }

  public Publisher<ModelStreamEvent> stream(ModelRequest request) {
    return subscriber ->
        subscriber.onSubscribe(
            new Subscription() {
              private boolean completed;

              @Override
              public void request(long count) {
                if (!this.completed) {
                  if (count < 1L) {
                    this.completed = true;
                    subscriber.onError(new IllegalArgumentException("demand must be positive"));
                  } else {
                    this.completed = true;
                    if (!StaticResponseModelClient.this.response.isEmpty()) {
                      subscriber.onNext(new TextDelta(StaticResponseModelClient.this.response));
                    }

                    subscriber.onNext(new Completed("stop", Map.of("fake", true)));
                    subscriber.onComplete();
                  }
                }
              }

              @Override
              public void cancel() {
                this.completed = true;
              }
            });
  }
}
