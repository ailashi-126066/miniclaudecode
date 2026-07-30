package dev.miniclaudecode.cli.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Creates Git snapshots through a disposable index, leaving the user's index and branch intact. */
final class GitCheckpointService {
  private static final String REF = "refs/miniclaudecode/checkpoints";

  private final Path workspace;

  GitCheckpointService(Path workspace) {
    this.workspace =
        Objects.requireNonNull(workspace, "workspace must not be null")
            .toAbsolutePath()
            .normalize();
  }

  String create(long turn) {
    if (!isRepository()) {
      return "Git checkpoint skipped (workspace is not a Git worktree).";
    }
    try {
      Path temporaryIndex = temporaryIndex();
      try {
        git(temporaryIndex, "add", "-A");
        String tree = git(temporaryIndex, "write-tree").strip();
        String parent = optionalGit("rev-parse", "--verify", "-q", REF);
        List<String> commit = new ArrayList<>(List.of("commit-tree", tree));
        if (parent != null) {
          commit.add("-p");
          commit.add(parent);
        }
        commit.add("-m");
        commit.add("MiniClaudeCode checkpoint before turn " + turn);
        String checkpoint = git(temporaryIndex, commit.toArray(String[]::new)).strip();
        git(temporaryIndex, "update-ref", REF, checkpoint);
        return "Git checkpoint "
            + checkpoint.substring(0, Math.min(12, checkpoint.length()))
            + " saved.";
      } finally {
        Files.deleteIfExists(temporaryIndex);
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return "Git checkpoint skipped: " + message(error);
    } catch (IOException error) {
      return "Git checkpoint skipped: " + message(error);
    } catch (RuntimeException error) {
      return "Git checkpoint skipped: " + message(error);
    }
  }

  String list() {
    if (!isRepository()) {
      return "(workspace is not a Git worktree)";
    }
    String checkpoints = optionalGit("log", "--format=%h %ad %s", "--date=short", REF);
    return checkpoints == null || checkpoints.isBlank()
        ? "(no Git checkpoints)"
        : checkpoints.strip();
  }

  String previewRestore(String revision) {
    requireCheckpoint(revision);
    try {
      Path temporaryIndex = temporaryIndex();
      try {
        git(temporaryIndex, "read-tree", revision);
        git(temporaryIndex, "add", "-A");
        String currentTree = git(temporaryIndex, "write-tree").strip();
        String changed =
            git(temporaryIndex, "diff-tree", "-r", "--name-status", revision, currentTree);
        return changed.isBlank()
            ? "Restore preview: workspace already matches " + revision
            : "Restore preview for "
                + revision
                + ":\n"
                + changed.strip()
                + "\nRun /restore "
                + revision
                + " apply to restore snapshot files. Files absent from the snapshot are left untouched.";
      } finally {
        Files.deleteIfExists(temporaryIndex);
      }
    } catch (IOException | InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "failed to preview checkpoint restore: " + message(error), error);
    }
  }

  String restore(String revision) {
    requireCheckpoint(revision);
    try {
      git(null, "restore", "--source=" + revision, "--worktree", "--", ".");
      return "Restored snapshot files from "
          + revision
          + ". Files absent from the snapshot were left untouched.";
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("failed to restore checkpoint", error);
    }
  }

  private boolean isRepository() {
    return "true".equals(optionalGit("rev-parse", "--is-inside-work-tree"));
  }

  private void requireCheckpoint(String revision) {
    if (revision == null || revision.isBlank()) {
      throw new IllegalArgumentException("checkpoint revision must not be blank");
    }
    String resolved = optionalGit("rev-parse", "--verify", revision + "^{commit}");
    if (resolved == null) {
      throw new IllegalArgumentException("unknown Git checkpoint: " + revision);
    }
  }

  private Path temporaryIndex() throws IOException, InterruptedException {
    String configuredIndex = git(null, "rev-parse", "--git-path", "index").strip();
    Path realIndex = Path.of(configuredIndex);
    if (!realIndex.isAbsolute()) {
      realIndex = this.workspace.resolve(realIndex).normalize();
    }
    Path indexDirectory = realIndex.getParent();
    if (indexDirectory == null) {
      indexDirectory = this.workspace;
    }
    Path temporary = Files.createTempFile(indexDirectory, "miniclaudecode-index-", ".tmp");
    if (Files.exists(realIndex)) {
      Files.copy(realIndex, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } else {
      Files.deleteIfExists(temporary);
    }
    return temporary;
  }

  private String git(Path temporaryIndex, String... arguments) throws InterruptedException {
    ProcessBuilder process = new ProcessBuilder(command(arguments));
    process.directory(this.workspace.toFile());
    if (temporaryIndex != null) {
      process.environment().put("GIT_INDEX_FILE", temporaryIndex.toString());
    }
    process.environment().putIfAbsent("GIT_AUTHOR_NAME", "MiniClaudeCode");
    process.environment().putIfAbsent("GIT_AUTHOR_EMAIL", "miniclaudecode@localhost");
    process.environment().putIfAbsent("GIT_COMMITTER_NAME", "MiniClaudeCode");
    process.environment().putIfAbsent("GIT_COMMITTER_EMAIL", "miniclaudecode@localhost");
    try {
      Process started = process.start();
      String stdout = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String stderr = new String(started.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      if (started.waitFor() != 0) {
        throw new IllegalStateException(stderr.isBlank() ? "git command failed" : stderr.strip());
      }
      return stdout;
    } catch (IOException error) {
      throw new IllegalStateException("cannot execute Git: " + message(error), error);
    }
  }

  private String optionalGit(String... arguments) {
    try {
      return git(null, arguments).strip();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return null;
    } catch (RuntimeException error) {
      return null;
    }
  }

  private List<String> command(String... arguments) {
    List<String> command = new ArrayList<>(List.of("git", "-C", this.workspace.toString()));
    command.addAll(List.of(arguments));
    return command;
  }

  private static String message(Throwable error) {
    return Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName());
  }
}
