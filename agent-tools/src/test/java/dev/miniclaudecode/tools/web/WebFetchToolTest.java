package dev.miniclaudecode.tools.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Choice;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Scope;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.tools.result.ToolResultStore;
import dev.miniclaudecode.tools.web.WebFetchTool.AddressResolver;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebFetchToolTest {
  @TempDir Path temporaryDirectory;
  private HttpServer server;
  private WebFetchTool publicTool;

  @BeforeEach
  void setUp() throws Exception {
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    this.server.createContext("/text", exchange -> respond(exchange, 200, "hello web"));
    this.server.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/text");
          exchange.sendResponseHeaders(302, -1L);
          exchange.close();
        });
    this.server.createContext("/large", exchange -> respond(exchange, 200, "x".repeat(500)));
    this.server.createContext(
        "/slow",
        exchange -> {
          try {
            Thread.sleep(1500L);
            respond(exchange, 200, "late");
          } catch (InterruptedException var2) {
            Thread.currentThread().interrupt();
          }
        });
    this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    this.server.start();
    this.publicTool = this.tool(host -> List.of(InetAddress.getByName("8.8.8.8")));
  }

  @AfterEach
  void tearDown() {
    this.server.stop(0);
  }

  @Test
  void fetchesTextAndRevalidatesRedirects() throws Exception {
    ToolResult result = this.execute(this.publicTool, this.url("/redirect"), 1024, Map.of());
    Assertions.assertThat(result.status()).isEqualTo(Status.COMPLETED);
    Assertions.assertThat(result.summary())
        .contains(new CharSequence[] {"HTTP 200", "/text", "hello web"});
    Assertions.assertThat(result.metadata()).containsEntry("status", 200);
  }

  @Test
  void rejectsUnsupportedProtocolsCloudMetadataAndOversizedResponses() throws Exception {
    Assertions.assertThat(
            this.execute(this.publicTool, "file:///etc/passwd", 1024, Map.of()).summary())
        .contains(new CharSequence[] {"only http and https"});
    WebFetchTool metadata = this.tool(host -> List.of(InetAddress.getByName("169.254.169.254")));
    Assertions.assertThat(
            this.execute(metadata, "http://metadata.test/latest", 1024, Map.of()).summary())
        .contains(new CharSequence[] {"cloud metadata endpoints are blocked"});
    Assertions.assertThat(
            this.execute(this.publicTool, this.url("/large"), 100, Map.of()).summary())
        .contains(new CharSequence[] {"exceeds maxBytes=100"});
  }

  @Test
  void timesOutBoundedRequests() throws Exception {
    ToolResult result = this.execute(this.publicTool, this.url("/slow"), 1024, Map.of(), 1);
    Assertions.assertThat(result.status()).isEqualTo(Status.FAILED);
    Assertions.assertThat(result.summary()).contains(new CharSequence[] {"HTTP fetch failed"});
  }

  @Test
  void privateNetworkRequiresBoundApprovalBeforeConnection() throws Exception {
    WebFetchTool privateTool = this.tool(host -> List.of(InetAddress.getByName("127.0.0.1")));
    ToolCall call = call(this.url("/text"), 1024, 30);
    ToolResult requested =
        (ToolResult) privateTool.execute(call, this.context(Map.of())).toCompletableFuture().get();
    ApprovalRequest approval = (ApprovalRequest) requested.metadata().get("approvalRequest");
    ApprovalDecision decision =
        new ApprovalDecision(
            approval.approvalId(),
            Choice.ALLOW,
            Scope.ONCE,
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:01Z"));
    ToolResult allowed =
        (ToolResult)
            privateTool
                .execute(
                    call,
                    this.context(Map.of("approvalRequest", approval, "approvalDecision", decision)))
                .toCompletableFuture()
                .get();
    Assertions.assertThat(requested.status()).isEqualTo(Status.APPROVAL_REQUIRED);
    Assertions.assertThat(allowed.status()).isEqualTo(Status.COMPLETED);
    Assertions.assertThat(allowed.summary()).contains(new CharSequence[] {"hello web"});
  }

  private WebFetchTool tool(AddressResolver resolver) throws Exception {
    return new WebFetchTool(
        HttpClient.newBuilder().followRedirects(Redirect.NEVER).build(),
        resolver,
        new ToolResultStore(Files.createDirectories(this.temporaryDirectory.resolve("results"))),
        Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
  }

  private ToolResult execute(
      WebFetchTool tool, String url, int maximumBytes, Map<String, Object> attributes)
      throws Exception {
    return this.execute(tool, url, maximumBytes, attributes, 30);
  }

  private ToolResult execute(
      WebFetchTool tool,
      String url,
      int maximumBytes,
      Map<String, Object> attributes,
      int timeoutSeconds)
      throws Exception {
    return (ToolResult)
        tool.execute(call(url, maximumBytes, timeoutSeconds), this.context(attributes))
            .toCompletableFuture()
            .get();
  }

  private static ToolCall call(String url, int maximumBytes, int timeoutSeconds) {
    return new ToolCall(
        "call-1",
        "web:fetch",
        "{\"url\":\""
            + url
            + "\",\"maxBytes\":"
            + maximumBytes
            + ",\"timeoutSeconds\":"
            + timeoutSeconds
            + "}");
  }

  private ToolContext context(Map<String, Object> attributes) {
    return new ToolContext(
        new SessionId("session-1"),
        new TurnId(1L),
        this.temporaryDirectory,
        EventSink.NOOP,
        attributes);
  }

  private String url(String path) {
    return "http://127.0.0.1:" + this.server.getAddress().getPort() + path;
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    HttpExchange var3 = exchange;

    try {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
      exchange.sendResponseHeaders(status, (long) bytes.length);
      exchange.getResponseBody().write(bytes);
    } catch (Throwable var7) {
      if (exchange != null) {
        try {
          var3.close();
        } catch (Throwable var6) {
          var7.addSuppressed(var6);
        }
      }

      throw var7;
    }

    if (exchange != null) {
      exchange.close();
    }
  }
}
