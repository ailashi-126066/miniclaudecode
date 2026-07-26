package dev.miniclaudecode.persistence.checkpoint;

import java.nio.file.Path;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCheckpointSaverTest {
  @TempDir Path temporaryDirectory;

  @Test
  void restoresTheLatestCheckpointFromANewSaverInstance() throws Exception {
    RunnableConfig config = RunnableConfig.builder().threadId("session-1").build();
    Checkpoint checkpoint =
        Checkpoint.builder()
            .id("checkpoint-1")
            .state(Map.of("value", "saved"))
            .nodeId("await_approval")
            .nextNodeId("execute_tools")
            .build();
    FileCheckpointSaver<FileCheckpointSaverTest.TestState> first =
        new FileCheckpointSaver<>(this.temporaryDirectory, FileCheckpointSaverTest.TestState::new);
    first.put(config, checkpoint);
    FileCheckpointSaver<FileCheckpointSaverTest.TestState> restored =
        new FileCheckpointSaver<>(this.temporaryDirectory, FileCheckpointSaverTest.TestState::new);
    Checkpoint loaded = (Checkpoint) restored.get(config).orElseThrow();
    Assertions.assertThat(loaded.getState().get("value")).isEqualTo("saved");
    Assertions.assertThat(loaded.getNextNodeId()).contains(new CharSequence[] {"execute_tools"});
  }

  private static final class TestState extends AgentState {
    private TestState(Map<String, Object> data) {
      super(data);
    }
  }
}
