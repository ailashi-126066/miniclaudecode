package dev.miniclaudecode.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.persistence.checkpoint.FileCheckpointSaver;
import dev.miniclaudecode.persistence.config.SecretRedactor;
import dev.miniclaudecode.persistence.event.JsonlEventStore;
import dev.miniclaudecode.providers.FakeModelClient;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompleteCodingAgentScenarioTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-21T08:00:00Z"), ZoneOffset.UTC);

  @TempDir Path temporaryDirectory;

  @Test
  void completesSearchReadApprovedEditTestAndFinalAnswerAcrossAProcessRestart() throws IOException {
    Path workspace = temporaryDirectory.resolve("workspace");
    Path source = workspace.resolve("src/App.java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "class App { int answer() { return 0; } }\n");

    SessionId sessionId = SessionId.of("complete-coding-scenario");
    TurnId turnId = TurnId.of(1);
    JsonlEventStore events =
        new JsonlEventStore(temporaryDirectory.resolve("events"), new SecretRedactor(), Set.of());
    emit(events, sessionId, turnId, AgentEventType.USER_MESSAGE, "Fix App.answer and test it");

    ToolCall search = call("search-1", "rag.code_search", "{\"query\":\"App answer\"}");
    ToolCall read = call("read-1", "workspace.read", "{\"path\":\"src/App.java\"}");
    ToolCall edit = call("edit-1", "workspace.edit", "{\"path\":\"src/App.java\"}");
    ToolCall test = call("test-1", "process.run", "{\"command\":\"mvn test\"}");
    ApprovalRequest approval =
        new ApprovalRequest(
            UUID.fromString("aabbccdd-1122-3344-5566-778899001122"),
            edit,
            RiskLevel.MEDIUM,
            "src/App.java",
            "Apply the displayed diff",
            Optional.of("before-hash"),
            Optional.of("diff-hash"),
            CLOCK.instant());
    ScenarioToolExecutor tools =
        new ScenarioToolExecutor(source, approval, events, sessionId, turnId);
    Path checkpoints = temporaryDirectory.resolve("checkpoints");

    AgentThreadRunner firstProcess =
        runner(
            audited(
                FakeModelClient.scripted(
                    List.of(toolResponse(search), toolResponse(read), toolResponse(edit))),
                events,
                sessionId,
                turnId),
            tools,
            checkpoints);

    MiniClaudeState paused = firstProcess.start(sessionId, request());

    assertThat(paused.status()).isEqualTo(AgentStatus.WAITING_APPROVAL);
    assertThat(paused.pendingApproval()).contains(approval);
    assertThat(Files.readString(source)).contains("return 0");
    assertThat(tools.editSideEffects()).isZero();

    ApprovalDecision decision =
        new ApprovalDecision(
            approval.approvalId(),
            ApprovalDecision.Choice.ALLOW,
            ApprovalDecision.Scope.ONCE,
            Optional.of("approved in CLI"),
            CLOCK.instant().plusSeconds(30));
    emit(events, sessionId, turnId, AgentEventType.APPROVAL_RESOLVED, "allow once");
    AgentThreadRunner restartedProcess =
        runner(
            audited(
                FakeModelClient.scripted(
                    List.of(
                        toolResponse(test),
                        List.of(
                            new ModelStreamEvent.ThinkingDelta(
                                "The targeted tests pass; summarize the exact change."),
                            new ModelStreamEvent.TextDelta(
                                "Updated App.answer() to return 42; tests pass."),
                            new ModelStreamEvent.Completed("stop", Map.of("model", "fake"))))),
                events,
                sessionId,
                turnId),
            tools,
            checkpoints);

    MiniClaudeState completed = restartedProcess.resume(sessionId, decision);
    emit(events, sessionId, turnId, AgentEventType.TURN_FINAL, completed.finalText());

    assertThat(completed.status()).isEqualTo(AgentStatus.COMPLETED);
    assertThat(completed.finalText()).isEqualTo("Updated App.answer() to return 42; tests pass.");
    assertThat(completed.thinking())
        .contains("The targeted tests pass; summarize the exact change.");
    assertThat(completed.thinking().orElseThrow()).doesNotContain(completed.finalText());
    assertThat(Files.readString(source)).contains("return 42").doesNotContain("return 0");
    assertThat(tools.editSideEffects()).isEqualTo(1);
    assertThat(tools.distinctToolSequence())
        .containsExactly("rag.code_search", "workspace.read", "workspace.edit", "process.run");
    assertThat(completed.trace())
        .containsExactly(
            "prepare_context",
            "call_model",
            "execute_tools",
            "call_model",
            "execute_tools",
            "call_model",
            "execute_tools",
            "await_approval",
            "execute_tools",
            "call_model",
            "execute_tools",
            "call_model",
            "finish");

    assertThat(events.read(sessionId).warnings()).isEmpty();
    assertThat(events.read(sessionId).events())
        .extracting(AgentEvent::type)
        .containsExactly(
            AgentEventType.USER_MESSAGE,
            AgentEventType.TOOL_STARTED,
            AgentEventType.TOOL_RESULT,
            AgentEventType.TOOL_STARTED,
            AgentEventType.TOOL_RESULT,
            AgentEventType.TOOL_STARTED,
            AgentEventType.APPROVAL_REQUESTED,
            AgentEventType.APPROVAL_RESOLVED,
            AgentEventType.TOOL_RESULT,
            AgentEventType.TOOL_STARTED,
            AgentEventType.TOOL_RESULT,
            AgentEventType.PROVIDER_THINKING,
            AgentEventType.ASSISTANT_MESSAGE,
            AgentEventType.TURN_FINAL);
  }

  private static AgentThreadRunner runner(
      ModelClient model, ToolExecutor executor, Path checkpoints) {
    FileCheckpointSaver<MiniClaudeState> saver =
        new FileCheckpointSaver<>(checkpoints, MiniClaudeState::new);
    return new AgentThreadRunner(
        new AgentGraphFactory(model, executor, new TurnLimits(8, 12), saver));
  }

  private static ModelClient audited(
      ModelClient delegate, JsonlEventStore events, SessionId sessionId, TurnId turnId) {
    return request ->
        subscriber ->
            delegate.stream(request)
                .subscribe(new AuditSubscriber(subscriber, events, sessionId, turnId));
  }

  private static ModelRequest request() {
    return new ModelRequest(
        "test",
        "fake",
        List.of(new AgentMessage.UserMessage("Fix App.answer and run the targeted tests")),
        List.of(),
        true,
        1024,
        Map.of());
  }

  private static List<ModelStreamEvent> toolResponse(ToolCall call) {
    return List.of(
        new ModelStreamEvent.ToolCallCompleted(call),
        new ModelStreamEvent.Completed("tool_calls", Map.of()));
  }

  private static ToolCall call(String id, String name, String arguments) {
    return new ToolCall(id, name, arguments);
  }

  private static void emit(
      JsonlEventStore events,
      SessionId sessionId,
      TurnId turnId,
      AgentEventType type,
      String text) {
    events.append(AgentEvent.create(sessionId, turnId, type, Map.of("text", text), CLOCK));
  }

  private static final class AuditSubscriber implements Flow.Subscriber<ModelStreamEvent> {

    private final Flow.Subscriber<? super ModelStreamEvent> downstream;
    private final JsonlEventStore events;
    private final SessionId sessionId;
    private final TurnId turnId;

    private AuditSubscriber(
        Flow.Subscriber<? super ModelStreamEvent> downstream,
        JsonlEventStore events,
        SessionId sessionId,
        TurnId turnId) {
      this.downstream = downstream;
      this.events = events;
      this.sessionId = sessionId;
      this.turnId = turnId;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      downstream.onSubscribe(subscription);
    }

    @Override
    public void onNext(ModelStreamEvent item) {
      if (item instanceof ModelStreamEvent.ThinkingDelta thinking) {
        emit(events, sessionId, turnId, AgentEventType.PROVIDER_THINKING, thinking.text());
      } else if (item instanceof ModelStreamEvent.TextDelta text) {
        emit(events, sessionId, turnId, AgentEventType.ASSISTANT_MESSAGE, text.text());
      }
      downstream.onNext(item);
    }

    @Override
    public void onError(Throwable throwable) {
      downstream.onError(throwable);
    }

    @Override
    public void onComplete() {
      downstream.onComplete();
    }
  }

  private static final class ScenarioToolExecutor implements ToolExecutor {

    private final Path source;
    private final ApprovalRequest approval;
    private final JsonlEventStore events;
    private final SessionId sessionId;
    private final TurnId turnId;
    private final AtomicInteger editSideEffects = new AtomicInteger();
    private final Set<String> seenCallIds = new LinkedHashSet<>();
    private final List<String> sequence = new ArrayList<>();

    private ScenarioToolExecutor(
        Path source,
        ApprovalRequest approval,
        JsonlEventStore events,
        SessionId sessionId,
        TurnId turnId) {
      this.source = source;
      this.approval = approval;
      this.events = events;
      this.sessionId = sessionId;
      this.turnId = turnId;
    }

    @Override
    public CompletionStage<List<ToolResult>> execute(List<ToolCall> calls) {
      return execute(calls, Optional.empty(), Optional.empty());
    }

    @Override
    public CompletionStage<List<ToolResult>> execute(
        List<ToolCall> calls,
        Optional<ApprovalRequest> pendingApproval,
        Optional<ApprovalDecision> decision) {
      ToolCall call = calls.getFirst();
      if (seenCallIds.add(call.toolCallId())) {
        sequence.add(call.qualifiedName());
        emit(events, sessionId, turnId, AgentEventType.TOOL_STARTED, call.qualifiedName());
      }
      if (call.equals(approval.toolCall()) && decision.isEmpty()) {
        emit(events, sessionId, turnId, AgentEventType.APPROVAL_REQUESTED, approval.target());
        return CompletableFuture.completedFuture(
            List.of(
                new ToolResult(
                    call.toolCallId(),
                    ToolResult.Status.APPROVAL_REQUIRED,
                    "Approval required for displayed diff",
                    Optional.empty(),
                    Map.of("approvalRequest", approval))));
      }
      if (call.equals(approval.toolCall())) {
        if (pendingApproval.filter(approval::equals).isEmpty()
            || decision
                .filter(value -> value.choice() == ApprovalDecision.Choice.ALLOW)
                .isEmpty()) {
          return CompletableFuture.failedFuture(new IllegalArgumentException("invalid approval"));
        }
        try {
          String before = Files.readString(source);
          Files.writeString(source, before.replace("return 0", "return 42"));
          editSideEffects.incrementAndGet();
        } catch (IOException error) {
          return CompletableFuture.failedFuture(error);
        }
      }
      String summary =
          switch (call.qualifiedName()) {
            case "rag.code_search" -> "src/App.java:1 contains answer()";
            case "workspace.read" -> "class App { int answer() { return 0; } }";
            case "workspace.edit" -> "src/App.java updated";
            case "process.run" -> "Tests run: 1, Failures: 0";
            default -> "completed";
          };
      emit(events, sessionId, turnId, AgentEventType.TOOL_RESULT, summary);
      return CompletableFuture.completedFuture(
          List.of(
              new ToolResult(
                  call.toolCallId(),
                  ToolResult.Status.COMPLETED,
                  summary,
                  Optional.empty(),
                  Map.of())));
    }

    int editSideEffects() {
      return editSideEffects.get();
    }

    List<String> distinctToolSequence() {
      return List.copyOf(sequence);
    }
  }
}
