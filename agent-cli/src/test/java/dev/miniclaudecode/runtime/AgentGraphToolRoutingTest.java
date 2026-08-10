package dev.miniclaudecode.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.providers.FakeModelClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentGraphToolRoutingTest {

  @Test
  void routesToolCallsThroughExecutionAndBackToTheModel() {
    ToolCall call = new ToolCall("call-1", "workspace.read_file", "{\"path\":\"pom.xml\"}");
    FakeModelClient model =
        FakeModelClient.scripted(
            List.of(
                List.of(
                    new ModelStreamEvent.ToolCallCompleted(call),
                    new ModelStreamEvent.Completed("tool_calls", Map.of())),
                List.of(
                    new ModelStreamEvent.TextDelta("The POM is valid."),
                    new ModelStreamEvent.Completed("stop", Map.of()))));
    AtomicReference<List<ToolCall>> executed = new AtomicReference<>();
    ToolExecutor executor =
        calls -> {
          executed.set(calls);
          return CompletableFuture.completedFuture(
              List.of(
                  new ToolResult(
                      "call-1",
                      ToolResult.Status.COMPLETED,
                      "pom content",
                      Optional.empty(),
                      Map.of())));
        };
    AgentGraphFactory graph = new AgentGraphFactory(model, executor, new TurnLimits(4, 8));

    var state = graph.run(request());

    assertThat(state.status()).isEqualTo(AgentStatus.COMPLETED);
    assertThat(state.finalText()).isEqualTo("The POM is valid.");
    assertThat(state.trace())
        .containsExactly("prepare_context", "call_model", "execute_tools", "call_model", "finish");
    assertThat(executed.get()).containsExactly(call);
    assertThat(state.messages()).anyMatch(AgentMessage.ToolMessage.class::isInstance);
    assertThat(model.requests()).hasSize(2);
  }

  private static ModelRequest request() {
    return new ModelRequest(
        "test",
        "fake",
        List.of(new AgentMessage.UserMessage("inspect pom.xml")),
        List.of(),
        true,
        1024,
        Map.of());
  }
}
