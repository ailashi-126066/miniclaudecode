package dev.miniclaudecode.persistence.memory;

import java.util.Optional;

public final class MemoryWriteGate {
  private MemoryWriteGate() {}

  public static Optional<MemoryCandidate> accept(MemoryCandidate candidate) {
    if (candidate.durability() != MemoryDurability.DURABLE
        || candidate.authority() == MemoryAuthority.ASSISTANT_INFERENCE) {
      return Optional.empty();
    }
    if (candidate.authority() != MemoryAuthority.USER_STATED
        && candidate.authority() != MemoryAuthority.VERIFIED_RESULT) {
      return Optional.empty();
    }
    if (candidate.type() == MemoryType.VERIFIED_LESSON
        && (candidate.authority() != MemoryAuthority.VERIFIED_RESULT
            || candidate.evidence().stream().allMatch(String::isBlank))) {
      return Optional.empty();
    }
    return Optional.of(candidate);
  }
}
