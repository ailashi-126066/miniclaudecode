package dev.miniclaudecode.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.providers.FakeModelClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class AgentGraphRecoveryTest {

  @Test
  void compactsAndRetriesOnceAfterAContextOverflow() {
    FakeModelClient model =
        FakeModelClient.scripted(
            List.of(
                List.of(
                    new ModelStreamEvent.Failed(
                        "context_length_exceeded", "prompt is too long", false)),
                List.of(
                    new ModelStreamEvent.TextDelta("Recovered."),
                    new ModelStreamEvent.Completed("stop", Map.of()))));
    AgentGraphFactory graph = new AgentGraphFactory(model, noTools(), new TurnLimits(4, 4));

    var state = graph.run(request());

    assertThat(state.status()).isEqualTo(AgentStatus.COMPLETED);
    assertThat(state.finalText()).isEqualTo("Recovered.");
    assertThat(state.trace()).containsSubsequence("call_model", "compact_context", "call_model");
  }

  @Test
  void retriesAProviderMarkedTransientFailure() {
    FakeModelClient model =
        FakeModelClient.scripted(
            List.of(
                List.of(new ModelStreamEvent.Failed("http_503", "unavailable", true)),
                List.of(
                    new ModelStreamEvent.TextDelta("Retried."),
                    new ModelStreamEvent.Completed("stop", Map.of()))));
    AgentGraphFactory graph = new AgentGraphFactory(model, noTools(), new TurnLimits(4, 4));

    var state = graph.run(request());

    assertThat(state.status()).isEqualTo(AgentStatus.COMPLETED);
    assertThat(state.trace()).containsSubsequence("call_model", "recover_error", "call_model");
    assertThat(model.requests()).hasSize(2);
  }

  @Test
  void perRequestConfigurationCanDisableRetries() {
    FakeModelClient model =
        FakeModelClient.scripted(
            List.of(
                List.of(new ModelStreamEvent.Failed("http_503", "unavailable", true)),
                List.of(
                    new ModelStreamEvent.TextDelta("must not run"),
                    new ModelStreamEvent.Completed("stop", Map.of()))));
    AgentGraphFactory graph = new AgentGraphFactory(model, noTools(), new TurnLimits(4, 4));

    var state = graph.run(request(Map.of("contextWindowTokens", 4096, "maxRetries", 0)));

    assertThat(state.status()).isEqualTo(AgentStatus.FAILED);
    assertThat(model.requests()).hasSize(1);
  }

  private static ToolExecutor noTools() {
    return calls -> CompletableFuture.completedFuture(List.of());
  }

  private static ModelRequest request() {
    return request(Map.of("contextWindowTokens", 4096));
  }

  private static ModelRequest request(Map<String, Object> attributes) {
    return new ModelRequest(
        "test",
        "fake",
        List.of(new AgentMessage.UserMessage("help")),
        List.of(),
        false,
        256,
        attributes);
  }
}
