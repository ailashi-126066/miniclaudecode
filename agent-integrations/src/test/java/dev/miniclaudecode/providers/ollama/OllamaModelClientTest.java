package dev.miniclaudecode.providers.ollama;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.output.FinishReason;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.model.ModelStreamEvent.Completed;
import dev.miniclaudecode.domain.model.ModelStreamEvent.TextDelta;
import dev.miniclaudecode.providers.FlowTestSubscriber;
import dev.miniclaudecode.providers.ProviderSpec;
import dev.miniclaudecode.providers.ProviderSpec.Type;
import dev.miniclaudecode.providers.TestStreamingChatModel;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class OllamaModelClientTest {
  @Test
  void suppressesThinkingWhenTurnDisablesIt() {
    TestStreamingChatModel model =
        new TestStreamingChatModel(
            handler -> {
              handler.onPartialThinking(new PartialThinking("private summary"));
              handler.onPartialResponse("answer");
              handler.onCompleteResponse(
                  ChatResponse.builder()
                      .aiMessage(AiMessage.from("answer"))
                      .finishReason(FinishReason.STOP)
                      .build());
            });
    OllamaModelClient client = new OllamaModelClient(model, spec());
    FlowTestSubscriber<ModelStreamEvent> subscriber = new FlowTestSubscriber();
    client.stream(request(false)).subscribe(subscriber);
    Assertions.assertThat(subscriber.events())
        .containsExactly(
            new ModelStreamEvent[] {new TextDelta("answer"), new Completed("stop", Map.of())});
  }

  @Test
  void cancellationBeforeInvocationDoesNotCallProvider() {
    TestStreamingChatModel model = new TestStreamingChatModel(handler -> {});
    OllamaModelClient client = new OllamaModelClient(model, spec());
    client.stream(request(true)).subscribe(new FlowTestSubscriber(true));
    Assertions.assertThat(model.callCount()).isZero();
  }

  private static ProviderSpec spec() {
    return new ProviderSpec(
        Type.OLLAMA,
        Optional.of(URI.create("http://localhost:11434")),
        Optional.empty(),
        "qwen3:8b",
        0.2,
        2048,
        true,
        Duration.ofSeconds(60L),
        0);
  }

  private static ModelRequest request(boolean thinking) {
    return new ModelRequest(
        "local",
        "qwen3:8b",
        List.of(new UserMessage("hello")),
        List.of(),
        thinking,
        1024,
        Map.of());
  }
}
