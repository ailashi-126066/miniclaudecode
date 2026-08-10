package dev.miniclaudecode.persistence.memory;

@FunctionalInterface
public interface MemoryConsolidator {
  void consolidate(MemoryStore store);
}
