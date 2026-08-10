package dev.miniclaudecode.persistence.config;

public record MemoryConfig(
    boolean enabled,
    String backend,
    int topK,
    int maxInjectionTokens,
    int consolidateAfter,
    boolean embeddingEnabled,
    String postTurnExtraction) {
  public MemoryConfig {
    backend = backend == null ? "" : backend.trim().toLowerCase(java.util.Locale.ROOT);
    if (!"sqlite".equals(backend)) {
      throw new IllegalArgumentException("memory.backend must be sqlite");
    }
    if (topK < 1 || topK > 20) {
      throw new IllegalArgumentException("memory.top-k must be between 1 and 20");
    }
    if (maxInjectionTokens < 128 || maxInjectionTokens > 8_000) {
      throw new IllegalArgumentException(
          "memory.max-injection-tokens must be between 128 and 8000");
    }
    if (consolidateAfter < 2 || consolidateAfter > 1_000) {
      throw new IllegalArgumentException("memory.consolidate-after must be between 2 and 1000");
    }
    if (embeddingEnabled) {
      throw new IllegalArgumentException("memory embeddings are not supported; use SQLite FTS5");
    }
    postTurnExtraction =
        postTurnExtraction == null
            ? ""
            : postTurnExtraction.trim().toLowerCase(java.util.Locale.ROOT);
    if (!"signal-gated".equals(postTurnExtraction)) {
      throw new IllegalArgumentException("memory.post-turn-extraction must be signal-gated");
    }
  }

  public static MemoryConfig defaults() {
    return new MemoryConfig(true, "sqlite", 3, 1_000, 10, false, "signal-gated");
  }
}
