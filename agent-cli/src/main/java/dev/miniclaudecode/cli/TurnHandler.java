package dev.miniclaudecode.cli;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** UI-independent interface for starting and resuming an Agent turn. */
public interface TurnHandler {
  CompletionStage<TurnOutcome> start(
      String prompt, CancellationToken cancellationToken, Consumer<TurnEvent> events);

  CompletionStage<TurnOutcome> resume(
      ApprovalDecision decision, CancellationToken cancellationToken, Consumer<TurnEvent> events);

  default Optional<ApprovalRequest> pendingApproval() {
    return Optional.empty();
  }

  default Optional<String> pendingApprovalPreview() {
    return Optional.empty();
  }
}
