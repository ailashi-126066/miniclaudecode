package dev.miniclaudecode.persistence.memory;

import java.util.List;

public interface MemoryStore {
  AceBullet curate(AceBullet candidate);

  AceBullet propose(AceBullet candidate);

  boolean approve(String id);

  List<AceBullet> pending();

  List<AceBullet> search(String query, int limit);

  boolean archive(String id);

  List<AceBullet> list();

  default List<String> warnings() {
    return List.of();
  }
}
