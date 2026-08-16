package dev.miniclaudecode.cli.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.miniclaudecode.cli.app.BackgroundAgentManager.Mode;
import dev.miniclaudecode.cli.app.BackgroundAgentManager.TaskSnapshot;
import dev.miniclaudecode.cli.app.BackgroundAgentManager.TaskSpec;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolEffect;
import dev.miniclaudecode.domain.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Starts and controls non-blocking subagents that reuse the normal AgentLoop. */
final class BackgroundAgentTool implements AgentTool {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "agent",
          "background",
          "Start, list, inspect, wait for, cancel, or retrieve a background Agent task",
          """
          {"type":"object","properties":{
            "action":{"type":"string","enum":["start","list","status","wait","cancel","result"]},
            "taskId":{"type":"string"},"task":{"type":"string"},
            "role":{"type":"string","enum":["explore","review","plan","implement"]},
            "mode":{"type":"string","enum":["isolated","fork"]},
            "context":{"type":"string"},
            "maxModelSteps":{"type":"integer","minimum":1,"maximum":8},
            "timeoutMillis":{"type":"integer","minimum":1,"maximum":60000}
          },"required":["action"]}
          """,
          RiskLevel.LOW,
          ToolEffect.PROCESS);

  private final BackgroundAgentManager manager;

  BackgroundAgentTool(BackgroundAgentManager manager) {
    this.manager = Objects.requireNonNull(manager);
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      JsonNode root = JSON.readTree(call.argumentsJson());
      String action = root.path("action").asText("").strip().toLowerCase(java.util.Locale.ROOT);
      return CompletableFuture.completedFuture(
          switch (action) {
            case "start" -> start(call, context, root);
            case "list" ->
                completed(call, render(manager.list(context.sessionId().value())), Map.of());
            case "status" -> snapshot(call, manager.status(required(root, "taskId")));
            case "wait" ->
                snapshot(
                    call,
                    manager.waitFor(
                        required(root, "taskId"), root.path("timeoutMillis").asLong(30_000)));
            case "cancel" -> snapshot(call, manager.cancel(required(root, "taskId")));
            case "result" -> snapshot(call, manager.status(required(root, "taskId")));
            default -> failed(call, "unknown background action: " + action);
          });
    } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException failure) {
      return CompletableFuture.completedFuture(
          failed(
              call,
              "background agent operation failed: "
                  + Objects.requireNonNullElse(
                      failure.getMessage(), failure.getClass().getSimpleName())));
    }
  }

  private ToolResult start(ToolCall call, ToolContext context, JsonNode root) {
    String mode = root.path("mode").asText("isolated");
    TaskSpec spec =
        new TaskSpec(
            required(root, "task"),
            root.path("role").asText("explore"),
            root.path("maxModelSteps").asInt(5),
            "fork".equalsIgnoreCase(mode) ? Mode.FORK : Mode.ISOLATED,
            root.path("context").asText(""),
            0);
    String id = manager.start(spec, context);
    return completed(
        call,
        "Background Agent started: " + id,
        Map.of("backgroundTaskId", id, "backgroundAgents", List.of(id + " QUEUED")));
  }

  private static ToolResult snapshot(ToolCall call, TaskSnapshot snapshot) {
    String output =
        snapshot.id()
            + " ["
            + snapshot.status()
            + "]\nTask: "
            + snapshot.task()
            + (snapshot.resultSummary().isBlank() ? "" : "\nResult: " + snapshot.resultSummary())
            + snapshot.resultReference().map(value -> "\nReference: " + value).orElse("");
    return completed(
        call,
        output,
        Map.of(
            "backgroundTaskId",
            snapshot.id(),
            "backgroundAgents",
            List.of(snapshot.id() + " " + snapshot.status())));
  }

  private static String render(List<TaskSnapshot> tasks) {
    if (tasks.isEmpty()) return "(no background agents)";
    return tasks.stream()
        .map(
            value ->
                value.id() + " [" + value.status() + "] " + value.role() + " - " + value.task())
        .reduce((left, right) -> left + "\n" + right)
        .orElseThrow();
  }

  private static ToolResult completed(ToolCall call, String output, Map<String, Object> metadata) {
    return new ToolResult(
        call.toolCallId(), ToolResult.Status.COMPLETED, output, Optional.empty(), metadata);
  }

  private static ToolResult failed(ToolCall call, String message) {
    return new ToolResult(
        call.toolCallId(), ToolResult.Status.FAILED, message, Optional.empty(), Map.of());
  }

  private static String required(JsonNode root, String field) {
    String value = root.path(field).asText("").strip();
    if (value.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
    return value;
  }
}
