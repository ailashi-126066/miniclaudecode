package dev.miniclaudecode.cli.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitCheckpointServiceTest {

  @TempDir Path workspace;

  @Test
  void createsSnapshotWithTemporaryIndexAndRestoresOnlyAfterPreview() throws Exception {
    Assumptions.assumeTrue(gitAvailable());
    git("init");
    Path file = workspace.resolve("example.txt");
    Files.writeString(file, "before", StandardCharsets.UTF_8);
    GitCheckpointService checkpoints = new GitCheckpointService(workspace);

    String created = checkpoints.create(1);
    Files.writeString(file, "after", StandardCharsets.UTF_8);
    Path createdByAgent = workspace.resolve("created-by-agent.txt");
    Files.writeString(createdByAgent, "new", StandardCharsets.UTF_8);

    assertThat(created).startsWith("Git checkpoint ");
    assertThat(checkpoints.list()).contains("checkpoint before turn 1");
    assertThat(checkpoints.previewRestore("refs/miniclaudecode/checkpoints"))
        .contains("example.txt", "Run /restore");

    checkpoints.restore("refs/miniclaudecode/checkpoints");

    assertThat(Files.readString(file)).isEqualTo("before");
    assertThat(createdByAgent).doesNotExist();
  }

  @Test
  void navigatesCheckpointHistoryWithUndoAndRedo() throws Exception {
    Assumptions.assumeTrue(gitAvailable());
    git("init");
    Path file = workspace.resolve("example.txt");
    GitCheckpointService checkpoints = new GitCheckpointService(workspace);

    Files.writeString(file, "before turn 1", StandardCharsets.UTF_8);
    checkpoints.create(1);
    Files.writeString(file, "after turn 1", StandardCharsets.UTF_8);
    checkpoints.create(2);
    Files.writeString(file, "after turn 2", StandardCharsets.UTF_8);

    assertThat(checkpoints.undo()).contains("Undid workspace");
    assertThat(Files.readString(file)).isEqualTo("after turn 1");
    assertThat(checkpoints.undo()).contains("Undid workspace");
    assertThat(Files.readString(file)).isEqualTo("before turn 1");

    assertThat(checkpoints.redo()).contains("Redid workspace");
    assertThat(Files.readString(file)).isEqualTo("after turn 1");
    assertThat(checkpoints.redo()).contains("Redid workspace");
    assertThat(Files.readString(file)).isEqualTo("after turn 2");
    assertThat(checkpoints.redo()).contains("No newer Git checkpoint");
  }

  private boolean gitAvailable() {
    try {
      return git("--version") == 0;
    } catch (IOException | InterruptedException error) {
      return false;
    }
  }

  private int git(String... arguments) throws IOException, InterruptedException {
    String[] command = new String[arguments.length + 3];
    command[0] = "git";
    command[1] = "-C";
    command[2] = workspace.toString();
    System.arraycopy(arguments, 0, command, 3, arguments.length);
    Process process = new ProcessBuilder(command).start();
    return process.waitFor();
  }
}
