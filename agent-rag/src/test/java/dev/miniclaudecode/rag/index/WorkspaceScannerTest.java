package dev.miniclaudecode.rag.index;

import dev.miniclaudecode.rag.index.FileFingerprintStore.FileFingerprint;
import dev.miniclaudecode.rag.index.WorkspaceScanner.ScannedFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
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

  @Test
  void reusesStoredHashWithoutReadingWhenSizeAndMtimeMatch() throws Exception {
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace"));
    Path file = workspace.resolve("App.java");
    Files.writeString(file, "class App { void one() {} }\n");
    WorkspaceScanner scanner = new WorkspaceScanner();
    ScannedFile first = scanner.scan(workspace).getFirst();
    Assertions.assertThat(first.content()).isPresent();

    // Swap the content for different bytes of the same length and restore the recorded mtime.
    // If the scanner still read and hashed the file, the fingerprint would change; getting the
    // stored hash back proves the cheap path skipped the read entirely.
    Files.writeString(file, "class App { void two() {} }\n");
    Files.setLastModifiedTime(file, FileTime.fromMillis(first.modifiedMillis()));
    Map<String, FileFingerprint> known =
        Map.of(
            first.path(),
            new FileFingerprint(first.fingerprint(), first.sizeBytes(), first.modifiedMillis()));
    ScannedFile second = scanner.scan(workspace, known).getFirst();
    Assertions.assertThat(second.fingerprint()).isEqualTo(first.fingerprint());
    Assertions.assertThat(second.content()).isEmpty();
  }

  @Test
  void readsAndRehashesWhenTheCheapSignalDiffers() throws Exception {
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace"));
    Path file = workspace.resolve("App.java");
    Files.writeString(file, "class App { void one() {} }\n");
    WorkspaceScanner scanner = new WorkspaceScanner();
    ScannedFile first = scanner.scan(workspace).getFirst();

    Files.writeString(file, "class App { void changed() {} }\n");
    Map<String, FileFingerprint> known =
        Map.of(
            first.path(),
            new FileFingerprint(first.fingerprint(), first.sizeBytes(), first.modifiedMillis()));
    ScannedFile second = scanner.scan(workspace, known).getFirst();
    Assertions.assertThat(second.fingerprint()).isNotEqualTo(first.fingerprint());
    Assertions.assertThat(second.content()).contains("class App { void changed() {} }\n");
  }

  @Test
  void readsAndRehashesWhenTheKnownFingerprintLacksACheapSignal() throws Exception {
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace"));
    Files.writeString(workspace.resolve("App.java"), "class App {}\n");
    WorkspaceScanner scanner = new WorkspaceScanner();
    ScannedFile fresh = scanner.scan(workspace).getFirst();

    List<ScannedFile> rescanned =
        scanner.scan(
            workspace, Map.of(fresh.path(), FileFingerprint.withoutSignal(fresh.fingerprint())));
    ScannedFile upgraded = rescanned.getFirst();
    Assertions.assertThat(upgraded.fingerprint()).isEqualTo(fresh.fingerprint());
    Assertions.assertThat(upgraded.content()).isPresent();
    Assertions.assertThat(upgraded.sizeBytes()).isEqualTo(fresh.sizeBytes());
  }
}
