package dev.miniclaudecode.runtime.verification;

import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.List;
import java.util.Objects;

public final class VerificationPipeline implements Verifier {
  private final List<Verifier> verifiers;

  public VerificationPipeline(List<Verifier> verifiers) {
    this.verifiers = List.copyOf(Objects.requireNonNull(verifiers, "verifiers must not be null"));
  }

  @Override
  public VerificationResult verify(MiniClaudeState state, VerificationScope scope) {
    VerificationResult aggregate = VerificationResult.pass(List.of());
    for (Verifier verifier : verifiers) {
      VerificationResult result = verifier.verify(state, scope);
      if (result.outcome() != VerificationOutcome.PASS) {
        return result;
      }
      aggregate =
          VerificationResult.pass(
              java.util.stream.Stream.concat(
                      aggregate.evidence().stream(), result.evidence().stream())
                  .distinct()
                  .toList());
    }
    return aggregate;
  }
}
