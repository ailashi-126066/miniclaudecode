package dev.miniclaudecode.providers.openai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage.InputTokensDetails;
import dev.langchain4j.model.output.FinishReason;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.model.ModelStreamEvent.Completed;
import dev.miniclaudecode.domain.model.ModelStreamEvent.Failed;
import dev.miniclaudecode.domain.model.ModelStreamEvent.UsageReported;
import dev.miniclaudecode.providers.FlowTestSubscriber;
import dev.miniclaudecode.providers.ProviderSpec;
import dev.miniclaudecode.providers.ProviderSpec.Type;
import dev.miniclaudecode.providers.TestStreamingChatModel;
import dev.miniclaudecode.providers.ThinkingSupport;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleModelClientTest {
  @Test
  void reportsOpenAiCompatibleCachedInputTokens() {
    TestStreamingChatModel model =
        new TestStreamingChatModel(
            handler ->
                handler.onCompleteResponse(
                    ChatResponse.builder()
                        .aiMessage(AiMessage.from("done"))
                        .tokenUsage(
                            OpenAiTokenUsage.builder()
                                .inputTokenCount(20)
                                .outputTokenCount(4)
                                .totalTokenCount(24)
                                .inputTokensDetails(
                                    InputTokensDetails.builder().cachedTokens(12).build())
                                .build())
                        .finishReason(FinishReason.STOP)
                        .build()));
    OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(model, spec());
    FlowTestSubscriber<ModelStreamEvent> subscriber = new FlowTestSubscriber();
    client.stream(request()).subscribe(subscriber);
    Assertions.assertThat(subscriber.events())
        .containsExactly(
            new ModelStreamEvent[] {
              new UsageReported(20L, 4L, 12L, 0L), new Completed("stop", Map.of())
            });
  }

  @Test
  void classifiesRetryableRateLimitAndRedactsConfiguredKey() {
    TestStreamingChatModel model =
        new TestStreamingChatModel(
            handler -> handler.onError(new RuntimeException("HTTP 429 Bearer sk-sensitive")));
    OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(model, spec());
    FlowTestSubscriber<ModelStreamEvent> subscriber = new FlowTestSubscriber();
    client.stream(request()).subscribe(subscriber);
    Assertions.assertThat(client.thinkingSupport()).isEqualTo(ThinkingSupport.BEST_EFFORT);
    Assertions.assertThat(subscriber.completed()).isTrue();
    Assertions.assertThat(subscriber.events())
        .containsExactly(
            new ModelStreamEvent[] {new Failed("rate_limited", "HTTP 429 Bearer ***", true)});
  }

  private static ProviderSpec spec() {
    return new ProviderSpec(
        Type.OPENAI_COMPATIBLE,
        Optional.of(URI.create("https://gateway.example/v1")),
        Optional.of("sk-sensitive"),
        "deepseek-chat",
        0.3,
        2048,
        true,
        Duration.ofSeconds(20L),
        3);
  }

  private static ModelRequest request() {
    return new ModelRequest(
        "gateway",
        "deepseek-chat",
        List.of(new UserMessage("hello")),
        List.of(),
        true,
        512,
        Map.of());
  }
}
