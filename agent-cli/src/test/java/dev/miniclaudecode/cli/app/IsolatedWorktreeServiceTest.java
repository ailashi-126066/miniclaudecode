package dev.miniclaudecode.cli.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IsolatedWorktreeServiceTest {
  @TempDir Path temporaryDirectory;

  @Test
  void commitsChangesInAnIsolatedWorktreeWithoutChangingThePrimaryWorkspace() throws Exception {
    Path primary = Files.createDirectory(this.temporaryDirectory.resolve("primary"));
    git(primary, "init");
    git(primary, "config", "user.name", "Test");
    git(primary, "config", "user.email", "test@example.invalid");
    Files.writeString(primary.resolve("App.java"), "class App {}\n");
    git(primary, "add", "App.java");
    git(primary, "commit", "-m", "initial");
    IsolatedWorktreeService service =
        new IsolatedWorktreeService(primary, this.temporaryDirectory.resolve("worktrees"));

    IsolatedWorktreeService.Worktree worktree = service.create("implement app");
    Files.writeString(
        worktree.path().resolve("App.java"), "class App { int value() { return 1; } }\n");
    IsolatedWorktreeService.Snapshot snapshot = service.snapshot(worktree, "implement app");

    assertThat(snapshot.changed()).isTrue();
    assertThat(snapshot.commit()).isNotBlank();
    assertThat(Files.readString(primary.resolve("App.java"))).isEqualTo("class App {}\n");
    assertThat(snapshot.summary()).contains("explicitly merge");
  }

  @Test
  void refusesMergeUntilAllThreeReviewLayersAreRecorded() throws Exception {
    Path primary = Files.createDirectory(this.temporaryDirectory.resolve("review-primary"));
    git(primary, "init");
    git(primary, "config", "user.name", "Test");
    git(primary, "config", "user.email", "test@example.invalid");
    Files.writeString(primary.resolve("App.java"), "class App {}\n");
    git(primary, "add", "App.java");
    git(primary, "commit", "-m", "initial");
    IsolatedWorktreeService service =
        new IsolatedWorktreeService(primary, this.temporaryDirectory.resolve("review-worktrees"));
    IsolatedWorktreeService.Worktree worktree = service.create("review task");
    Files.writeString(
        worktree.path().resolve("App.java"), "class App { int value() { return 1; } }\n");
    service.snapshot(worktree, "review task");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.merge(worktree.id()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("function, quality, and security");
    service.recordReview(worktree.id(), IsolatedWorktreeService.ReviewLayer.FUNCTION);
    service.recordReview(worktree.id(), IsolatedWorktreeService.ReviewLayer.QUALITY);
    service.recordReview(worktree.id(), IsolatedWorktreeService.ReviewLayer.SECURITY);

    assertThat(service.merge(worktree.id())).contains("Merged isolated worktree");
    assertThat(Files.readString(primary.resolve("App.java"))).contains("value()");
  }

  private static void git(Path directory, String... arguments) throws Exception {
    java.util.List<String> command =
        new java.util.ArrayList<>(java.util.List.of("git", "-C", directory.toString()));
    command.addAll(java.util.List.of(arguments));
    Process process = new ProcessBuilder(command).start();
    if (process.waitFor() != 0) {
      throw new IllegalStateException(new String(process.getErrorStream().readAllBytes()));
    }
  }
}
