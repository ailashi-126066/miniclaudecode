package dev.miniclaudecode.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.providers.FakeModelClient;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class AgentGraphOutputRepairTest {

  @Test
  void feedsMalformedJsonBackToModelBeforeCompleting() {
    FakeModelClient model =
        FakeModelClient.scripted(
            List.of(
                response("{\"status\":\"done\"}"),
                response("{\"status\":\"completed\",\"final\":\"Tests passed\"}")));
    AgentGraphFactory graph =
        new AgentGraphFactory(
            model, calls -> CompletableFuture.completedFuture(List.of()), new TurnLimits(4, 4));

    MiniClaudeState state =
        graph.run(
            new ModelRequest(
                "deepseek",
                "deepseek-chat",
                List.of(new AgentMessage.UserMessage("run tests")),
                List.of(),
                false,
                512,
                Map.of("outputProtocol", "json", "maxOutputRepairs", 1)));

    assertThat(state.status()).isEqualTo(AgentStatus.COMPLETED);
    assertThat(state.finalText()).isEqualTo("Tests passed");
    assertThat(state.outputRepairCount()).isEqualTo(1);
    assertThat(state.trace())
        .containsSubsequence("call_model", "repair_output", "call_model", "finish");
    assertThat(model.requests()).hasSize(2);
    assertThat(model.requests().get(1).messages().getLast())
        .isInstanceOfSatisfying(
            AgentMessage.UserMessage.class,
            message -> assertThat(message.text()).contains("\"status\":\"completed\""));
  }

  private static List<ModelStreamEvent> response(String text) {
    return List.of(
        new ModelStreamEvent.TextDelta(text), new ModelStreamEvent.Completed("stop", Map.of()));
  }
}
