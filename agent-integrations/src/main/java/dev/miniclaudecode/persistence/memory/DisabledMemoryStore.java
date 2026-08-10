package dev.miniclaudecode.persistence.memory;

import java.util.List;
import java.util.Optional;

/** No-op degradation used when the optional local memory database cannot be opened safely. */
public final class DisabledMemoryStore implements MemoryStore {
  private final String warning;

  public DisabledMemoryStore(String warning) {
    this.warning =
        warning == null || warning.isBlank() ? "long-term memory is unavailable" : warning;
  }

  @Override
  public AceBullet curate(AceBullet candidate) {
    return candidate;
  }

  @Override
  public Optional<MemoryRecord> remember(MemoryCandidate candidate) {
    return Optional.empty();
  }

  @Override
  public List<AceBullet> search(String query, int limit) {
    return List.of();
  }

  @Override
  public boolean archive(String id) {
    return false;
  }

  @Override
  public boolean edit(String id, String content) {
    return false;
  }

  @Override
  public int clear() {
    return 0;
  }

  @Override
  public boolean supersede(String id, String supersededBy) {
    return false;
  }

  @Override
  public List<AceBullet> list() {
    return List.of();
  }

  @Override
  public List<String> warnings() {
    return List.of(warning);
  }
}
