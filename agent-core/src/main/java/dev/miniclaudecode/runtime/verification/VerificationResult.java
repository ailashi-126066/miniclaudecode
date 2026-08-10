package dev.miniclaudecode.runtime.verification;

import java.util.List;
import java.util.Objects;

public record VerificationResult(
    VerificationOutcome outcome, String reason, List<String> evidence) {
  public VerificationResult {
    outcome = Objects.requireNonNull(outcome, "outcome must not be null");
    reason = Objects.requireNonNullElse(reason, "").strip();
    evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
  }

  public static VerificationResult pass(List<String> evidence) {
    return new VerificationResult(VerificationOutcome.PASS, "", evidence);
  }
}
