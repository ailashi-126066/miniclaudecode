package dev.miniclaudecode.tools.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.miniclaudecode.tools.diff.FileHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileOperationRecoveryTest {

  @TempDir Path temporaryDirectory;

  @Test
  void undoesAndRedoesOnlyWhenTheExpectedHashStillMatches() throws Exception {
    Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
    Path target = workspace.resolve("example.txt");
    Files.writeString(target, "after", StandardCharsets.UTF_8);
    FileOperationRecovery recovery =
        new FileOperationRecovery(workspace, temporaryDirectory.resolve("recovery"));
    recovery.record(
        "call-1",
        target,
        FileHashes.sha256("before".getBytes(StandardCharsets.UTF_8)),
        "before".getBytes(StandardCharsets.UTF_8),
        "after".getBytes(StandardCharsets.UTF_8));

    assertThat(recovery.undo(Optional.empty()).message()).contains("Undid");
    assertThat(Files.readString(target)).isEqualTo("before");

    assertThat(recovery.redo(Optional.of("call-1")).message()).contains("Redid");
    assertThat(Files.readString(target)).isEqualTo("after");

    Files.writeString(target, "manual edit", StandardCharsets.UTF_8);
    assertThatThrownBy(() -> recovery.undo(Optional.of("call-1")))
        .hasMessageContaining("file changed after the agent operation");
  }

  @Test
  void undoRemovesAnAgentCreatedFileOnlyWhenItIsUnchanged() throws Exception {
    Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
    Path target = workspace.resolve("new.txt");
    Files.writeString(target, "created", StandardCharsets.UTF_8);
    FileOperationRecovery recovery =
        new FileOperationRecovery(workspace, temporaryDirectory.resolve("recovery"));
    recovery.record(
        "call-2",
        target,
        FileHashes.MISSING,
        new byte[0],
        "created".getBytes(StandardCharsets.UTF_8));

    recovery.undo(Optional.empty());

    assertThat(Files.exists(target)).isFalse();
  }
}
