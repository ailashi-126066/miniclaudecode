package dev.miniclaudecode.runtime.verification;

import dev.miniclaudecode.runtime.state.MiniClaudeState;

@FunctionalInterface
public interface Verifier {
  VerificationResult verify(MiniClaudeState state, VerificationScope scope);
}
