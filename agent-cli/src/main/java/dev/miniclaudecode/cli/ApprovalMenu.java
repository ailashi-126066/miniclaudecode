package dev.miniclaudecode.cli;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    reader.getTerminal().writer().println(optionsLine(request));
    reader.getTerminal().flush();
    while (true) {
      String selection = reader.readLine("Select: ");
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
    Set<ApprovalDecision.Scope> supported = supportedScopes(request);
    return switch (normalized) {
      case "1" -> allow(request, ApprovalDecision.Scope.ONCE, decidedAt);
      case "2" -> allowIfSupported(request, ApprovalDecision.Scope.TURN, supported, decidedAt);
      case "3" -> allowIfSupported(request, ApprovalDecision.Scope.FILE, supported, decidedAt);
      case "4" -> allowIfSupported(request, ApprovalDecision.Scope.PERMANENT, supported, decidedAt);
      case "5" ->
          new ApprovalDecision(
              request.approvalId(),
              ApprovalDecision.Choice.REJECT,
              ApprovalDecision.Scope.ONCE,
              Optional.empty(),
              decidedAt);
      default -> throw new IllegalArgumentException("Choose one of the listed numbers");
    };
  }

  /**
   * Scopes each tool family actually consumes. The menu used to offer all five options for every
   * approval, but only file-mutation tools honor them all: RunCommandTool consumes once/turn/
   * permanent, and web/MCP approvals are once-only — so "Allow this file" on a shell command
   * silently degraded to allow-once, and "Always allow" on an MCP tool re-prompted on the next
   * call. Offering only what will be honored keeps the menu honest.
   */
  static Set<ApprovalDecision.Scope> supportedScopes(ApprovalRequest request) {
    String tool = request.toolCall().qualifiedName();
    if (tool.startsWith("workspace:")) {
      return Set.of(
          ApprovalDecision.Scope.ONCE,
          ApprovalDecision.Scope.TURN,
          ApprovalDecision.Scope.FILE,
          ApprovalDecision.Scope.PERMANENT);
    }
    if (tool.startsWith("shell:")) {
      return Set.of(
          ApprovalDecision.Scope.ONCE,
          ApprovalDecision.Scope.TURN,
          ApprovalDecision.Scope.PERMANENT);
    }
    return Set.of(ApprovalDecision.Scope.ONCE);
  }

  static String optionsLine(ApprovalRequest request) {
    Set<ApprovalDecision.Scope> supported = supportedScopes(request);
    StringBuilder line = new StringBuilder("1) Allow once");
    if (supported.contains(ApprovalDecision.Scope.TURN)) {
      line.append("  2) Allow this turn");
    }
    if (supported.contains(ApprovalDecision.Scope.FILE)) {
      line.append("  3) Allow this file");
    }
    if (supported.contains(ApprovalDecision.Scope.PERMANENT)) {
      line.append("  4) Always allow");
    }
    return line.append("  5) Reject").toString();
  }

  private static ApprovalDecision allowIfSupported(
      ApprovalRequest request,
      ApprovalDecision.Scope scope,
      Set<ApprovalDecision.Scope> supported,
      Instant decidedAt) {
    if (!supported.contains(scope)) {
      throw new IllegalArgumentException(
          "That scope is not available for " + request.toolCall().qualifiedName());
    }
    return allow(request, scope, decidedAt);
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
