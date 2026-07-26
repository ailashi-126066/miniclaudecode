package dev.miniclaudecode.tools.fs;

import dev.miniclaudecode.tools.diff.FileHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ConcurrentModificationException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileWriterTest {
  @TempDir Path tempDirectory;

  @Test
  void writesOnlyWhenExpectedSourceHashStillMatches() throws Exception {
    Path file = Files.writeString(this.tempDirectory.resolve("App.java"), "before");
    String hash = FileHashes.hash(file);
    AtomicFileWriter writer = new AtomicFileWriter();
    writer.write(file, "after".getBytes(StandardCharsets.UTF_8), hash);
    Assertions.assertThat(Files.readString(file)).isEqualTo("after");
    Assertions.assertThatThrownBy(
            () -> writer.write(file, "stale".getBytes(StandardCharsets.UTF_8), hash))
        .isInstanceOf(ConcurrentModificationException.class);
    Assertions.assertThat(Files.readString(file)).isEqualTo("after");
  }

  @Test
  void createsNewFileOnlyWhileItIsStillMissing() throws Exception {
    Path file = this.tempDirectory.resolve("New.java");
    AtomicFileWriter writer = new AtomicFileWriter();
    writer.write(file, "new".getBytes(StandardCharsets.UTF_8), "missing");
    Assertions.assertThat(Files.readString(file)).isEqualTo("new");
  }
}
