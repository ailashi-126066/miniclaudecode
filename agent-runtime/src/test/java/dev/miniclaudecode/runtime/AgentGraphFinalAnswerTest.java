package dev.miniclaudecode.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.providers.FakeModelClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentGraphFinalAnswerTest {

  @Test
  void executesTheCompiledGraphToAFinalAnswer() {
    FakeModelClient model =
        FakeModelClient.respondingWith(
            List.of(
                new ModelStreamEvent.ThinkingDelta("check the request"),
                new ModelStreamEvent.TextDelta("Done."),
                new ModelStreamEvent.Completed("stop", Map.of("provider", "fake"))));
    AgentGraphFactory graph =
        new AgentGraphFactory(model, calls -> failIfToolsRun(), new TurnLimits(4, 8));

    var state = graph.run(request());

    assertThat(state.status()).isEqualTo(AgentStatus.COMPLETED);
    assertThat(state.finalText()).isEqualTo("Done.");
    assertThat(state.thinking()).contains("check the request");
    assertThat(state.trace()).containsExactly("prepare_context", "call_model", "finish");
    assertThat(model.requests()).hasSize(1);
  }

  private static ModelRequest request() {
    return new ModelRequest(
        "test",
        "fake",
        List.of(new AgentMessage.UserMessage("fix the build")),
        List.of(),
        true,
        1024,
        Map.of());
  }

  private static <T> T failIfToolsRun() {
    throw new AssertionError("tool executor must not run on final answer path");
  }
}
