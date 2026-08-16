package dev.miniclaudecode.cli;

import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.session.AgentStatus;
import java.util.Objects;
import java.util.Optional;

public record TurnOutcome(
    AgentStatus status,
    Optional<ApprovalRequest> approvalRequest,
    Optional<String> approvalPreview) {
  public TurnOutcome {
    status = Objects.requireNonNull(status, "status must not be null");
    approvalRequest = Objects.requireNonNull(approvalRequest, "approvalRequest must not be null");
    approvalPreview =
        Objects.requireNonNull(approvalPreview, "approvalPreview must not be null")
            .map(String::strip)
            .filter(value -> !value.isEmpty());
  }

  public static TurnOutcome completed() {
    return finished(AgentStatus.COMPLETED);
  }

  public static TurnOutcome failed() {
    return finished(AgentStatus.FAILED);
  }

  public static TurnOutcome finished(AgentStatus status) {
    return new TurnOutcome(status, Optional.empty(), Optional.empty());
  }

  public static TurnOutcome waitingFor(ApprovalRequest request) {
    return waitingFor(request, null);
  }

  public static TurnOutcome waitingFor(ApprovalRequest request, String preview) {
    return new TurnOutcome(
        AgentStatus.WAITING_APPROVAL, Optional.of(request), Optional.ofNullable(preview));
  }
}
