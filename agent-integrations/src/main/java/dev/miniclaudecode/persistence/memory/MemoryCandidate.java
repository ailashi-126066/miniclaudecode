package dev.miniclaudecode.persistence.memory;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record MemoryCandidate(
    MemoryType type,
    MemoryScope scope,
    String content,
    String normalizedKey,
    MemoryAuthority authority,
    MemoryDurability durability,
    List<String> evidence,
    String sourceSessionId,
    String sourceTurnId) {
  public MemoryCandidate {
    type = Objects.requireNonNull(type, "type must not be null");
    scope = Objects.requireNonNull(scope, "scope must not be null");
    content = requireText(content, "content");
    normalizedKey = normalizeKey(normalizedKey);
    authority = Objects.requireNonNull(authority, "authority must not be null");
    durability = Objects.requireNonNull(durability, "durability must not be null");
    evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
    sourceSessionId = requireText(sourceSessionId, "sourceSessionId");
    sourceTurnId = requireText(sourceTurnId, "sourceTurnId");
  }

  public static String normalizeKey(String value) {
    return requireText(value, "normalizedKey")
        .replaceAll("\\s+", " ")
        .strip()
        .toLowerCase(Locale.ROOT);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.replaceAll("\\s+", " ").strip();
  }
}
