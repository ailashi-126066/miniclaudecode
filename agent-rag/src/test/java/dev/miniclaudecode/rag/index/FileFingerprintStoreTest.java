package dev.miniclaudecode.rag.index;

import dev.miniclaudecode.rag.index.FileFingerprintStore.FileFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileFingerprintStoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void roundTripsContentHashWithCheapSignal() throws Exception {
    FileFingerprintStore store =
        new FileFingerprintStore(this.temporaryDirectory.resolve("fingerprints.properties"));
    store.save(Map.of("src/App.java", new FileFingerprint("abc123", 42L, 1700000000000L)));
    Assertions.assertThat(store.load())
        .containsExactly(
            Map.entry("src/App.java", new FileFingerprint("abc123", 42L, 1700000000000L)));
  }

  @Test
  void discardsDataWrittenByAnOlderSchemaVersion() throws Exception {
    Path file = this.temporaryDirectory.resolve("fingerprints.properties");
    // A version-1 store wrote plain hash values and no version sidecar. Loading it must return
    // empty (forcing one full rescan), never throw and never serve a hash without provenance.
    Files.writeString(file, "src/App.java=abc123\n");
    Assertions.assertThat(new FileFingerprintStore(file).load()).isEmpty();
  }

  @Test
  void degradesMalformedValuesToHashWithoutSignalInsteadOfThrowing() throws Exception {
    Path file = this.temporaryDirectory.resolve("fingerprints.properties");
    Files.writeString(
        file, "good=hash1,10,1700000000000\nnosignal=hash2\nbadnumbers=hash3,ten,soon\n");
    Files.writeString(
        this.temporaryDirectory.resolve("fingerprints.properties.version"),
        FileFingerprintStore.SCHEMA_VERSION);
    Map<String, FileFingerprint> loaded = new FileFingerprintStore(file).load();
    Assertions.assertThat(loaded.get("good"))
        .isEqualTo(new FileFingerprint("hash1", 10L, 1700000000000L));
    Assertions.assertThat(loaded.get("nosignal")).isEqualTo(FileFingerprint.withoutSignal("hash2"));
    Assertions.assertThat(loaded.get("badnumbers"))
        .isEqualTo(FileFingerprint.withoutSignal("hash3"));
    Assertions.assertThat(loaded.get("good").hasCheapSignal()).isTrue();
    Assertions.assertThat(loaded.get("nosignal").hasCheapSignal()).isFalse();
  }
}
