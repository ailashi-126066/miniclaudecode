package dev.miniclaudecode.persistence.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** User-owned explicit preferences, kept separate from project facts and agent experience. */
public final class UserProfileStore {
  private static final java.util.regex.Pattern SENSITIVE_VALUE =
      java.util.regex.Pattern.compile(
          "(?i)(api[_-]?key|token|password|secret)\\s*[:=]|(?:sk-|ghp_|AKIA)[A-Za-z0-9_-]{12,}");
  private final Path file;

  public UserProfileStore(Path file) {
    this.file = Objects.requireNonNull(file, "file must not be null").toAbsolutePath().normalize();
  }

  public synchronized boolean add(String preference) {
    String normalized = normalize(preference);
    if (SENSITIVE_VALUE.matcher(normalized).find()) {
      throw new IllegalArgumentException("preferences must not contain credentials or secrets");
    }
    List<String> existing = list();
    if (existing.stream().anyMatch(value -> value.equalsIgnoreCase(normalized))) {
      return false;
    }
    try {
      Path parent = this.file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(
          this.file,
          (Files.exists(this.file)
                  ? System.lineSeparator()
                  : "# User profile" + System.lineSeparator())
              + "- "
              + normalized,
          StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
      return true;
    } catch (IOException error) {
      throw new IllegalStateException("cannot save user preference", error);
    }
  }

  public synchronized List<String> list() {
    if (!Files.isRegularFile(this.file)) {
      return List.of();
    }
    try {
      return Files.readAllLines(this.file, StandardCharsets.UTF_8).stream()
          .map(String::strip)
          .filter(line -> line.startsWith("- "))
          .map(line -> line.substring(2).strip())
          .filter(line -> !line.isBlank())
          .toList();
    } catch (IOException error) {
      throw new IllegalStateException("cannot read user profile", error);
    }
  }

  private static String normalize(String value) {
    String normalized = Objects.requireNonNullElse(value, "").replaceAll("\\s+", " ").strip();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException("preference must not be blank");
    }
    return normalized;
  }
}
