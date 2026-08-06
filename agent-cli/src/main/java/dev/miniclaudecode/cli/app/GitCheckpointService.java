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
  private String restoredCheckpoint;

  GitCheckpointService(Path workspace) {
    this.workspace =
        Objects.requireNonNull(workspace, "workspace must not be null")
            .toAbsolutePath()
            .normalize();
  }

  synchronized String create(long turn) {
    if (!isRepository()) {
      return "Git checkpoint skipped (workspace is not a Git worktree).";
    }
    try {
      String parent =
          this.restoredCheckpoint != null
              ? this.restoredCheckpoint
              : optionalGit("rev-parse", "--verify", "-q", REF);
      String checkpoint = snapshot("MiniClaudeCode checkpoint before turn " + turn, parent);
      this.restoredCheckpoint = null;
      return "Git checkpoint " + shortRevision(checkpoint) + " saved.";
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return "Git checkpoint skipped: " + message(error);
    } catch (IOException error) {
      return "Git checkpoint skipped: " + message(error);
    } catch (RuntimeException error) {
      return "Git checkpoint skipped: " + message(error);
    }
  }

  synchronized String list() {
    if (!isRepository()) {
      return "(workspace is not a Git worktree)";
    }
    String checkpoints = optionalGit("log", "--format=%h %ad %s", "--date=short", REF);
    return checkpoints == null || checkpoints.isBlank()
        ? "(no Git checkpoints)"
        : checkpoints.strip();
  }

  synchronized String previewRestore(String revision) {
    String checkpoint = requireCheckpoint(revision);
    try {
      Path temporaryIndex = temporaryIndex();
      try {
        git(temporaryIndex, "read-tree", checkpoint);
        git(temporaryIndex, "add", "-A");
        String currentTree = git(temporaryIndex, "write-tree").strip();
        String changed =
            git(temporaryIndex, "diff-tree", "-r", "--name-status", checkpoint, currentTree);
        return changed.isBlank()
            ? "Restore preview: workspace already matches " + revision
            : "Restore preview for "
                + revision
                + ":\n"
                + changed.strip()
                + "\nRun /restore "
                + revision
                + " apply to restore the checkpoint.";
      } finally {
        Files.deleteIfExists(temporaryIndex);
      }
    } catch (IOException | InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "failed to preview checkpoint restore: " + message(error), error);
    }
  }

  synchronized String restore(String revision) {
    String checkpoint = requireCheckpoint(revision);
    try {
      restoreFiles(checkpoint);
      this.restoredCheckpoint = checkpoint;
      return "Restored Git checkpoint " + shortRevision(checkpoint) + ".";
    } catch (IOException | InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("failed to restore checkpoint", error);
    }
  }

  synchronized String undo() {
    if (!isRepository()) {
      return "Cannot undo: workspace is not a Git worktree.";
    }
    String target;
    try {
      if (this.restoredCheckpoint == null) {
        target = optionalGit("rev-parse", "--verify", "-q", REF);
        if (target == null) {
          return "No Git checkpoint is available to undo.";
        }
        snapshot("MiniClaudeCode checkpoint before undo", target);
      } else {
        target = optionalGit("rev-parse", "--verify", "-q", this.restoredCheckpoint + "^");
        if (target == null) {
          return "No earlier Git checkpoint is available to undo.";
        }
      }
      restoreFiles(target);
      this.restoredCheckpoint = target;
      return "Undid workspace to Git checkpoint " + shortRevision(target) + ".";
    } catch (IOException | InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("failed to undo Git checkpoint", error);
    }
  }

  synchronized String redo() {
    if (!isRepository()) {
      return "Cannot redo: workspace is not a Git worktree.";
    }
    if (this.restoredCheckpoint == null) {
      return "No undone Git checkpoint is available to redo.";
    }
    String descendants =
        optionalGit(
            "rev-list", "--first-parent", "--reverse", this.restoredCheckpoint + ".." + REF);
    if (descendants == null || descendants.isBlank()) {
      return "No newer Git checkpoint is available to redo.";
    }
    String target = descendants.lines().findFirst().orElseThrow();
    try {
      restoreFiles(target);
      this.restoredCheckpoint = target;
      return "Redid workspace to Git checkpoint " + shortRevision(target) + ".";
    } catch (IOException | InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("failed to redo Git checkpoint", error);
    }
  }

  private boolean isRepository() {
    return "true".equals(optionalGit("rev-parse", "--is-inside-work-tree"));
  }

  private String requireCheckpoint(String revision) {
    if (revision == null || revision.isBlank()) {
      throw new IllegalArgumentException("checkpoint revision must not be blank");
    }
    String resolved = optionalGit("rev-parse", "--verify", revision + "^{commit}");
    String head = optionalGit("rev-parse", "--verify", "-q", REF);
    if (resolved == null
        || head == null
        || optionalGit("merge-base", "--is-ancestor", resolved, head) == null) {
      throw new IllegalArgumentException("unknown Git checkpoint: " + revision);
    }
    return resolved;
  }

  private String snapshot(String message, String parent) throws IOException, InterruptedException {
    Path temporaryIndex = temporaryIndex();
    try {
      git(temporaryIndex, "add", "-A");
      String tree = git(temporaryIndex, "write-tree").strip();
      List<String> commit = new ArrayList<>(List.of("commit-tree", tree));
      if (parent != null) {
        commit.add("-p");
        commit.add(parent);
      }
      commit.add("-m");
      commit.add(message);
      String checkpoint = git(temporaryIndex, commit.toArray(String[]::new)).strip();
      git(temporaryIndex, "update-ref", REF, checkpoint);
      return checkpoint;
    } finally {
      Files.deleteIfExists(temporaryIndex);
    }
  }

  private void restoreFiles(String checkpoint) throws IOException, InterruptedException {
    Path temporaryIndex = temporaryIndex();
    try {
      git(temporaryIndex, "add", "-A");
      git(temporaryIndex, "read-tree", "--reset", "-u", checkpoint);
    } finally {
      Files.deleteIfExists(temporaryIndex);
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

  private static String shortRevision(String revision) {
    return revision.substring(0, Math.min(12, revision.length()));
  }
}
