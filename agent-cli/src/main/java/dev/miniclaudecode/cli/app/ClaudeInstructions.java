package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.persistence.path.UserDataLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Loads global and project-scoped miniclaude.md files without ever mutating them. */
final class ClaudeInstructions {
  private static final int MAX_FILES = 16;
  private static final int MAX_CHARACTERS_PER_FILE = 8_000;

  private final Path workspace;
  private final UserDataLayout layout;

  ClaudeInstructions(Path workspace, UserDataLayout layout) {
    this.workspace =
        Objects.requireNonNull(workspace, "workspace must not be null")
            .toAbsolutePath()
            .normalize();
    this.layout = Objects.requireNonNull(layout, "layout must not be null");
  }

  String load() {
    List<Path> files = new ArrayList<>();
    files.add(this.layout.globalMiniclaudeFile());
    try (Stream<Path> found = Files.find(this.workspace, 4, this::isClaudeFile)) {
      found.sorted().limit(MAX_FILES).forEach(files::add);
    } catch (IOException ignored) {
      // Instruction loading is best effort; workspace tools remain available for diagnosis.
    }
    StringBuilder instructions = new StringBuilder();
    files.stream()
        .filter(Files::isRegularFile)
        .distinct()
        .forEach(path -> append(instructions, path));
    return instructions.isEmpty()
        ? ""
        : "User-maintained instructions from miniclaude.md files follow. Apply the closest project "
            + "file when instructions conflict:\n"
            + instructions.toString().stripTrailing();
  }

  private boolean isClaudeFile(Path path, java.nio.file.attribute.BasicFileAttributes attributes) {
    if (!attributes.isRegularFile()
        || !"miniclaude.md".equalsIgnoreCase(path.getFileName().toString())) {
      return false;
    }
    Path relative = this.workspace.relativize(path);
    return !relative.startsWith(".git") && !relative.startsWith(".miniclaudecode");
  }

  private void append(StringBuilder output, Path path) {
    try {
      String content = Files.readString(path, StandardCharsets.UTF_8).strip();
      if (!content.isBlank()) {
        output
            .append("\n## ")
            .append(
                path.equals(this.layout.globalMiniclaudeFile())
                    ? "global"
                    : this.workspace.relativize(path))
            .append("\n")
            .append(content, 0, Math.min(content.length(), MAX_CHARACTERS_PER_FILE))
            .append("\n");
      }
    } catch (IOException ignored) {
      // A file disappearing during a scan is not a turn failure.
    }
  }
}
