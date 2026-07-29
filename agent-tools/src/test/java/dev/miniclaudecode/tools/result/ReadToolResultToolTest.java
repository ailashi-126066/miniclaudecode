package dev.miniclaudecode.tools.result;

import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import java.nio.file.Path;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadToolResultToolTest {
  @TempDir Path temporaryDirectory;

  @Test
  void retrievesBoundedPagesByContentAddress() throws Exception {
    ToolResultStore store = new ToolResultStore(this.temporaryDirectory.resolve("results"));
    String reference = store.put("0123456789");
    ReadToolResultTool tool = new ReadToolResultTool(store);

    ToolResult result =
        tool.execute(
                new ToolCall(
                    "read-result",
                    "context:read_result",
                    "{\"reference\":\"" + reference + "\",\"offset\":3,\"maxCharacters\":4}"),
                new ToolContext(
                    SessionId.of("session"),
                    TurnId.of(1),
                    this.temporaryDirectory,
                    EventSink.NOOP,
                    Map.of()))
            .toCompletableFuture()
            .get();

    Assertions.assertThat(result.summary()).isEqualTo("3456");
    Assertions.assertThat(result.resultReference()).contains(reference);
    Assertions.assertThat(result.metadata())
        .containsEntry("nextOffset", 7)
        .containsEntry("hasMore", true);
  }
}
