package dev.miniclaudecode.extensions.mcp;

import dev.langchain4j.mcp.client.McpPrompt;
import dev.langchain4j.mcp.client.McpPromptArgument;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.extensions.mcp.McpManager.ConnectReport;
import dev.miniclaudecode.extensions.mcp.McpManager.Connection;
import dev.miniclaudecode.extensions.mcp.McpPromptCatalog.Argument;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpManagerTest {
  @TempDir Path temporaryDirectory;

  @Test
  void requiresApprovalBeforeStartingStdioAndNeverCallsFactoryWhenRejected() throws Exception {
    AtomicBoolean factoryCalled = new AtomicBoolean();
    McpManager manager =
        new McpManager(
            this.resultStore(),
            config -> false,
            config -> {
              factoryCalled.set(true);
              return this.client(config.name());
            },
            1024);
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(
                    () ->
                        manager.connect(McpServerConfig.stdio("local", List.of("java", "server"))))
                .isInstanceOf(SecurityException.class))
        .hasMessageContaining("rejected");
    Assertions.assertThat(factoryCalled).isFalse();
  }

  @Test
  void isolatesConnectionFailuresAndNamespacesSameNamedToolsByServer() throws Exception {
    McpManager manager =
        new McpManager(
            this.resultStore(),
            config -> true,
            config -> {
              if (config.name().equals("broken")) {
                throw new IllegalStateException("connection refused");
              } else {
                return this.client(config.name());
              }
            },
            1024);
    List<McpServerConfig> configurations =
        List.of(
            McpServerConfig.streamableHttp("github", URI.create("http://localhost/github")),
            McpServerConfig.streamableHttp("gitlab", URI.create("http://localhost/gitlab")),
            McpServerConfig.streamableHttp("broken", URI.create("http://localhost/broken")));
    ConnectReport report = manager.connectAll(configurations);
    Assertions.assertThat(report.connected()).containsExactly(new String[] {"github", "gitlab"});
    Assertions.assertThat(report.failures()).containsEntry("broken", "connection refused");
    Assertions.assertThat(manager.tools())
        .extracting(tool -> tool.descriptor().qualifiedName())
        .contains(
            new String[] {
              "mcp.github:search",
              "mcp.github:list_resources",
              "mcp.github:read_resource",
              "mcp.gitlab:search"
            });
    Assertions.assertThat(manager.tools())
        .allSatisfy(
            tool -> Assertions.assertThat(tool.descriptor().baseRisk()).isEqualTo(RiskLevel.HIGH));
  }

  @Test
  void discoversPromptMetadataAndClosesClients() throws Exception {
    FakeMcpClient client =
        this.client("docs")
            .prompts(
                List.of(
                    new McpPrompt(
                        "review",
                        "Review a patch",
                        List.of(new McpPromptArgument("focus", "review focus", false)))));
    McpManager manager = new McpManager(this.resultStore(), config -> true, config -> client, 1024);
    Connection connection =
        manager.connect(
            McpServerConfig.streamableHttp("docs", URI.create("https://example.test/mcp")));
    Assertions.assertThat(connection.prompts().list())
        .singleElement()
        .satisfies(
            prompt -> {
              Assertions.assertThat(prompt.server()).isEqualTo("docs");
              Assertions.assertThat(prompt.name()).isEqualTo("review");
              Assertions.assertThat(prompt.arguments())
                  .extracting(Argument::name)
                  .containsExactly(new String[] {"focus"});
            });
    manager.close();
    Assertions.assertThat(client.closed()).isTrue();
  }

  private FakeMcpClient client(String key) {
    return new FakeMcpClient(
        key,
        List.of(McpToolAdapterTest.specification("search")),
        request -> ToolExecutionResult.builder().resultText("ok").build());
  }

  private ToolResultStore resultStore() throws Exception {
    Path path = this.temporaryDirectory.resolve("results-" + System.nanoTime());
    return new ToolResultStore(Files.createDirectory(path));
  }
}
