package dev.miniclaudecode.extensions.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.extensions.mcp.McpManager.Connection;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpHttpIntegrationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void executesToolAndDiscoversResourcesOverRealStreamableHttpTransport() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/mcp", McpHttpIntegrationTest::handle);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();

    try {
      McpManager manager =
          new McpManager(
              new ToolResultStore(
                  Files.createDirectory(this.temporaryDirectory.resolve("results"))),
              ignored -> true);

      try {
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        Connection connection =
            manager.connect(McpServerConfig.streamableHttp("http-test", endpoint));
        AgentTool echo =
            connection.tools().stream()
                .filter(tool -> tool.descriptor().name().equals("echo"))
                .findFirst()
                .orElseThrow();
        AgentTool listResources =
            connection.tools().stream()
                .filter(tool -> tool.descriptor().name().equals("list_resources"))
                .findFirst()
                .orElseThrow();
        ToolResult echoed = this.execute(echo, "tool", "{\"text\":\"http\"}");
        ToolResult resources = this.execute(listResources, "resources", "{}");
        Assertions.assertThat(echoed.status()).isEqualTo(Status.COMPLETED);
        Assertions.assertThat(echoed.summary()).isEqualTo("echo:http");
        Assertions.assertThat(resources.summary())
            .contains(new CharSequence[] {"test://guide", "test guide"});
      } catch (Throwable var14) {
        try {
          manager.close();
        } catch (Throwable var13) {
          var14.addSuppressed(var13);
        }

        throw var14;
      }

      manager.close();
    } finally {
      server.stop(0);
    }
  }

  private static void handle(HttpExchange exchange) {
    try {
      HttpExchange exception = exchange;

      label48:
      {
        try {
          String request =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String response = TestMcpProtocol.respond(TestMcpProtocol.parse(request));
          exchange.getResponseHeaders().add("Mcp-Session-Id", "test-session");
          if (response.isEmpty()) {
            exchange.sendResponseHeaders(202, -1L);
            break label48;
          }

          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, (long) bytes.length);
          exchange.getResponseBody().write(bytes);
        } catch (Throwable var6) {
          if (exchange != null) {
            try {
              exception.close();
            } catch (Throwable var5) {
              var6.addSuppressed(var5);
            }
          }

          throw var6;
        }

        if (exchange != null) {
          exchange.close();
        }

        return;
      }

      if (exchange != null) {
        exchange.close();
      }
    } catch (Exception var7) {
      throw new IllegalStateException(var7);
    }
  }

  private ToolResult execute(AgentTool tool, String id, String arguments) throws Exception {
    return (ToolResult)
        tool.execute(
                new ToolCall(id, tool.descriptor().qualifiedName(), arguments),
                new ToolContext(
                    new SessionId("session-1"),
                    new TurnId(1L),
                    this.temporaryDirectory,
                    EventSink.NOOP,
                    Map.of()))
            .toCompletableFuture()
            .get();
  }
}
