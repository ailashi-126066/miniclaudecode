package dev.miniclaudecode.extensions.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.mcp.client.McpBlobResourceContents;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpResource;
import dev.langchain4j.mcp.client.McpResourceContents;
import dev.langchain4j.mcp.client.McpTextResourceContents;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolEffect;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class McpResourceTools {
  private static final ObjectMapper JSON = new ObjectMapper();

  private McpResourceTools() {}

  public static List<AgentTool> create(
      String namespace,
      McpClient client,
      RiskLevel risk,
      ToolResultStore resultStore,
      int inlineByteLimit) {
    Objects.requireNonNull(client, "client must not be null");
    Objects.requireNonNull(resultStore, "resultStore must not be null");
    if (inlineByteLimit < 1) {
      throw new IllegalArgumentException("inlineByteLimit must be positive");
    } else {
      return List.of(
          new McpResourceTools.ListResourcesTool(namespace, client, risk),
          new McpResourceTools.ReadResourceTool(
              namespace, client, risk, resultStore, inlineByteLimit));
    }
  }

  private static String describe(McpResource resource) {
    String description = resource.description() == null ? "" : " - " + resource.description();
    return resource.uri() + " (" + resource.name() + ")" + description;
  }

  private static String content(McpResourceContents content) {
    if (content instanceof McpTextResourceContents text) {
      return text.text();
    } else {
      return content instanceof McpBlobResourceContents blob
          ? "[base64 "
              + Objects.requireNonNullElse(blob.mimeType(), "application/octet-stream")
              + "]\n"
              + blob.blob()
          : content.toString();
    }
  }

  private static ToolResult completed(ToolCall call, String output, String server) {
    return new ToolResult(
        call.toolCallId(), Status.COMPLETED, output, Optional.empty(), Map.of("server", server));
  }

  private static ToolResult largeResult(
      ToolCall call,
      String output,
      String server,
      ToolResultStore resultStore,
      int inlineByteLimit) {
    if (output.getBytes(StandardCharsets.UTF_8).length <= inlineByteLimit) {
      return completed(call, output, server);
    } else {
      String reference = resultStore.put(output);
      String preview =
          output.substring(0, Math.min(output.length(), Math.max(1, inlineByteLimit / 2)));
      return new ToolResult(
          call.toolCallId(),
          Status.COMPLETED,
          preview + "\n… MCP resource truncated; full result: " + reference,
          Optional.of(reference),
          Map.of("server", server, "truncated", true));
    }
  }

  private static ToolResult failed(ToolCall call, String server, Exception exception) {
    String message =
        Objects.requireNonNullElse(exception.getMessage(), exception.getClass().getSimpleName());
    return new ToolResult(
        call.toolCallId(),
        Status.FAILED,
        "MCP resource operation failed: " + message,
        Optional.empty(),
        Map.of("server", server));
  }

  private static final class ListResourcesTool implements AgentTool {
    private final McpClient client;
    private final ToolDescriptor descriptor;

    private ListResourcesTool(String namespace, McpClient client, RiskLevel risk) {
      this.client = client;
      this.descriptor =
          new ToolDescriptor(
              namespace,
              "list_resources",
              "List resources exposed by this MCP server",
              "{\"type\":\"object\"}",
              risk,
              ToolEffect.READ_ONLY_EXTERNAL);
    }

    public ToolDescriptor descriptor() {
      return this.descriptor;
    }

    public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
      try {
        String output =
            this.client.listResources().stream()
                .map(McpResourceTools::describe)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("No MCP resources.");
        return CompletableFuture.completedFuture(
            McpResourceTools.completed(call, output, this.client.key()));
      } catch (RuntimeException var4) {
        return CompletableFuture.completedFuture(
            McpResourceTools.failed(call, this.client.key(), var4));
      }
    }
  }

  private static final class ReadResourceTool implements AgentTool {
    private final McpClient client;
    private final ToolDescriptor descriptor;
    private final ToolResultStore resultStore;
    private final int inlineByteLimit;

    private ReadResourceTool(
        String namespace,
        McpClient client,
        RiskLevel risk,
        ToolResultStore resultStore,
        int inlineByteLimit) {
      this.client = client;
      this.resultStore = resultStore;
      this.inlineByteLimit = inlineByteLimit;
      this.descriptor =
          new ToolDescriptor(
              namespace,
              "read_resource",
              "Read one resource exposed by this MCP server",
              "{\"type\":\"object\",\"properties\":{\"uri\":{\"type\":\"string\"}},\"required\":[\"uri\"]}",
              risk,
              ToolEffect.READ_ONLY_EXTERNAL);
    }

    public ToolDescriptor descriptor() {
      return this.descriptor;
    }

    public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
      try {
        JsonNode arguments = McpResourceTools.JSON.readTree(call.argumentsJson());
        JsonNode uri = arguments.path("uri");
        if (uri.isTextual() && !uri.asText().isBlank()) {
          McpReadResourceResult resource = this.client.readResource(uri.asText());
          String output =
              resource.contents().stream()
                  .map(McpResourceTools::content)
                  .reduce((a, b) -> a + "\n" + b)
                  .orElse("(empty resource)");
          return CompletableFuture.completedFuture(
              McpResourceTools.largeResult(
                  call, output, this.client.key(), this.resultStore, this.inlineByteLimit));
        } else {
          throw new IllegalArgumentException("uri must be a non-blank string");
        }
      } catch (RuntimeException | IOException var7) {
        return CompletableFuture.completedFuture(
            McpResourceTools.failed(call, this.client.key(), var7));
      }
    }
  }
}
