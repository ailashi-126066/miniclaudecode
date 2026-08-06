package dev.miniclaudecode.rag.index;

import dev.miniclaudecode.rag.index.FileFingerprintStore.FileFingerprint;
import dev.miniclaudecode.rag.index.WorkspaceScanner.ScannedFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Assumptions;
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
  void honorsGitIgnoreAndStillIncludesTrackedAndNewSourceFiles() throws Exception {
    Assumptions.assumeTrue(gitAvailable());
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("git-workspace"));
    git(workspace, "init");
    Files.createDirectories(workspace.resolve("src"));
    Files.createDirectories(workspace.resolve("datasets/raw"));
    Files.createDirectories(workspace.resolve("benchmarks/rag"));
    Files.writeString(workspace.resolve(".gitignore"), "datasets/\n*.log\n");
    Files.writeString(workspace.resolve("src/Tracked.java"), "class Tracked {}\n");
    git(workspace, "add", ".gitignore", "src/Tracked.java");
    Files.writeString(workspace.resolve("src/NewFile.java"), "class NewFile {}\n");
    Files.writeString(workspace.resolve("datasets/raw/Benchmark.java"), "class Benchmark {}\n");
    Files.writeString(workspace.resolve("debug.log"), "not source\n");
    Files.writeString(workspace.resolve("benchmarks/rag/Evaluation.java"), "class Evaluation {}\n");
    git(workspace, "add", "benchmarks/rag/Evaluation.java");

    Assertions.assertThat(new WorkspaceScanner().scan(workspace))
        .extracting(ScannedFile::path)
        .containsExactly(".gitignore", "src/NewFile.java", "src/Tracked.java");
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

  private static boolean gitAvailable() {
    try {
      return new ProcessBuilder("git", "--version").start().waitFor() == 0;
    } catch (java.io.IOException | InterruptedException error) {
      return false;
    }
  }

  private static void git(Path workspace, String... arguments) throws Exception {
    String[] command = new String[arguments.length + 3];
    command[0] = "git";
    command[1] = "-C";
    command[2] = workspace.toString();
    System.arraycopy(arguments, 0, command, 3, arguments.length);
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    Assertions.assertThat(process.waitFor()).as(output).isZero();
  }
}
