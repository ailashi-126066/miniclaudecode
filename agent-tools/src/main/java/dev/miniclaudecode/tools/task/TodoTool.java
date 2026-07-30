package dev.miniclaudecode.tools.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolResult;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

public final class TodoTool implements AgentTool {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "task",
          "todo",
          "List or replace the current session's concise execution checklist",
          "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"list\",\"replace\"]},\"items\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},\"status\":{\"type\":\"string\",\"enum\":[\"todo\",\"in_progress\",\"done\"]}},\"required\":[\"id\",\"content\",\"status\"]}}},\"required\":[\"action\"]}",
          RiskLevel.LOW);
  private final Map<SessionId, List<TodoTool.TodoItem>> sessions = new ConcurrentHashMap<>();
  private final Map<SessionId, Integer> successfulVerifications = new ConcurrentHashMap<>();

  /** Called only by the executor after a successful recognized verification command. */
  public void recordSuccessfulVerification(SessionId sessionId) {
    this.successfulVerifications.merge(Objects.requireNonNull(sessionId), 1, Integer::sum);
  }

  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      JsonNode arguments = JSON.readTree(call.argumentsJson());
      String action = requiredText(arguments, "action").toLowerCase(Locale.ROOT);
      List<TodoTool.TodoItem> items;
      if (action.equals("list")) {
        items = this.sessions.getOrDefault(context.sessionId(), List.of());
      } else {
        if (!action.equals("replace")) {
          throw new IllegalArgumentException("action must be list or replace");
        }

        items =
            parseItems(
                arguments.path("items"),
                this.successfulVerifications.getOrDefault(context.sessionId(), 0),
                Boolean.TRUE.equals(context.attributes().get("requireVerifiedTodo")));
        this.sessions.put(context.sessionId(), items);
        context
            .eventSink()
            .emit(
                AgentEvent.create(
                    context.sessionId(),
                    context.turnId(),
                    AgentEventType.TASK_UPDATED,
                    Map.of("items", serialize(items)),
                    Clock.systemUTC()));
      }

      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              dev.miniclaudecode.domain.tool.ToolResult.Status.COMPLETED,
              render(items),
              Optional.empty(),
              Map.of(
                  "items",
                  items.size(),
                  "completed",
                  items.stream().filter(item -> item.status() == TodoTool.Status.DONE).count())));
    } catch (RuntimeException | JsonProcessingException var6) {
      String message =
          var6.getMessage() == null ? var6.getClass().getSimpleName() : var6.getMessage();
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              dev.miniclaudecode.domain.tool.ToolResult.Status.FAILED,
              "todo update failed: " + message,
              Optional.empty(),
              Map.of()));
    }
  }

  public List<TodoTool.TodoItem> items(SessionId sessionId) {
    return this.sessions.getOrDefault(Objects.requireNonNull(sessionId), List.of());
  }

  public void restore(SessionId sessionId, List<TodoTool.TodoItem> items) {
    this.sessions.put(Objects.requireNonNull(sessionId), List.copyOf(items));
  }

  private static List<Map<String, String>> serialize(List<TodoTool.TodoItem> items) {
    return items.stream()
        .map(
            item ->
                Map.of(
                    "id",
                    item.id(),
                    "content",
                    item.content(),
                    "status",
                    item.status().name().toLowerCase(Locale.ROOT)))
        .toList();
  }

  private static List<TodoTool.TodoItem> parseItems(
      JsonNode node, int successfulVerifications, boolean requireSuccessfulVerification) {
    if (!node.isArray()) {
      throw new IllegalArgumentException("items must be an array for replace");
    } else {
      List<TodoTool.TodoItem> items = new ArrayList<>();
      Set<String> identifiers = new HashSet<>();
      int inProgress = 0;

      for (JsonNode item : node) {
        String id = requiredText(item, "id");
        String content = requiredText(item, "content");
        String verification = optionalText(item, "verification");

        TodoTool.Status status;
        try {
          status = TodoTool.Status.valueOf(requiredText(item, "status").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException var10) {
          throw new IllegalArgumentException(
              "todo status must be todo, in_progress, or done", var10);
        }

        if (!identifiers.add(id)) {
          throw new IllegalArgumentException("duplicate todo id: " + id);
        }

        if (status == TodoTool.Status.IN_PROGRESS) {
          if (++inProgress > 1) {
            throw new IllegalArgumentException("only one todo item may be in_progress");
          }
        }
        if (status == TodoTool.Status.DONE && verification.isBlank()) {
          throw new IllegalArgumentException("done todo items require a verification criterion");
        }
        if (status == TodoTool.Status.DONE
            && requireSuccessfulVerification
            && successfulVerifications < 1) {
          throw new IllegalArgumentException(
              "done todo items require a successful verification command");
        }

        items.add(new TodoTool.TodoItem(id, content, verification, status));
      }

      if (items.size() > 100) {
        throw new IllegalArgumentException("todo list cannot exceed 100 items");
      } else {
        return List.copyOf(items);
      }
    }
  }

  private static String render(List<TodoTool.TodoItem> items) {
    return items.isEmpty()
        ? "No todo items."
        : items.stream()
            .map(
                item ->
                    marker(item.status())
                        + " "
                        + item.id()
                        + " - "
                        + item.content()
                        + (item.verification().isBlank()
                            ? ""
                            : " [verify: " + item.verification() + "]"))
            .reduce((left, right) -> left + "\n" + right)
            .orElseThrow();
  }

  private static String marker(TodoTool.Status status) {
    return switch (status) {
      case TODO -> "[ ]";
      case IN_PROGRESS -> "[>]";
      case DONE -> "[x]";
    };
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isTextual() && !value.asText().isBlank()) {
      return value.asText().trim();
    } else {
      throw new IllegalArgumentException(field + " must be non-blank text");
    }
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isTextual() ? value.asText().trim() : "";
  }

  public static enum Status {
    TODO,
    IN_PROGRESS,
    DONE;
  }

  public static record TodoItem(
      String id, String content, String verification, TodoTool.Status status) {
    public TodoItem(String id, String content, TodoTool.Status status) {
      this(id, content, "", status);
    }
  }
}
