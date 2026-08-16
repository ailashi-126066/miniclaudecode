package dev.miniclaudecode.cli.tui;

import dev.miniclaudecode.domain.session.AgentStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Pure state transition function for the TUI. */
public final class TuiReducer {
  public TuiState reduce(TuiState state, TuiEvent event) {
    return switch (event) {
      case TuiEvent.Resize resize -> copy(state, resize.width(), resize.height());
      case TuiEvent.InputChanged input -> withInput(state, input.value());
      case TuiEvent.PromptSubmitted submitted -> submit(state, submitted.prompt());
      case TuiEvent.TextDelta text -> withStream(state, state.streamText() + text.text());
      case TuiEvent.ThinkingDelta thinking ->
          withThinking(state, state.thinking() + thinking.text());
      case TuiEvent.Progress progress -> withStatus(state, progress.text());
      case TuiEvent.DashboardUpdated updated -> withDashboard(state, updated.dashboard());
      case TuiEvent.CommandResult result -> append(state, result.text(), "Ready");
      case TuiEvent.Failure failure -> append(state, "Error: " + failure.text(), "Failed");
      case TuiEvent.TurnFinished finished -> finish(state, finished.outcome());
      case TuiEvent.ApprovalStarted ignored ->
          new TuiState(
              state.width(),
              state.height(),
              state.transcript(),
              "",
              state.streamText(),
              state.thinking(),
              "Applying approval decision...",
              state.dashboard(),
              true,
              Optional.empty(),
              Optional.empty());
    };
  }

  private static TuiState submit(TuiState state, String prompt) {
    List<String> transcript = new ArrayList<>(state.transcript());
    transcript.add("You: " + prompt);
    return new TuiState(
        state.width(),
        state.height(),
        transcript,
        "",
        "",
        "",
        "Agent is working...",
        state.dashboard(),
        true,
        Optional.empty(),
        Optional.empty());
  }

  private static TuiState finish(TuiState state, dev.miniclaudecode.cli.TurnOutcome outcome) {
    List<String> transcript = new ArrayList<>(state.transcript());
    if (!state.thinking().isBlank()) {
      transcript.add("Thinking: " + state.thinking());
    }
    if (!state.streamText().isBlank()) {
      transcript.add("Assistant: " + state.streamText());
    }
    boolean waiting = outcome.status() == AgentStatus.WAITING_APPROVAL;
    String status = waiting ? "Approval required: y allow / n reject" : outcome.status().name();
    return new TuiState(
        state.width(),
        state.height(),
        transcript,
        "",
        "",
        "",
        status,
        state.dashboard(),
        false,
        outcome.approvalRequest(),
        outcome.approvalPreview());
  }

  private static TuiState append(TuiState state, String text, String status) {
    List<String> transcript = new ArrayList<>(state.transcript());
    transcript.add(text);
    return new TuiState(
        state.width(),
        state.height(),
        transcript,
        state.input(),
        state.streamText(),
        state.thinking(),
        status,
        state.dashboard(),
        state.running(),
        state.approval(),
        state.approvalPreview());
  }

  private static TuiState withInput(TuiState state, String input) {
    return new TuiState(
        state.width(),
        state.height(),
        state.transcript(),
        input,
        state.streamText(),
        state.thinking(),
        state.status(),
        state.dashboard(),
        state.running(),
        state.approval(),
        state.approvalPreview());
  }

  private static TuiState withStream(TuiState state, String stream) {
    return new TuiState(
        state.width(),
        state.height(),
        state.transcript(),
        state.input(),
        stream,
        state.thinking(),
        state.status(),
        state.dashboard(),
        state.running(),
        state.approval(),
        state.approvalPreview());
  }

  private static TuiState withThinking(TuiState state, String thinking) {
    return new TuiState(
        state.width(),
        state.height(),
        state.transcript(),
        state.input(),
        state.streamText(),
        thinking,
        state.status(),
        state.dashboard(),
        state.running(),
        state.approval(),
        state.approvalPreview());
  }

  private static TuiState withStatus(TuiState state, String status) {
    return new TuiState(
        state.width(),
        state.height(),
        state.transcript(),
        state.input(),
        state.streamText(),
        state.thinking(),
        status,
        state.dashboard(),
        state.running(),
        state.approval(),
        state.approvalPreview());
  }

  private static TuiState copy(TuiState state, int width, int height) {
    return new TuiState(
        width,
        height,
        state.transcript(),
        state.input(),
        state.streamText(),
        state.thinking(),
        state.status(),
        state.dashboard(),
        state.running(),
        state.approval(),
        state.approvalPreview());
  }

  private static TuiState withDashboard(TuiState state, TuiDashboard dashboard) {
    return new TuiState(
        state.width(),
        state.height(),
        state.transcript(),
        state.input(),
        state.streamText(),
        state.thinking(),
        state.status(),
        dashboard,
        state.running(),
        state.approval(),
        state.approvalPreview());
  }
}
