package dev.miniclaudecode.rag.index;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public final class FileFingerprintStore {

  /**
   * Version of the persisted fingerprint schema. A mismatch makes {@link #load()} return an empty
   * map, which forces one full rescan and re-chunk on the next synchronize. Version 2 introduced
   * the cheap change signal (size + mtime) and shipped together with the skeleton TYPE chunks, so
   * discarding version-1 data also replaces the stale whole-class chunks those indexes still hold
   * instead of letting them linger until each file happens to change.
   */
  static final String SCHEMA_VERSION = "3";

  private final Path file;
  private final Path versionFile;
  private final Path parent;

  public FileFingerprintStore(Path file) {
    this.file = file.toAbsolutePath().normalize();
    this.parent = Objects.requireNonNull(this.file.getParent(), "fingerprint file needs a parent");
    this.versionFile = this.parent.resolve(this.file.getFileName() + ".version");
  }

  /**
   * Content hash plus the cheap change signal recorded when the hash was computed.
   *
   * <p>The content hash stays the only source of truth for "has this file changed". Size and mtime
   * exist purely to skip work: when both still match, the scanner reuses the stored hash without
   * reading the file. They are never used on their own to declare a file changed, so a bare {@code
   * touch} does not trigger re-chunking — the re-read simply produces the same hash.
   */
  public record FileFingerprint(String contentHash, long sizeBytes, long modifiedMillis) {

    /** Sentinel for records that predate the cheap signal or failed to parse. */
    public static final long UNKNOWN = -1L;

    public FileFingerprint {
      Objects.requireNonNull(contentHash, "contentHash must not be null");
    }

    public static FileFingerprint withoutSignal(String contentHash) {
      return new FileFingerprint(contentHash, UNKNOWN, UNKNOWN);
    }

    public boolean hasCheapSignal() {
      return this.sizeBytes >= 0L && this.modifiedMillis >= 0L;
    }
  }

  public Map<String, FileFingerprint> load() throws IOException {
    if (!Files.exists(this.file) || !SCHEMA_VERSION.equals(this.readVersion())) {
      return Map.of();
    }

    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(this.file)) {
      properties.load(input);
    }

    Map<String, FileFingerprint> values = new LinkedHashMap<>();
    properties.stringPropertyNames().stream()
        .sorted()
        .forEach(key -> values.put(key, decode(properties.getProperty(key))));
    return Map.copyOf(values);
  }

  public void save(Map<String, FileFingerprint> fingerprints) throws IOException {
    Files.createDirectories(this.parent);
    Path temporary = Files.createTempFile(this.parent, "fingerprints-", ".tmp");

    try {
      Properties properties = new Properties();
      fingerprints.forEach(
          (path, fingerprint) -> properties.setProperty(path, encode(fingerprint)));

      try (OutputStream output = Files.newOutputStream(temporary)) {
        properties.store(output, "MiniClaudeCode workspace fingerprints");
      }

      move(temporary, this.file);
    } finally {
      Files.deleteIfExists(temporary);
    }

    // Written after the data file so a crash in between leaves version absent -> load() returns
    // empty -> full rescan. The failure mode is wasted work, never a stale cheap signal.
    Files.writeString(this.versionFile, SCHEMA_VERSION, StandardCharsets.UTF_8);
  }

  private String readVersion() throws IOException {
    if (!Files.exists(this.versionFile)) {
      return "";
    }
    return Files.readString(this.versionFile, StandardCharsets.UTF_8).trim();
  }

  private static String encode(FileFingerprint fingerprint) {
    return fingerprint.contentHash()
        + ","
        + fingerprint.sizeBytes()
        + ","
        + fingerprint.modifiedMillis();
  }

  /**
   * A value that fails to parse degrades to a hash without a cheap signal instead of throwing: the
   * worst case must always be "read and hash the file again", never a broken index.
   */
  private static FileFingerprint decode(String value) {
    String[] parts = value.split(",", -1);
    if (parts.length != 3) {
      return FileFingerprint.withoutSignal(value);
    }
    try {
      return new FileFingerprint(parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2]));
    } catch (NumberFormatException ignored) {
      return FileFingerprint.withoutSignal(parts[0]);
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
