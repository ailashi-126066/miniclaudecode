package dev.miniclaudecode.cli.app;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.persistence.config.ProviderProfile;
import dev.miniclaudecode.providers.ProviderFactory;
import dev.miniclaudecode.providers.ProviderSpec;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class RoutingModelClientTest {

  @Test
  void rebuildsTheProviderClientWhenThinkingOrModelChanges() {
    ProviderFactory factory = Mockito.mock(ProviderFactory.class);
    ModelClient client =
        ignored ->
            subscriber ->
                subscriber.onSubscribe(
                    new Flow.Subscription() {
                      @Override
                      public void request(long n) {
                        subscriber.onNext(new ModelStreamEvent.Completed("stop", Map.of()));
                        subscriber.onComplete();
                      }

                      @Override
                      public void cancel() {}
                    });
    when(factory.create(any())).thenReturn(client);
    RoutingModelClient routing =
        new RoutingModelClient(Map.of("profile", profile()), Map.of("API_KEY", "secret"), factory);

    subscribe(routing.stream(request("model-a", false)));
    subscribe(routing.stream(request("model-a", false)));
    subscribe(routing.stream(request("model-a", true)));
    subscribe(routing.stream(request("model-b", true)));

    ArgumentCaptor<ProviderSpec> specs = ArgumentCaptor.forClass(ProviderSpec.class);
    verify(factory, times(3)).create(specs.capture());
    Assertions.assertThat(specs.getAllValues())
        .extracting(ProviderSpec::model, ProviderSpec::thinking)
        .containsExactly(
            Assertions.tuple("model-a", false),
            Assertions.tuple("model-a", true),
            Assertions.tuple("model-b", true));
  }

  private static void subscribe(Flow.Publisher<ModelStreamEvent> publisher) {
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(ModelStreamEvent item) {}

          @Override
          public void onError(Throwable throwable) {
            throw new AssertionError(throwable);
          }

          @Override
          public void onComplete() {}
        });
  }

  private static ModelRequest request(String model, boolean thinking) {
    return new ModelRequest(
        "profile", model, List.of(new UserMessage("hello")), List.of(), thinking, 4096, Map.of());
  }

  private static ProviderProfile profile() {
    return new ProviderProfile(
        ProviderProfile.Type.OPENAI_COMPATIBLE,
        Optional.of(URI.create("https://example.test/v1")),
        Optional.empty(),
        Optional.of("API_KEY"),
        "configured-model",
        0.2,
        8192,
        false,
        Duration.ofSeconds(30),
        3);
  }
}
