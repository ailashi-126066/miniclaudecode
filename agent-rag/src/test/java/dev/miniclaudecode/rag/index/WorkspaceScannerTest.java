package dev.miniclaudecode.rag.index;

import dev.miniclaudecode.rag.index.WorkspaceScanner.ScannedFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceScannerTest {
  @TempDir Path temporaryDirectory;

  @Test
  void ignoresBuildMetadataAndBinaryFiles() throws Exception {
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace"));
    Files.createDirectories(workspace.resolve("src"));
    Files.createDirectories(workspace.resolve("target"));
    Files.createDirectories(workspace.resolve(".git"));
    Files.writeString(workspace.resolve("src/App.java"), "class App {}\n");
    Files.writeString(workspace.resolve("target/generated.java"), "class Generated {}\n");
    Files.writeString(workspace.resolve(".git/config"), "secret\n");
    Files.write(workspace.resolve("src/logo.bin"), new byte[] {1, 0, 2});
    Assertions.assertThat(new WorkspaceScanner().scan(workspace))
        .extracting(ScannedFile::path)
        .containsExactly(new String[] {"src/App.java"});
  }
}
