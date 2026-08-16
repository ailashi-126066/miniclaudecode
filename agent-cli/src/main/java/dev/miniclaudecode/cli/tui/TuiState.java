package dev.miniclaudecode.cli.tui;

import dev.miniclaudecode.domain.approval.ApprovalRequest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable state rendered by the full-screen terminal UI. */
public record TuiState(
    int width,
    int height,
    List<String> transcript,
    String input,
    String streamText,
    String thinking,
    String status,
    TuiDashboard dashboard,
    boolean running,
    Optional<ApprovalRequest> approval,
    Optional<String> approvalPreview) {
  public TuiState {
    width = Math.max(40, width);
    height = Math.max(12, height);
    transcript = List.copyOf(Objects.requireNonNull(transcript, "transcript must not be null"));
    input = Objects.requireNonNull(input, "input must not be null");
    streamText = Objects.requireNonNull(streamText, "streamText must not be null");
    thinking = Objects.requireNonNull(thinking, "thinking must not be null");
    status = Objects.requireNonNull(status, "status must not be null");
    dashboard = Objects.requireNonNull(dashboard, "dashboard must not be null");
    approval = Objects.requireNonNull(approval, "approval must not be null");
    approvalPreview = Objects.requireNonNull(approvalPreview, "approvalPreview must not be null");
  }

  public static TuiState initial() {
    return new TuiState(
        80,
        24,
        List.of("MiniClaudeCode ready. Type /help for commands."),
        "",
        "",
        "",
        "Ready",
        TuiDashboard.empty(),
        false,
        Optional.empty(),
        Optional.empty());
  }
}
