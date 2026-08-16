package dev.miniclaudecode.cli.tui;

import dev.miniclaudecode.cli.TurnOutcome;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.ToolCall;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class TuiReducerTest {
  private final TuiReducer reducer = new TuiReducer();

  @Test
  void reducesStreamingTurnWithoutOwningBackendState() {
    TuiState state = reducer.reduce(TuiState.initial(), new TuiEvent.Resize(120, 40));
    state = reducer.reduce(state, new TuiEvent.InputChanged("hello"));
    state = reducer.reduce(state, new TuiEvent.PromptSubmitted("hello"));
    state = reducer.reduce(state, new TuiEvent.ThinkingDelta("checking"));
    state = reducer.reduce(state, new TuiEvent.TextDelta("answer"));
    state = reducer.reduce(state, new TuiEvent.TurnFinished(TurnOutcome.completed()));

    Assertions.assertThat(state.width()).isEqualTo(120);
    Assertions.assertThat(state.running()).isFalse();
    Assertions.assertThat(state.input()).isEmpty();
    Assertions.assertThat(state.transcript())
        .contains("You: hello", "Thinking: checking", "Assistant: answer");
    Assertions.assertThat(new TuiView().render(state, "C:\\work"))
        .contains("MiniClaudeCode", "C:\\work", "> ");
  }

  @Test
  void rendersOperationalDashboardWithoutAddingItToTranscript() {
    TuiDashboard dashboard =
        new TuiDashboard(
            "session-7 / executing",
            "ACTIVE v2 - fix build",
            "3 req, 120 in, 40 out",
            "bg-1 RUNNING",
            "team-1 ACTIVE");
    TuiState state = reducer.reduce(TuiState.initial(), new TuiEvent.DashboardUpdated(dashboard));

    Assertions.assertThat(state.transcript()).hasSize(1);
    Assertions.assertThat(new TuiView().render(state, "workspace"))
        .contains(
            "Session: session-7 / executing",
            "Plan: ACTIVE v2 - fix build",
            "Usage: 3 req, 120 in, 40 out",
            "Background: bg-1 RUNNING",
            "Team: team-1 ACTIVE");
  }

  @Test
  void exposesApprovalAsADedicatedDialogState() {
    ApprovalRequest request =
        new ApprovalRequest(
            UUID.randomUUID(),
            new ToolCall("call-1", "workspace:write", "{}"),
            RiskLevel.HIGH,
            "A.java",
            "write",
            Optional.empty(),
            Optional.empty(),
            Instant.parse("2026-08-16T00:00:00Z"));
    TuiState state = reducer.reduce(TuiState.initial(), new TuiEvent.PromptSubmitted("change it"));
    state =
        reducer.reduce(
            state, new TuiEvent.TurnFinished(TurnOutcome.waitingFor(request, "-old\n+new")));

    Assertions.assertThat(state.approval()).contains(request);
    Assertions.assertThat(state.approvalPreview()).contains("-old\n+new");
    Assertions.assertThat(new TuiView().render(state, "workspace"))
        .contains("Approve A.java?", "[y] allow", "Proposed change:");
  }
}
