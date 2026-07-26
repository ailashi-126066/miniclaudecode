package dev.miniclaudecode.tools.fs;

import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobAndGrepToolTest {
  @TempDir Path tempDirectory;
  private Path workspace;
  private WorkspacePathResolver resolver;
  private ToolResultStore store;

  @BeforeEach
  void setUp() throws Exception {
    this.workspace = Files.createDirectory(this.tempDirectory.resolve("workspace"));
    Files.createDirectories(this.workspace.resolve("src/main/java"));
    Files.createDirectories(this.workspace.resolve("src/test/java"));
    Files.writeString(
        this.workspace.resolve("src/main/java/App.java"), "class App { // TODO implement\n}\n");
    Files.writeString(
        this.workspace.resolve("src/test/java/AppTest.java"), "class AppTest { // TODO test\n}\n");
    Files.write(this.workspace.resolve("src/logo.bin"), new byte[] {0, 1, 2});
    this.resolver = new WorkspacePathResolver(this.workspace);
    this.store = new ToolResultStore(Files.createDirectory(this.tempDirectory.resolve("results")));
  }

  @Test
  void globReturnsSortedPortableRelativePaths() throws Exception {
    GlobTool tool = new GlobTool(this.resolver, this.store, 100, 4096);
    ToolResult result =
        (ToolResult)
            tool.execute(call("workspace:glob", "{\"pattern\":\"**/*.java\"}"), this.context())
                .toCompletableFuture()
                .get();
    Assertions.assertThat(result.status()).isEqualTo(Status.COMPLETED);
    Assertions.assertThat(result.summary())
        .isEqualTo("src/main/java/App.java\nsrc/test/java/AppTest.java");
  }

  @Test
  void grepSearchesTextInJavaWithoutReadingBinaryFiles() throws Exception {
    GrepTool tool = new GrepTool(this.resolver, this.store, 100, 4096, 1048576L);
    ToolResult result =
        (ToolResult)
            tool.execute(
                    call(
                        "workspace:grep",
                        "{\"query\":\"TODO\\\\s+(implement|test)\",\"glob\":\"**/*.java\"}"),
                    this.context())
                .toCompletableFuture()
                .get();
    Assertions.assertThat(result.status()).isEqualTo(Status.COMPLETED);
    Assertions.assertThat(result.summary())
        .isEqualTo(
            "src/main/java/App.java:1: class App { // TODO implement\n"
                + "src/test/java/AppTest.java:1: class AppTest { // TODO test");
    Assertions.assertThat(result.metadata()).containsEntry("matches", 2);
  }

  private ToolContext context() {
    return new ToolContext(
        new SessionId("session-1"), new TurnId(1L), this.workspace, EventSink.NOOP, Map.of());
  }

  private static ToolCall call(String name, String arguments) {
    return new ToolCall("call-1", name, arguments);
  }
}
