package dev.miniclaudecode.cli;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jline.reader.LineReader;

public final class ApprovalMenu {

  private final LineReader reader;
  private final Clock clock;

  public ApprovalMenu(LineReader reader, Clock clock) {
    this.reader = Objects.requireNonNull(reader, "reader must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public ApprovalDecision prompt(ApprovalRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    if ("user:ask".equals(request.toolCall().qualifiedName())) {
      return promptForAnswer(request);
    }
    reader.getTerminal().writer().printf("%nApproval required [%s]%n", request.riskLevel());
    reader
        .getTerminal()
        .writer()
        .printf("Target: %s%nReason: %s%n", request.target(), request.reason());
    reader
        .getTerminal()
        .writer()
        .println(
            "1) Allow once  2) Allow this turn  3) Allow this file  4) Always allow  5) Reject");
    reader.getTerminal().flush();
    while (true) {
      String selection = reader.readLine("Select [1-5]: ");
      try {
        return decide(request, selection, Instant.now(clock));
      } catch (IllegalArgumentException error) {
        reader.getTerminal().writer().println(error.getMessage());
        reader.getTerminal().flush();
      }
    }
  }

  public ApprovalDecision decide(ApprovalRequest request, String selection, Instant decidedAt) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(decidedAt, "decidedAt must not be null");
    String normalized = selection == null ? "" : selection.trim();
    return switch (normalized) {
      case "1" -> allow(request, ApprovalDecision.Scope.ONCE, decidedAt);
      case "2" -> allow(request, ApprovalDecision.Scope.TURN, decidedAt);
      case "3" -> allow(request, ApprovalDecision.Scope.FILE, decidedAt);
      case "4" -> allow(request, ApprovalDecision.Scope.PERMANENT, decidedAt);
      case "5" ->
          new ApprovalDecision(
              request.approvalId(),
              ApprovalDecision.Choice.REJECT,
              ApprovalDecision.Scope.ONCE,
              Optional.empty(),
              decidedAt);
      default -> throw new IllegalArgumentException("Choose a number from 1 to 5");
    };
  }

  private static ApprovalDecision allow(
      ApprovalRequest request, ApprovalDecision.Scope scope, Instant decidedAt) {
    return new ApprovalDecision(
        request.approvalId(), ApprovalDecision.Choice.ALLOW, scope, Optional.empty(), decidedAt);
  }

  private ApprovalDecision promptForAnswer(ApprovalRequest request) {
    reader.getTerminal().writer().printf("%nQuestion: %s%n", request.target());
    reader.getTerminal().flush();
    String answer = reader.readLine("Answer (blank to decline): ");
    String normalized = answer == null ? "" : answer.trim();
    if (normalized.isEmpty()) {
      return new ApprovalDecision(
          request.approvalId(),
          ApprovalDecision.Choice.REJECT,
          ApprovalDecision.Scope.ONCE,
          Optional.empty(),
          Instant.now(clock));
    }
    return new ApprovalDecision(
        request.approvalId(),
        ApprovalDecision.Choice.ALLOW,
        ApprovalDecision.Scope.ONCE,
        Optional.of(normalized),
        Instant.now(clock));
  }
}
