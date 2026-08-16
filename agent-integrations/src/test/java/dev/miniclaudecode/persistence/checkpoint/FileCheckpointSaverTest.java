package dev.miniclaudecode.persistence.checkpoint;

import dev.miniclaudecode.domain.session.SessionId;
import java.nio.file.Path;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCheckpointSaverTest {
  @TempDir Path temporaryDirectory;

  @Test
  void restoresTheLatestCheckpointFromANewSaverInstance() {
    SessionId session = SessionId.of("session-1");
    FileCheckpointSaver<FileCheckpointSaverTest.TestState> first =
        new FileCheckpointSaver<>(this.temporaryDirectory, FileCheckpointSaverTest.TestState::new);
    first.save(session, Map.of("value", "saved"));
    FileCheckpointSaver<FileCheckpointSaverTest.TestState> restored =
        new FileCheckpointSaver<>(this.temporaryDirectory, FileCheckpointSaverTest.TestState::new);
    TestState loaded = restored.get(session).orElseThrow();
    Assertions.assertThat(loaded.data().get("value")).isEqualTo("saved");
    restored.release(session);
    Assertions.assertThat(restored.get(session)).isEmpty();
  }

  private record TestState(Map<String, Object> data) {
    private TestState(Map<String, Object> data) {
      this.data = Map.copyOf(data);
    }
  }
}
