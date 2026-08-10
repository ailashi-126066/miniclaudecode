package dev.miniclaudecode.persistence.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record MemoryRecord(
    String id,
    MemoryCandidate candidate,
    MemoryState state,
    Instant createdAt,
    Instant updatedAt,
    int occurrenceCount,
    Optional<Instant> lastUsedAt,
    Optional<Instant> consolidatedAt,
    Optional<String> supersededBy) {
  public MemoryRecord {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    candidate = Objects.requireNonNull(candidate, "candidate must not be null");
    state = Objects.requireNonNull(state, "state must not be null");
    createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    if (occurrenceCount < 1) {
      throw new IllegalArgumentException("occurrenceCount must be positive");
    }
    lastUsedAt = Objects.requireNonNull(lastUsedAt, "lastUsedAt must not be null");
    consolidatedAt = Objects.requireNonNull(consolidatedAt, "consolidatedAt must not be null");
    supersededBy = Objects.requireNonNull(supersededBy, "supersededBy must not be null");
  }
}
