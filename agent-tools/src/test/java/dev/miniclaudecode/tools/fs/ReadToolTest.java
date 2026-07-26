package dev.miniclaudecode.tools.fs;

import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.MapAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadToolTest {
  @TempDir Path tempDirectory;

  @Test
  void readsUtf8TextWithStableLineNumbers() throws Exception {
    Path workspace = Files.createDirectory(this.tempDirectory.resolve("workspace"));
    Files.writeString(workspace.resolve("App.java"), "class App {\n  // 中文\n}\n");
    ReadTool tool = new ReadTool(new WorkspacePathResolver(workspace), this.store(), 1024, 1024);
    ToolResult result =
        (ToolResult)
            tool.execute(call("{\"path\":\"App.java\"}"), context(workspace))
                .toCompletableFuture()
                .get();
    Assertions.assertThat(result.status()).isEqualTo(Status.COMPLETED);
    Assertions.assertThat(result.summary()).isEqualTo("1 | class App {\n2 |   // 中文\n3 | }");
    Assertions.assertThat(result.resultReference()).isEmpty();
    ((MapAssert) Assertions.assertThat(result.metadata()).containsEntry("path", "App.java"))
        .containsEntry("truncated", false);
  }

  @Test
  void rejectsBinaryFilesWithoutLeakingTheirContent() throws Exception {
    Path workspace = Files.createDirectory(this.tempDirectory.resolve("workspace"));
    Files.write(workspace.resolve("image.bin"), new byte[] {1, 2, 0, 3, 4});
    ReadTool tool = new ReadTool(new WorkspacePathResolver(workspace), this.store(), 1024, 1024);
    ToolResult result =
        (ToolResult)
            tool.execute(call("{\"path\":\"image.bin\"}"), context(workspace))
                .toCompletableFuture()
                .get();
    Assertions.assertThat(result.status()).isEqualTo(Status.FAILED);
    ((AbstractStringAssert)
            Assertions.assertThat(result.summary()).contains(new CharSequence[] {"binary"}))
        .doesNotContain(new CharSequence[] {"\u0000"});
  }

  @Test
  void storesLargeOutputAndReturnsPreviewReference() throws Exception {
    Path workspace = Files.createDirectory(this.tempDirectory.resolve("workspace"));
    Files.writeString(
        workspace.resolve("large.txt"), "abcdef\n".repeat(100), StandardCharsets.UTF_8);
    ToolResultStore store = this.store();
    ReadTool tool = new ReadTool(new WorkspacePathResolver(workspace), store, 4096, 80);
    ToolResult result =
        (ToolResult)
            tool.execute(call("{\"path\":\"large.txt\"}"), context(workspace))
                .toCompletableFuture()
                .get();
    Assertions.assertThat(result.status()).isEqualTo(Status.COMPLETED);
    Assertions.assertThat(result.resultReference()).isPresent();
    Assertions.assertThat(result.metadata()).containsEntry("truncated", true);
    Assertions.assertThat(store.read((String) result.resultReference().orElseThrow()))
        .contains(new CharSequence[] {"100 | abcdef"});
    Assertions.assertThat(result.summary()).contains(new CharSequence[] {"output truncated"});
  }

  private ToolResultStore store() throws Exception {
    return new ToolResultStore(Files.createDirectories(this.tempDirectory.resolve("results")));
  }

  private static ToolCall call(String arguments) {
    return new ToolCall("call-1", "workspace:read", arguments);
  }

  private static ToolContext context(Path workspace) {
    return new ToolContext(
        new SessionId("session-1"), new TurnId(1L), workspace, EventSink.NOOP, Map.of());
  }
}
