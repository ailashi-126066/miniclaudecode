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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AgentGraphLimitTest {

  @Test
  void terminatesWhenTheModelStepLimitIsReached() {
    FakeModelClient model = FakeModelClient.respondingWith(toolResponse(oneCall()));
    ToolExecutor executor = calls -> CompletableFuture.completedFuture(success(calls.getFirst()));
    AgentGraphFactory graph = new AgentGraphFactory(model, executor, new TurnLimits(1, 4));

    var state = graph.run(request());

    assertThat(state.status()).isEqualTo(AgentStatus.FAILED);
    assertThat(state.error())
        .hasValueSatisfying(error -> assertThat(error).contains("model step limit"));
    assertThat(state.trace())
        .containsExactly("prepare_context", "call_model", "execute_tools", "call_model", "finish");
    assertThat(model.requests()).hasSize(1);
  }

  @Test
  void refusesAToolBatchThatWouldExceedTheToolStepLimit() {
    ToolCall first = oneCall();
    ToolCall second = new ToolCall("call-2", "workspace.grep", "{\"query\":\"TODO\"}");
    FakeModelClient model = FakeModelClient.respondingWith(toolResponse(first, second));
    AtomicBoolean executed = new AtomicBoolean();
    ToolExecutor executor =
        calls -> {
          executed.set(true);
          return CompletableFuture.completedFuture(List.of());
        };
    AgentGraphFactory graph = new AgentGraphFactory(model, executor, new TurnLimits(4, 1));

    var state = graph.run(request());

    assertThat(state.status()).isEqualTo(AgentStatus.FAILED);
    assertThat(state.error())
        .hasValueSatisfying(error -> assertThat(error).contains("tool step limit"));
    assertThat(executed).isFalse();
    assertThat(state.trace())
        .containsExactly("prepare_context", "call_model", "execute_tools", "finish");
  }

  private static List<ModelStreamEvent> toolResponse(ToolCall... calls) {
    var events = new java.util.ArrayList<ModelStreamEvent>();
    for (ToolCall call : calls) {
      events.add(new ModelStreamEvent.ToolCallCompleted(call));
    }
    events.add(new ModelStreamEvent.Completed("tool_calls", Map.of()));
    return List.copyOf(events);
  }

  private static ToolCall oneCall() {
    return new ToolCall("call-1", "workspace.read_file", "{\"path\":\"pom.xml\"}");
  }

  private static List<ToolResult> success(ToolCall call) {
    return List.of(
        new ToolResult(
            call.toolCallId(), ToolResult.Status.COMPLETED, "ok", Optional.empty(), Map.of()));
  }

  private static ModelRequest request() {
    return new ModelRequest(
        "test",
        "fake",
        List.of(new AgentMessage.UserMessage("continue until done")),
        List.of(),
        false,
        1024,
        Map.of());
  }
}
