package dev.miniclaudecode.persistence.memory;

import java.util.List;
import java.util.Optional;

public interface MemoryStore extends AutoCloseable {
  Optional<MemoryRecord> remember(MemoryCandidate candidate);

  AceBullet curate(AceBullet candidate);

  List<AceBullet> search(String query, int limit);

  boolean archive(String id);

  default boolean edit(String id, String content) {
    return false;
  }

  /** Archives all active memories in the current store scope without deleting history. */
  default int clear() {
    return 0;
  }

  default boolean supersede(String id, String supersededBy) {
    return archive(id);
  }

  List<AceBullet> list();

  default List<String> warnings() {
    return List.of();
  }

  default void requestConsolidation() {}

  @Override
  default void close() {}
}
