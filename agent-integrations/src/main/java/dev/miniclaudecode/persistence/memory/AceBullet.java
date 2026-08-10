package dev.miniclaudecode.persistence.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** A compact, project-scoped lesson produced from a failed or repaired agent turn. */
public record AceBullet(
    String id,
    String trigger,
    String lesson,
    List<String> evidence,
    int occurrences,
    Instant createdAt,
    Instant updatedAt,
    double confidence,
    List<String> applicablePaths,
    AceBullet.State state) {
  public AceBullet {
    trigger = text(trigger, "trigger");
    lesson = text(lesson, "lesson");
    evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
    if (occurrences < 1) {
      throw new IllegalArgumentException("occurrences must be positive");
    }
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    if (confidence < 0.0 || confidence > 1.0 || !Double.isFinite(confidence)) {
      throw new IllegalArgumentException("confidence must be between zero and one");
    }
    applicablePaths =
        List.copyOf(Objects.requireNonNull(applicablePaths, "applicablePaths must not be null"));
    state = Objects.requireNonNull(state, "state must not be null");
    id = id == null || id.isBlank() ? hash(trigger + "\n" + lesson) : text(id, "id");
  }

  /** Compatibility constructor for the original on-disk schema and callers. */
  public AceBullet(
      String id,
      String trigger,
      String lesson,
      List<String> evidence,
      int occurrences,
      Instant createdAt,
      Instant updatedAt) {
    this(
        id,
        trigger,
        lesson,
        evidence,
        occurrences,
        createdAt,
        updatedAt,
        0.55,
        List.of(),
        State.ACTIVE);
  }

  public AceBullet merge(AceBullet newer) {
    return new AceBullet(
        this.id,
        this.trigger,
        this.lesson,
        java.util.stream.Stream.concat(this.evidence.stream(), newer.evidence.stream())
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .limit(8)
            .toList(),
        this.occurrences + 1,
        this.createdAt,
        newer.updatedAt,
        Math.min(0.95, Math.max(this.confidence, newer.confidence) + 0.05),
        java.util.stream.Stream.concat(
                this.applicablePaths.stream(), newer.applicablePaths.stream())
            .filter(path -> path != null && !path.isBlank())
            .distinct()
            .limit(12)
            .toList(),
        State.ACTIVE);
  }

  private static String text(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.replaceAll("\\s+", " ").strip();
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  public enum State {
    ACTIVE,
    SUPERSEDED,
    ARCHIVED
  }
}
