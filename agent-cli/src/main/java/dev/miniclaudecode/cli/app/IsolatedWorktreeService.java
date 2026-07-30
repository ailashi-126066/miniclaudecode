package dev.miniclaudecode.cli.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates disposable, detached Git worktrees for writable delegated work.
 *
 * <p>Creating and committing a worktree never changes the user's branch or index. A caller must
 * explicitly decide whether to merge the returned commit into the primary workspace.
 */
final class IsolatedWorktreeService {
  private final Path workspace;
  private final Path root;
  private final java.util.Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
  private final java.util.Map<String, java.util.Set<ReviewLayer>> reviews =
      new ConcurrentHashMap<>();

  IsolatedWorktreeService(Path workspace, Path root) {
    this.workspace = Objects.requireNonNull(workspace).toAbsolutePath().normalize();
    this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
  }

  Worktree create(String taskName) {
    if (!"true".equals(git(this.workspace, "rev-parse", "--is-inside-work-tree").strip())) {
      throw new IllegalStateException("isolated writable tasks require a Git worktree");
    }
    String id = safeId(taskName) + "-" + UUID.randomUUID().toString().substring(0, 8);
    Path path = this.root.resolve(id).normalize();
    if (!path.startsWith(this.root)) {
      throw new IllegalArgumentException("worktree path escapes its managed root");
    }
    try {
      Files.createDirectories(this.root);
      git(this.workspace, "worktree", "add", "--detach", path.toString(), "HEAD");
      String baseCommit = git(path, "rev-parse", "HEAD").strip();
      return new Worktree(id, path, baseCommit, Instant.now());
    } catch (IOException error) {
      throw new IllegalStateException("cannot create isolated worktree", error);
    }
  }

  Snapshot snapshot(Worktree worktree, String message) {
    Objects.requireNonNull(worktree);
    String status = git(worktree.path(), "status", "--porcelain").strip();
    if (status.isBlank()) {
      Snapshot snapshot = new Snapshot(worktree, false, "", "No isolated changes to merge.");
      this.snapshots.put(worktree.id(), snapshot);
      return snapshot;
    }
    git(worktree.path(), "add", "-A");
    git(worktree.path(), "commit", "-m", "MiniClaudeCode isolated task: " + safeMessage(message));
    String commit = git(worktree.path(), "rev-parse", "HEAD").strip();
    Snapshot snapshot =
        new Snapshot(
            worktree,
            true,
            commit,
            "Isolated changes committed as "
                + commit
                + ". Review and explicitly merge this commit into the primary workspace.");
    this.snapshots.put(worktree.id(), snapshot);
    return snapshot;
  }

  Snapshot snapshot(String id) {
    Snapshot snapshot = this.snapshots.get(Objects.requireNonNull(id));
    if (snapshot == null) {
      throw new IllegalArgumentException("unknown isolated worktree: " + id);
    }
    return snapshot;
  }

  String merge(String id) {
    Snapshot snapshot = snapshot(id);
    if (!snapshot.changed()) {
      return snapshot.summary();
    }
    if (!this.reviews
        .getOrDefault(id, java.util.Set.of())
        .containsAll(
            java.util.Set.of(ReviewLayer.FUNCTION, ReviewLayer.QUALITY, ReviewLayer.SECURITY))) {
      throw new IllegalStateException(
          "merge requires completed function, quality, and security reviews");
    }
    git(this.workspace, "merge", "--no-ff", "--no-edit", snapshot.commit());
    return "Merged isolated worktree " + id + " commit " + snapshot.commit() + ".";
  }

  String recordReview(String id, ReviewLayer layer) {
    snapshot(id);
    this.reviews.computeIfAbsent(id, ignored -> ConcurrentHashMap.newKeySet()).add(layer);
    return "Recorded " + layer.name().toLowerCase() + " review for isolated worktree " + id + ".";
  }

  String discard(String id) {
    Snapshot snapshot = snapshot(id);
    git(this.workspace, "worktree", "remove", "--force", snapshot.worktree().path().toString());
    this.snapshots.remove(id);
    this.reviews.remove(id);
    return "Discarded isolated worktree " + id + ".";
  }

  private static String safeId(String taskName) {
    String value = Objects.requireNonNullElse(taskName, "task").replaceAll("[^A-Za-z0-9._-]+", "-");
    return value.isBlank() ? "task" : value.substring(0, Math.min(40, value.length()));
  }

  private static String safeMessage(String message) {
    String value =
        Objects.requireNonNullElse(message, "delegated task").replaceAll("[\\r\\n]+", " ").strip();
    return value.isBlank() ? "delegated task" : value.substring(0, Math.min(120, value.length()));
  }

  private static String git(Path directory, String... arguments) {
    List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
    command.addAll(List.of(arguments));
    ProcessBuilder process = new ProcessBuilder(command);
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
      throw new IllegalStateException("cannot execute Git", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Git operation interrupted", error);
    }
  }

  record Worktree(String id, Path path, String baseCommit, Instant createdAt) {}

  record Snapshot(Worktree worktree, boolean changed, String commit, String summary) {}

  enum ReviewLayer {
    FUNCTION,
    QUALITY,
    SECURITY
  }
}
