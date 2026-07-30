package dev.miniclaudecode.rag.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Persisted provenance for an index build; it makes stale-index diagnostics explicit. */
final class WorkspaceVersion {
  private final Path file;

  WorkspaceVersion(Path indexRoot) {
    this.file = Objects.requireNonNull(indexRoot).resolve("workspace.version");
  }

  void save(Path workspace) throws IOException {
    Path root = workspace.toAbsolutePath().normalize();
    String head = gitHead(root.resolve(".git"));
    String content = "workspace=" + root + "\nhead=" + head + "\nindexedAt=" + Instant.now() + "\n";
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  String read() throws IOException {
    return Files.isRegularFile(file)
        ? Files.readString(file, StandardCharsets.UTF_8).strip()
        : "unknown";
  }

  private static String gitHead(Path gitDirectory) {
    String head = read(gitDirectory.resolve("HEAD"));
    if (!head.startsWith("ref: ")) {
      return "commit=" + head;
    }
    String reference = head.substring("ref: ".length()).strip();
    String commit = read(gitDirectory.resolve(reference));
    return "ref=" + reference + " commit=" + commit;
  }

  private static String read(Path path) {
    try {
      return Files.isRegularFile(path)
          ? Files.readString(path, StandardCharsets.UTF_8).strip()
          : "unknown";
    } catch (IOException ignored) {
      return "unknown";
    }
  }
}
