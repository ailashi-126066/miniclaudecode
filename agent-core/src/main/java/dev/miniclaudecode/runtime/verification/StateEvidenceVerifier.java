package dev.miniclaudecode.runtime.verification;

import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.runtime.CompletionRequirements;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.List;

/** Deterministic verification over observed state; model self-claims are never evidence. */
public final class StateEvidenceVerifier implements Verifier {
  @Override
  public VerificationResult verify(MiniClaudeState state, VerificationScope scope) {
    if (state.status() == AgentStatus.FAILED || state.error().isPresent()) {
      return new VerificationResult(
          VerificationOutcome.FAIL, state.error().orElse("task failed"), evidence(state));
    }
    if (state.finalText().isBlank()) {
      return new VerificationResult(
          VerificationOutcome.RETRY, "task produced no final result", evidence(state));
    }
    if (CompletionRequirements.requiresVerification(state)) {
      VerificationOutcome outcome =
          scope == VerificationScope.PLAN_STEP
              ? VerificationOutcome.REPLAN
              : VerificationOutcome.RETRY;
      return new VerificationResult(
          outcome, "workspace mutation has no successful verification command", evidence(state));
    }
    return VerificationResult.pass(evidence(state));
  }

  private static List<String> evidence(MiniClaudeState state) {
    return state.toolResults().stream()
        .filter(
            result ->
                result.status()
                    != dev.miniclaudecode.domain.tool.ToolResult.Status.APPROVAL_REQUIRED)
        .map(result -> result.status() + ": " + result.summary())
        .limit(12)
        .toList();
  }
}
