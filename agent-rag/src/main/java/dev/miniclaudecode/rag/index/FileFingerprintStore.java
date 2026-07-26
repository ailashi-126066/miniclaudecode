package dev.miniclaudecode.rag.index;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public final class FileFingerprintStore {
  private final Path file;
  private final Path parent;

  public FileFingerprintStore(Path file) {
    this.file = file.toAbsolutePath().normalize();
    this.parent = Objects.requireNonNull(this.file.getParent(), "fingerprint file needs a parent");
  }

  public Map<String, String> load() throws IOException {
    if (!Files.exists(this.file)) {
      return Map.of();
    } else {
      Properties properties = new Properties();

      try (InputStream input = Files.newInputStream(this.file)) {
        properties.load(input);
      }

      Map<String, String> values = new LinkedHashMap<>();
      properties.stringPropertyNames().stream()
          .sorted()
          .forEach(key -> values.put(key, properties.getProperty(key)));
      return Map.copyOf(values);
    }
  }

  public void save(Map<String, String> fingerprints) throws IOException {
    Files.createDirectories(this.parent);
    Path temporary = Files.createTempFile(this.parent, "fingerprints-", ".tmp");

    try {
      Properties properties = new Properties();
      fingerprints.forEach(properties::setProperty);

      try (OutputStream output = Files.newOutputStream(temporary)) {
        properties.store(output, "MiniClaudeCode workspace fingerprints");
      }

      move(temporary, this.file);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void move(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException var3) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
