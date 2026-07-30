package dev.miniclaudecode.tools.fs;

import dev.miniclaudecode.tools.diff.FileHashes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Persists pre- and post-images for approved workspace mutations, enabling a hash-guarded undo and
 * redo without touching the user's Git index or branch.
 */
public final class FileOperationRecovery {
  private static final String RECOVERY_DIRECTORY = ".mini-claude-code/recovery";

  private final Path workspace;
  private final Path root;

  public FileOperationRecovery(Path workspace) {
    this(
        workspace,
        Path.of(System.getProperty("user.home"), RECOVERY_DIRECTORY)
            .resolve(
                FileHashes.sha256(
                    workspace
                        .toAbsolutePath()
                        .normalize()
                        .toString()
                        .getBytes(StandardCharsets.UTF_8))));
  }

  FileOperationRecovery(Path workspace, Path root) {
    this.workspace =
        Objects.requireNonNull(workspace, "workspace must not be null")
            .toAbsolutePath()
            .normalize();
    this.root = Objects.requireNonNull(root, "root must not be null").toAbsolutePath().normalize();
  }

  public Operation record(
      String operationId, Path target, String beforeHash, byte[] before, byte[] after) {
    Objects.requireNonNull(operationId, "operationId must not be null");
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(beforeHash, "beforeHash must not be null");
    Objects.requireNonNull(before, "before must not be null");
    Objects.requireNonNull(after, "after must not be null");
    Path normalizedTarget = target.toAbsolutePath().normalize();
    if (!normalizedTarget.startsWith(this.workspace)) {
      throw new IllegalArgumentException("recovery target escapes workspace");
    }
    Operation operation =
        new Operation(
            operationId,
            this.workspace.relativize(normalizedTarget).toString().replace('\\', '/'),
            beforeHash,
            FileHashes.sha256(after),
            FileHashes.MISSING.equals(beforeHash),
            State.APPLIED,
            Instant.now());
    try {
      Files.createDirectories(this.root);
      String key = key(operationId);
      writeAtomically(this.root.resolve(key + ".before"), before);
      writeAtomically(this.root.resolve(key + ".after"), after);
      save(operation);
      return operation;
    } catch (IOException error) {
      throw new IllegalStateException("failed to save recovery snapshot", error);
    }
  }

  public List<Operation> list() {
    if (!Files.isDirectory(this.root)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(this.root)) {
      return files
          .filter(path -> path.getFileName().toString().endsWith(".properties"))
          .map(this::loadSafely)
          .flatMap(Optional::stream)
          .sorted(Comparator.comparing(Operation::createdAt).reversed())
          .toList();
    } catch (IOException error) {
      throw new IllegalStateException("failed to list recovery snapshots", error);
    }
  }

  public Outcome undo(Optional<String> requestedId) {
    Operation operation = select(requestedId, State.APPLIED, "undo");
    Path target = resolve(operation.path());
    requireHash(
        target, operation.afterHash(), "Undo refused: file changed after the agent operation");
    try {
      if (operation.beforeMissing()) {
        Files.deleteIfExists(target);
      } else {
        writeAtomically(target, Files.readAllBytes(beforePath(operation.operationId())));
      }
      Operation updated = operation.withState(State.UNDONE);
      save(updated);
      return new Outcome(updated, "Undid agent change to " + updated.path());
    } catch (IOException error) {
      throw new IllegalStateException("failed to undo agent change", error);
    }
  }

  public Outcome redo(Optional<String> requestedId) {
    Operation operation = select(requestedId, State.UNDONE, "redo");
    Path target = resolve(operation.path());
    requireHash(target, operation.beforeHash(), "Redo refused: file changed after the undo");
    try {
      writeAtomically(target, Files.readAllBytes(afterPath(operation.operationId())));
      Operation updated = operation.withState(State.APPLIED);
      save(updated);
      return new Outcome(updated, "Redid agent change to " + updated.path());
    } catch (IOException error) {
      throw new IllegalStateException("failed to redo agent change", error);
    }
  }

  private Operation select(Optional<String> requestedId, State expectedState, String action) {
    Objects.requireNonNull(requestedId, "requestedId must not be null");
    return list().stream()
        .filter(operation -> requestedId.map(operation.operationId()::equals).orElse(true))
        .filter(operation -> operation.state() == expectedState)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "No "
                        + expectedState.name().toLowerCase()
                        + " agent operation is available to "
                        + action));
  }

  private void save(Operation operation) throws IOException {
    Properties properties = new Properties();
    properties.setProperty("operationId", operation.operationId());
    properties.setProperty("path", operation.path());
    properties.setProperty("beforeHash", operation.beforeHash());
    properties.setProperty("afterHash", operation.afterHash());
    properties.setProperty("beforeMissing", Boolean.toString(operation.beforeMissing()));
    properties.setProperty("state", operation.state().name());
    properties.setProperty("createdAt", operation.createdAt().toString());
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    properties.store(output, "MiniClaudeCode recovery operation");
    writeAtomically(
        this.root.resolve(key(operation.operationId()) + ".properties"), output.toByteArray());
  }

  private Optional<Operation> loadSafely(Path path) {
    try (InputStream input = Files.newInputStream(path)) {
      Properties properties = new Properties();
      properties.load(input);
      return Optional.of(
          new Operation(
              required(properties, "operationId"),
              required(properties, "path"),
              required(properties, "beforeHash"),
              required(properties, "afterHash"),
              Boolean.parseBoolean(required(properties, "beforeMissing")),
              State.valueOf(required(properties, "state")),
              Instant.parse(required(properties, "createdAt"))));
    } catch (IOException | IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }

  private Path resolve(String relativePath) {
    Path target = this.workspace.resolve(relativePath).normalize();
    if (!target.startsWith(this.workspace)) {
      throw new IllegalArgumentException("recovery path escapes workspace");
    }
    return target;
  }

  private static void requireHash(Path target, String expected, String message) {
    if (!FileHashes.hash(target).equals(expected)) {
      throw new IllegalStateException(message + ": " + target);
    }
  }

  private Path beforePath(String operationId) {
    return this.root.resolve(key(operationId) + ".before");
  }

  private Path afterPath(String operationId) {
    return this.root.resolve(key(operationId) + ".after");
  }

  private static String key(String operationId) {
    return FileHashes.sha256(operationId.getBytes(StandardCharsets.UTF_8));
  }

  private static String required(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing recovery property: " + key);
    }
    return value;
  }

  private static void writeAtomically(Path target, byte[] content) throws IOException {
    Path parent = Objects.requireNonNull(target.getParent(), "recovery target has no parent");
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, ".miniclaude-recovery-", ".tmp");
    try {
      try (OutputStream output = Files.newOutputStream(temporary)) {
        output.write(content);
      }
      Files.move(
          temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public enum State {
    APPLIED,
    UNDONE
  }

  public record Operation(
      String operationId,
      String path,
      String beforeHash,
      String afterHash,
      boolean beforeMissing,
      State state,
      Instant createdAt) {
    Operation withState(State updatedState) {
      return new Operation(
          this.operationId,
          this.path,
          this.beforeHash,
          this.afterHash,
          this.beforeMissing,
          updatedState,
          this.createdAt);
    }
  }

  public record Outcome(Operation operation, String message) {}
}
