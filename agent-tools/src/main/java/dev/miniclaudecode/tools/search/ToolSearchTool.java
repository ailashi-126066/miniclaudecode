package dev.miniclaudecode.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolEffect;
import dev.miniclaudecode.domain.tool.ToolExposure;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.tools.registry.DeferredToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Searches deferred tools by summary and explicitly selects schemas for the current session. */
public final class ToolSearchTool implements AgentTool {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "system",
          "tool_search",
          "Search deferred tools; pass exact qualified names in select to expose their full schemas",
          "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"select\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}},\"required\":[\"query\"]}",
          RiskLevel.LOW,
          ToolEffect.READ_ONLY_LOCAL,
          ToolExposure.EAGER,
          Set.of("tools", "search", "discovery", "schema"),
          "Find capabilities and select deferred tool schemas");

  private final DeferredToolRegistry registry;

  public ToolSearchTool(DeferredToolRegistry registry) {
    this.registry = java.util.Objects.requireNonNull(registry);
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      JsonNode root = MAPPER.readTree(call.argumentsJson());
      String query = root.path("query").asText("").strip();
      int limit = root.path("limit").asInt(8);
      List<String> selections = new ArrayList<>();
      JsonNode select = root.path("select");
      if (select.isArray()) {
        select.forEach(value -> selections.add(value.asText()));
      }
      List<ToolDescriptor> discovered =
          selections.isEmpty() ? List.of() : registry.discover(context.sessionId(), selections);
      List<Map<String, Object>> hits =
          registry.search(query, limit).stream()
              .map(
                  hit -> {
                    ToolDescriptor descriptor = hit.descriptor();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", descriptor.qualifiedName());
                    item.put("summary", descriptor.summary());
                    item.put("tags", descriptor.tags());
                    item.put("effect", descriptor.effect().name());
                    item.put("score", hit.score());
                    return Map.copyOf(item);
                  })
              .toList();
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("matches", hits);
      response.put("selected", discovered.stream().map(ToolDescriptor::qualifiedName).toList());
      response.put(
          "next",
          discovered.isEmpty()
              ? "Call system:tool_search again with select=[qualified names] to expose schemas"
              : "Selected schemas are available on the next model step");
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("toolSearch", true);
      metadata.put(
          "discoveredTools", discovered.stream().map(ToolDescriptor::qualifiedName).toList());
      metadata.put("discoveredToolDescriptors", discovered);
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.COMPLETED,
              MAPPER.writeValueAsString(response),
              Optional.empty(),
              Map.copyOf(metadata)));
    } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException error) {
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.FAILED,
              "tool search failed: "
                  + java.util.Objects.requireNonNullElse(
                      error.getMessage(), error.getClass().getSimpleName()),
              Optional.empty(),
              Map.of("toolSearch", true)));
    }
  }
}
