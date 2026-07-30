package dev.miniclaudecode.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.providers.FakeModelClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Small deterministic regression harness for normal-turn semantics. Each case uses a scripted model
 * and real runtime loop, then asserts the artifact, trace, and measured turn boundaries.
 */
class AgentRegressionHarnessTest {

  @Test
  void toolRoundTripReportsAStableExecutionProfile() {
    ToolCall read = new ToolCall("read-1", "workspace:read", "{\"path\":\"README.md\"}");
    FakeModelClient model =
        FakeModelClient.scripted(
            List.of(
                toolResponse(read),
                List.of(
                    new ModelStreamEvent.TextDelta("README checked."),
                    new ModelStreamEvent.Completed("stop", Map.of()))));
    List<TurnProgressListener.Progress> progress = new ArrayList<>();

    HarnessResult result =
        run(
            model,
            calls -> completed(calls.getFirst(), "project readme"),
            request(Map.of()),
            progress);

    assertThat(result.status()).isEqualTo(AgentStatus.COMPLETED);
    assertThat(result.finalText()).isEqualTo("README checked.");
    assertThat(result.modelCalls()).isEqualTo(2);
    assertThat(result.toolCalls()).isEqualTo(1);
    assertThat(result.compactions()).isZero();
    assertThat(progress)
        .extracting(TurnProgressListener.Progress::phase)
        .containsSubsequence("before_model", "after_model", "before_tools", "after_tools");
  }

  @Test
  void largeToolOutputCompactsBeforeTheNextModelCallAndReportsTheReason() {
    ToolCall read = new ToolCall("read-1", "workspace:read", "{\"path\":\"large.log\"}");
    FakeModelClient model =
        FakeModelClient.scripted(
            List.of(
                toolResponse(read),
                List.of(
                    new ModelStreamEvent.TextDelta("Large output was compacted."),
                    new ModelStreamEvent.Completed("stop", Map.of()))));
    List<TurnProgressListener.Progress> progress = new ArrayList<>();

    HarnessResult result =
        run(
            model,
            // A repeated single character is highly token-compressible. Use token-like text so
            // this regression asserts the real tokenizer threshold rather than the old /4 proxy.
            calls -> completed(calls.getFirst(), "x ".repeat(20_000)),
            request(Map.of("contextWindowTokens", 4096, "maxCompactions", 3)),
            progress);

    TurnProgressListener.Progress compaction =
        progress.stream()
            .filter(TurnProgressListener.Progress::compaction)
            .findFirst()
            .orElseThrow();
    assertThat(result.status()).isEqualTo(AgentStatus.COMPLETED);
    assertThat(result.compactions()).isEqualTo(1);
    assertThat(compaction.compactionReason()).isEqualTo("preflight_threshold");
    assertThat(compaction.estimatedInputTokens()).isLessThan(compaction.beforeCompactionTokens());
    assertThat(result.modelCalls()).isEqualTo(2);
  }

  private static HarnessResult run(
      FakeModelClient model,
      ToolExecutor executor,
      ModelRequest request,
      List<TurnProgressListener.Progress> progress) {
    var state =
        new AgentGraphFactory(model, executor, new TurnLimits(6, 6), null, null, progress::add)
            .run(request);
    return new HarnessResult(
        state.status(),
        state.finalText(),
        state.modelSteps(),
        state.toolSteps(),
        state.compactionCount());
  }

  private static CompletableFuture<List<ToolResult>> completed(ToolCall call, String summary) {
    return CompletableFuture.completedFuture(
        List.of(
            new ToolResult(
                call.toolCallId(),
                ToolResult.Status.COMPLETED,
                summary,
                Optional.empty(),
                Map.of())));
  }

  private static List<ModelStreamEvent> toolResponse(ToolCall call) {
    return List.of(
        new ModelStreamEvent.ToolCallCompleted(call),
        new ModelStreamEvent.Completed("tool_calls", Map.of()));
  }

  private static ModelRequest request(Map<String, Object> attributes) {
    return new ModelRequest(
        "test",
        "fake",
        List.of(new AgentMessage.UserMessage("inspect the workspace")),
        List.of(),
        false,
        256,
        attributes);
  }

  private record HarnessResult(
      AgentStatus status, String finalText, int modelCalls, int toolCalls, int compactions) {}
}
