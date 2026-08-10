package dev.miniclaudecode.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.persistence.checkpoint.FileCheckpointSaver;
import dev.miniclaudecode.providers.FakeModelClient;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApprovalResumeGraphTest {

  @TempDir Path temporaryDirectory;

  @Test
  void restoresAnInterruptedApprovalAndExecutesTheEditExactlyOnce() throws IOException {
    Path checkpoints = temporaryDirectory.resolve("checkpoints");
    Path editedFile = temporaryDirectory.resolve("App.java");
    ToolCall call = new ToolCall("edit-1", "workspace.edit", "{\"path\":\"App.java\"}");
    ApprovalRequest approval =
        new ApprovalRequest(
            UUID.fromString("13cb477b-a853-49a5-8b8a-62644c1a46ba"),
            call,
            RiskLevel.MEDIUM,
            "App.java",
            "Apply the displayed diff",
            Optional.of("before-hash"),
            Optional.of("diff-hash"),
            Instant.parse("2026-07-21T00:00:00Z"));
    AtomicInteger sideEffects = new AtomicInteger();
    SessionId sessionId = SessionId.of("approval-session");

    AgentThreadRunner firstProcess =
        runner(
            FakeModelClient.respondingWith(
                List.of(
                    new ModelStreamEvent.ToolCallCompleted(call),
                    new ModelStreamEvent.Completed("tool_calls", Map.of()))),
            approvalAwareExecutor(approval, editedFile, sideEffects),
            checkpoints);

    MiniClaudeState paused = firstProcess.start(sessionId, request());

    assertThat(paused.status()).isEqualTo(AgentStatus.WAITING_APPROVAL);
    assertThat(paused.pendingApproval()).contains(approval);
    assertThat(editedFile).doesNotExist();

    AgentThreadRunner restoredProcess =
        runner(
            FakeModelClient.respondingWith(
                List.of(
                    new ModelStreamEvent.TextDelta("Edit applied."),
                    new ModelStreamEvent.Completed("stop", Map.of()))),
            approvalAwareExecutor(approval, editedFile, sideEffects),
            checkpoints);
    ApprovalDecision decision =
        new ApprovalDecision(
            approval.approvalId(),
            ApprovalDecision.Choice.ALLOW,
            ApprovalDecision.Scope.ONCE,
            Optional.empty(),
            Instant.parse("2026-07-21T00:01:00Z"));

    MiniClaudeState completed = restoredProcess.resume(sessionId, decision);

    assertThat(completed.status()).isEqualTo(AgentStatus.COMPLETED);
    assertThat(completed.finalText()).isEqualTo("Edit applied.");
    assertThat(Files.readString(editedFile)).isEqualTo("class App {}\n");
    assertThat(sideEffects).hasValue(1);
  }

  private static AgentThreadRunner runner(
      FakeModelClient model, ToolExecutor executor, Path checkpoints) {
    FileCheckpointSaver<MiniClaudeState> saver =
        new FileCheckpointSaver<>(checkpoints, MiniClaudeState::new);
    return new AgentThreadRunner(
        new AgentGraphFactory(model, executor, new TurnLimits(4, 8), saver));
  }

  private static ToolExecutor approvalAwareExecutor(
      ApprovalRequest approval, Path editedFile, AtomicInteger sideEffects) {
    return new ToolExecutor() {
      @Override
      public CompletionStage<List<ToolResult>> execute(List<ToolCall> calls) {
        return execute(calls, Optional.empty(), Optional.empty());
      }

      @Override
      public CompletionStage<List<ToolResult>> execute(
          List<ToolCall> calls,
          Optional<ApprovalRequest> pendingApproval,
          Optional<ApprovalDecision> decision) {
        if (decision.isEmpty()) {
          return CompletableFuture.completedFuture(
              List.of(
                  new ToolResult(
                      calls.getFirst().toolCallId(),
                      ToolResult.Status.APPROVAL_REQUIRED,
                      "Approval required",
                      Optional.empty(),
                      Map.of("approvalRequest", approval))));
        }
        if (!decision.orElseThrow().approvalId().equals(approval.approvalId())
            || decision.orElseThrow().choice() != ApprovalDecision.Choice.ALLOW
            || pendingApproval.filter(approval::equals).isEmpty()) {
          return CompletableFuture.failedFuture(new IllegalArgumentException("invalid approval"));
        }
        try {
          sideEffects.incrementAndGet();
          Files.writeString(editedFile, "class App {}\n");
          return CompletableFuture.completedFuture(
              List.of(
                  new ToolResult(
                      calls.getFirst().toolCallId(),
                      ToolResult.Status.COMPLETED,
                      "App.java updated",
                      Optional.empty(),
                      Map.of())));
        } catch (IOException error) {
          return CompletableFuture.failedFuture(error);
        }
      }
    };
  }

  private static ModelRequest request() {
    return new ModelRequest(
        "test",
        "fake",
        List.of(new AgentMessage.UserMessage("Edit App.java")),
        List.of(),
        true,
        1024,
        Map.of());
  }
}
