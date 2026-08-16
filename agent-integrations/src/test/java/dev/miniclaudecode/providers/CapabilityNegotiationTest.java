package dev.miniclaudecode.providers;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CapabilityNegotiationTest {
  @Test
  void disablesThinkingWhenTheProviderDoesNotSupportIt() {
    TestStreamingChatModel model =
        new TestStreamingChatModel(
            handler ->
                handler.onCompleteResponse(
                    ChatResponse.builder()
                        .aiMessage(AiMessage.from("done"))
                        .finishReason(FinishReason.STOP)
                        .build()));
    LangChainStreamingModelClient client =
        new LangChainStreamingModelClient(model, ThinkingSupport.UNSUPPORTED, Optional.empty()) {};
    ModelRequest request =
        new ModelRequest(
            "test", "model", List.of(new UserMessage("hello")), List.of(), true, 128, Map.of());
    FlowTestSubscriber<ModelStreamEvent> subscriber = new FlowTestSubscriber<>();

    client.stream(request).subscribe(subscriber);

    assertThat(model.callCount()).isOne();
    assertThat(subscriber.events())
        .containsExactly(new ModelStreamEvent.Completed("stop", Map.of()));
    assertThat(subscriber.error()).isNull();
    assertThat(subscriber.completed()).isTrue();
  }
}
