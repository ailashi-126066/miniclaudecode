package dev.miniclaudecode.runtime;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolEffect;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentLoopTest {
  @Test
  void completesAStreamingResponseWithThinkingAndUsage() {
    ModelClient model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelStreamEvent.ThinkingDelta("reasoning"),
                    new ModelStreamEvent.TextDelta("answer"),
                    new ModelStreamEvent.UsageReported(10, 2, 0, 0),
                    new ModelStreamEvent.Completed("stop", Map.of("requestId", "r-1")))));
    AgentLoop loop = new AgentLoop(model, noTools(), new TurnLimits(4, 4));

    MiniClaudeState result = loop.run(request("explain this", List.of()));

    Assertions.assertThat(result.status()).isEqualTo(AgentStatus.COMPLETED);
    Assertions.assertThat(result.finalText()).isEqualTo("answer");
    Assertions.assertThat(result.thinking()).contains("reasoning");
    Assertions.assertThat(result.providerMetadata())
        .containsEntry("inputTokens", 10L)
        .containsEntry("finishReason", "stop");
    Assertions.assertThat(result.modelSteps()).isEqualTo(1);
    Assertions.assertThat(result.trace()).contains("call_model", "finish");
  }

  @Test
  void executesToolsAndWritesResultsBackBeforeTheNextModelCall() {
    ToolCall call = new ToolCall("call-1", "workspace:read", "{\"path\":\"README.md\"}");
    AtomicInteger modelCalls = new AtomicInteger();
    ModelClient model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelStreamEvent.ToolCallCompleted(call),
                    new ModelStreamEvent.Completed("tool_use", Map.of())),
                List.of(
                    new ModelStreamEvent.TextDelta("read complete"),
                    new ModelStreamEvent.Completed("stop", Map.of()))),
            modelCalls);
    ToolExecutor tools =
        calls ->
            CompletableFuture.completedFuture(
                List.of(
                    new ToolResult(
                        calls.getFirst().toolCallId(),
                        ToolResult.Status.COMPLETED,
                        "file contents",
                        Optional.empty(),
                        Map.of())));
    AgentLoop loop = new AgentLoop(model, tools, new TurnLimits(4, 4));

    MiniClaudeState result = loop.run(request("inspect the file", List.of(readDescriptor())));

    Assertions.assertThat(result.status()).isEqualTo(AgentStatus.COMPLETED);
    Assertions.assertThat(result.finalText()).isEqualTo("read complete");
    Assertions.assertThat(result.toolSteps()).isEqualTo(1);
    Assertions.assertThat(result.messages())
        .anyMatch(
            message ->
                message instanceof dev.miniclaudecode.domain.message.AgentMessage.ToolMessage);
    Assertions.assertThat(modelCalls).hasValue(2);
  }

  @Test
  void resumesAnApprovalPausedToolBatch() {
    ToolCall call = new ToolCall("call-approval", "external:change", "{\"target\":\"demo\"}");
    UUID approvalId = UUID.randomUUID();
    ApprovalRequest approval =
        new ApprovalRequest(
            approvalId,
            call,
            RiskLevel.HIGH,
            "A.java",
            "write file",
            Optional.empty(),
            Optional.empty(),
            Instant.parse("2026-08-16T00:00:00Z"));
    ModelClient model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelStreamEvent.ToolCallCompleted(call),
                    new ModelStreamEvent.Completed("tool_use", Map.of())),
                List.of(
                    new ModelStreamEvent.TextDelta("written"),
                    new ModelStreamEvent.Completed("stop", Map.of()))));
    ToolExecutor tools = new ApprovalToolExecutor(approval);
    AgentLoop loop = new AgentLoop(model, tools, new TurnLimits(4, 4));

    MiniClaudeState paused = loop.run(request("use the prepared tool", List.of(writeDescriptor())));

    Assertions.assertThat(paused.status()).isEqualTo(AgentStatus.WAITING_APPROVAL);
    Assertions.assertThat(paused.pendingApproval()).contains(approval);
    ApprovalDecision decision =
        new ApprovalDecision(
            approvalId,
            ApprovalDecision.Choice.ALLOW,
            ApprovalDecision.Scope.ONCE,
            Optional.empty(),
            Instant.parse("2026-08-16T00:00:01Z"));

    MiniClaudeState completed = loop.resume(paused, decision);

    Assertions.assertThat(completed.status())
        .withFailMessage("error=%s trace=%s", completed.error(), completed.trace())
        .isEqualTo(AgentStatus.COMPLETED);
    Assertions.assertThat(completed.finalText()).isEqualTo("written");
    Assertions.assertThat(completed.toolSteps()).isEqualTo(1);
  }

  @Test
  void continuesAfterMaxTokensAndCombinesTheText() {
    AtomicInteger calls = new AtomicInteger();
    ModelClient model =
        scriptedModel(
            List.of(
                List.of(
                    new ModelStreamEvent.TextDelta("part one "),
                    new ModelStreamEvent.Completed("max_tokens", Map.of())),
                List.of(
                    new ModelStreamEvent.TextDelta("part two"),
                    new ModelStreamEvent.Completed("stop", Map.of()))),
            calls);
    AgentLoop loop = new AgentLoop(model, noTools(), new TurnLimits(4, 4));

    MiniClaudeState result = loop.run(request("long answer", List.of()));

    Assertions.assertThat(result.status()).isEqualTo(AgentStatus.COMPLETED);
    Assertions.assertThat(result.finalText()).isEqualTo("part one part two");
    Assertions.assertThat(result.continuationCount()).isEqualTo(1);
    Assertions.assertThat(result.messages())
        .anyMatch(
            message ->
                message instanceof dev.miniclaudecode.domain.message.AgentMessage.SystemMessage
                    && message.text().startsWith("Continue exactly"));
    Assertions.assertThat(calls).hasValue(2);
  }

  @Test
  void injectsSelectedDeferredSchemaIntoTheNextModelRequest() {
    ToolDescriptor search =
        new ToolDescriptor(
            "system",
            "tool_search",
            "find tools",
            "{\"type\":\"object\"}",
            RiskLevel.LOW,
            ToolEffect.READ_ONLY_LOCAL);
    ToolDescriptor discovered =
        writeDescriptor().withExposure(dev.miniclaudecode.domain.tool.ToolExposure.DEFERRED);
    AtomicInteger modelCalls = new AtomicInteger();
    ModelClient model =
        request -> {
          int call = modelCalls.getAndIncrement();
          if (call == 0) {
            Assertions.assertThat(request.tools())
                .extracting(ToolDescriptor::qualifiedName)
                .containsExactly("system:tool_search");
            return publisher(
                List.of(
                    new ModelStreamEvent.ToolCallCompleted(
                        new ToolCall(
                            "discover-1",
                            "system:tool_search",
                            "{\"query\":\"change\",\"select\":[\"external:change\"]}")),
                    new ModelStreamEvent.Completed("tool_use", Map.of())));
          }
          Assertions.assertThat(request.tools())
              .extracting(ToolDescriptor::qualifiedName)
              .containsExactly("system:tool_search", "external:change");
          return publisher(
              List.of(
                  new ModelStreamEvent.TextDelta("schema discovered"),
                  new ModelStreamEvent.Completed("stop", Map.of())));
        };
    ToolExecutor tools =
        calls ->
            CompletableFuture.completedFuture(
                List.of(
                    new ToolResult(
                        calls.getFirst().toolCallId(),
                        ToolResult.Status.COMPLETED,
                        "selected external:change",
                        Optional.empty(),
                        Map.of(
                            "discoveredTools",
                            List.of("external:change"),
                            "discoveredToolDescriptors",
                            List.of(discovered)))));

    MiniClaudeState result =
        new AgentLoop(model, tools, new TurnLimits(4, 4))
            .run(request("find and use a tool", List.of(search)));

    Assertions.assertThat(result.status()).isEqualTo(AgentStatus.COMPLETED);
    Assertions.assertThat(result.discoveredTools()).containsExactly("external:change");
    Assertions.assertThat(result.request().tools())
        .extracting(ToolDescriptor::qualifiedName)
        .containsExactly("system:tool_search", "external:change");
    Assertions.assertThat(modelCalls).hasValue(2);
  }

  private static ModelRequest request(String prompt, List<ToolDescriptor> tools) {
    return new ModelRequest(
        "test",
        "test-model",
        List.of(new UserMessage(prompt)),
        tools,
        true,
        1024,
        Map.of(
            "planningEnabled",
            false,
            "requireVerification",
            false,
            "maxRetries",
            0,
            "maxCompactions",
            0));
  }

  private static ToolDescriptor readDescriptor() {
    return new ToolDescriptor(
        "workspace",
        "read",
        "read a file",
        "{\"type\":\"object\"}",
        RiskLevel.LOW,
        ToolEffect.READ_ONLY_LOCAL);
  }

  private static ToolDescriptor writeDescriptor() {
    return new ToolDescriptor(
        "external",
        "change",
        "change an external resource",
        "{\"type\":\"object\"}",
        RiskLevel.HIGH,
        ToolEffect.MUTATION);
  }

  private static ToolExecutor noTools() {
    return calls -> CompletableFuture.failedFuture(new AssertionError("tools must not be called"));
  }

  private static ModelClient scriptedModel(List<List<ModelStreamEvent>> responses) {
    return scriptedModel(responses, new AtomicInteger());
  }

  private static ModelClient scriptedModel(
      List<List<ModelStreamEvent>> responses, AtomicInteger calls) {
    return request -> {
      int index = calls.getAndIncrement();
      if (index >= responses.size()) {
        throw new AssertionError("unexpected model call " + index);
      }
      return publisher(responses.get(index));
    };
  }

  private static Publisher<ModelStreamEvent> publisher(List<ModelStreamEvent> events) {
    return subscriber -> subscriber.onSubscribe(new EventSubscription(subscriber, events));
  }

  private static final class EventSubscription implements Subscription {
    private final Subscriber<? super ModelStreamEvent> subscriber;
    private final List<ModelStreamEvent> events;
    private final AtomicBoolean emitted = new AtomicBoolean();

    private EventSubscription(
        Subscriber<? super ModelStreamEvent> subscriber, List<ModelStreamEvent> events) {
      this.subscriber = subscriber;
      this.events = events;
    }

    @Override
    public void request(long count) {
      if (count > 0 && this.emitted.compareAndSet(false, true)) {
        this.events.forEach(this.subscriber::onNext);
        this.subscriber.onComplete();
      }
    }

    @Override
    public void cancel() {
      this.emitted.set(true);
    }
  }

  private static final class ApprovalToolExecutor implements ToolExecutor {
    private final ApprovalRequest approval;

    private ApprovalToolExecutor(ApprovalRequest approval) {
      this.approval = approval;
    }

    @Override
    public java.util.concurrent.CompletionStage<List<ToolResult>> execute(List<ToolCall> calls) {
      return execute(calls, Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Override
    public java.util.concurrent.CompletionStage<List<ToolResult>> execute(
        List<ToolCall> calls,
        Optional<ApprovalRequest> pendingApproval,
        Optional<ApprovalDecision> approvalDecision,
        Optional<PlanExecutionContext> planContext) {
      ToolResult result =
          approvalDecision.isPresent()
              ? new ToolResult(
                  calls.getFirst().toolCallId(),
                  ToolResult.Status.COMPLETED,
                  "write complete",
                  Optional.empty(),
                  Map.of())
              : new ToolResult(
                  calls.getFirst().toolCallId(),
                  ToolResult.Status.APPROVAL_REQUIRED,
                  "approval required",
                  Optional.empty(),
                  Map.of("approvalRequest", this.approval));
      return CompletableFuture.completedFuture(List.of(result));
    }
  }
}
