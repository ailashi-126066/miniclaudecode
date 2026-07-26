package dev.miniclaudecode.extensions.mcp;

import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.extensions.mcp.McpManager.Connection;
import dev.miniclaudecode.extensions.mcp.McpPromptCatalog.PromptDescriptor;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpStdioIntegrationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void executesToolOverRealStdioTransportAfterApproval() throws Exception {
    String executable =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                    ? "java.exe"
                    : "java")
            .toString();
    McpServerConfig defaults =
        McpServerConfig.stdio(
            "stdio-test",
            List.of(
                executable,
                "-cp",
                System.getProperty("java.class.path"),
                StdioTestMcpServer.class.getName()));
    McpServerConfig config =
        new McpServerConfig(
            defaults.name(),
            defaults.transport(),
            defaults.command(),
            null,
            Map.of(),
            Map.of(),
            Duration.ofSeconds(10L),
            Duration.ofSeconds(10L),
            defaults.toolRisk());
    McpManager manager =
        new McpManager(
            new ToolResultStore(Files.createDirectory(this.temporaryDirectory.resolve("results"))),
            ignored -> true);

    try {
      Connection connection = manager.connect(config);
      AgentTool echo =
          connection.tools().stream()
              .filter(tool -> tool.descriptor().name().equals("echo"))
              .findFirst()
              .orElseThrow();
      ToolResult result =
          (ToolResult)
              echo.execute(
                      new ToolCall(
                          "call-1", echo.descriptor().qualifiedName(), "{\"text\":\"stdio\"}"),
                      this.context())
                  .toCompletableFuture()
                  .get();
      Assertions.assertThat(result.status()).isEqualTo(Status.COMPLETED);
      Assertions.assertThat(result.summary()).isEqualTo("echo:stdio");
      Assertions.assertThat(connection.prompts().list())
          .extracting(PromptDescriptor::name)
          .containsExactly(new String[] {"review"});
    } catch (Throwable var9) {
      try {
        manager.close();
      } catch (Throwable var8) {
        var9.addSuppressed(var8);
      }

      throw var9;
    }

    manager.close();
  }

  private ToolContext context() {
    return new ToolContext(
        new SessionId("session-1"),
        new TurnId(1L),
        this.temporaryDirectory,
        EventSink.NOOP,
        Map.of());
  }
}
