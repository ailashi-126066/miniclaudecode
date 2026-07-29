package dev.miniclaudecode.persistence.memory;

import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record MemoryRecord(
    String id,
    Category category,
    String objective,
    String summary,
    List<String> evidence,
    SessionId sourceSession,
    TurnId sourceTurn,
    Instant createdAt) {

  public MemoryRecord {
    Objects.requireNonNull(category, "category must not be null");
    objective = requireText(objective, "objective");
    summary = requireText(summary, "summary");
    evidence =
        List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null")).stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .limit(12)
            .toList();
    Objects.requireNonNull(sourceSession, "sourceSession must not be null");
    Objects.requireNonNull(sourceTurn, "sourceTurn must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    String canonical =
        category + "\n" + objective + "\n" + summary + "\n" + String.join("\n", evidence);
    id = id == null || id.isBlank() ? sha256(canonical) : requireText(id, "id");
  }

  @Override
  public List<String> evidence() {
    return new ArrayList<>(evidence);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  public enum Category {
    PATH_EXPERIENCE,
    ERROR_REPAIR,
    USER_PREFERENCE,
    SESSION_OUTCOME
  }
}
