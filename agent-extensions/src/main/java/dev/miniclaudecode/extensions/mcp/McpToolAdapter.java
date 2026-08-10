package dev.miniclaudecode.extensions.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolEffect;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class McpToolAdapter implements AgentTool {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final McpClient client;
  private final ToolSpecification specification;
  private final ToolDescriptor descriptor;
  private final ToolResultStore resultStore;
  private final int inlineByteLimit;

  public McpToolAdapter(
      String namespace,
      McpClient client,
      ToolSpecification specification,
      RiskLevel risk,
      ToolResultStore resultStore,
      int inlineByteLimit) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.specification = Objects.requireNonNull(specification, "specification must not be null");
    this.resultStore = Objects.requireNonNull(resultStore, "resultStore must not be null");
    if (inlineByteLimit < 1) {
      throw new IllegalArgumentException("inlineByteLimit must be positive");
    } else {
      this.inlineByteLimit = inlineByteLimit;
      this.descriptor =
          new ToolDescriptor(
              namespace,
              specification.name(),
              description(specification),
              inputSchema(specification),
              risk,
              ToolEffect.EXTERNAL_EFFECT);
    }
  }

  public ToolDescriptor descriptor() {
    return this.descriptor;
  }

  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      ToolExecutionResult result =
          this.client.executeTool(
              ToolExecutionRequest.builder()
                  .id(call.toolCallId())
                  .name(this.specification.name())
                  .arguments(call.argumentsJson())
                  .build());
      String output =
          Objects.requireNonNullElse(result.resultText(), String.valueOf(result.result()));
      return CompletableFuture.completedFuture(this.toResult(call, output, result.isError()));
    } catch (RuntimeException var5) {
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              Status.FAILED,
              "MCP tool failed: " + safeMessage(var5),
              Optional.empty(),
              Map.of("server", this.client.key(), "tool", this.specification.name())));
    }
  }

  private ToolResult toResult(ToolCall call, String output, boolean error) {
    String normalized = output != null && !output.isEmpty() ? output : "(no output)";
    Map<String, Object> metadata =
        Map.of("server", this.client.key(), "tool", this.specification.name());
    if (error) {
      return new ToolResult(
          call.toolCallId(), Status.FAILED, normalized, Optional.empty(), metadata);
    } else if (normalized.getBytes(StandardCharsets.UTF_8).length <= this.inlineByteLimit) {
      return new ToolResult(
          call.toolCallId(), Status.COMPLETED, normalized, Optional.empty(), metadata);
    } else {
      String reference = this.resultStore.put(normalized);
      String preview =
          normalized.substring(0, Math.min(normalized.length(), this.inlineByteLimit / 2));
      return new ToolResult(
          call.toolCallId(),
          Status.COMPLETED,
          preview + "\n… MCP output truncated; full result: " + reference,
          Optional.of(reference),
          Map.of(
              "server",
              this.client.key(),
              "tool",
              this.specification.name(),
              "truncated",
              true,
              "totalBytes",
              normalized.getBytes(StandardCharsets.UTF_8).length));
    }
  }

  private static String inputSchema(ToolSpecification specification) {
    try {
      JsonNode root = JSON.readTree(specification.toJson());
      JsonNode parameters = root.path("parameters");
      return !parameters.isMissingNode() && !parameters.isNull()
          ? parameters.toString()
          : "{\"type\":\"object\"}";
    } catch (JsonProcessingException var3) {
      throw new IllegalArgumentException("invalid MCP tool schema", var3);
    }
  }

  private static String description(ToolSpecification specification) {
    String value = specification.description();
    return value != null && !value.isBlank() ? value : "MCP tool " + specification.name();
  }

  private static String safeMessage(RuntimeException exception) {
    return exception.getMessage() == null
        ? exception.getClass().getSimpleName()
        : exception.getMessage();
  }
}
