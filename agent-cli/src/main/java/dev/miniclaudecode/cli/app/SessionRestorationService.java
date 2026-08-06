package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.AssistantMessage;
import dev.miniclaudecode.domain.message.AgentMessage.SystemMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.tools.task.TodoTool.Status;
import dev.miniclaudecode.tools.task.TodoTool.TodoItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Reconstructs durable session state without mutating the active ApplicationSession. */
final class SessionRestorationService {

  RestoredSession restore(SessionId sessionId, List<AgentEvent> events, String systemPrompt) {
    List<AgentMessage> messages = new ArrayList<>();
    messages.add(new SystemMessage(systemPrompt));
    long maximumTurn = 0L;
    for (AgentEvent event : events) {
      maximumTurn = Math.max(maximumTurn, event.turnId().value());
      Object text = event.payload().get("text");
      if (text instanceof String content && !content.isBlank()) {
        if (event.type() == AgentEventType.USER_MESSAGE) {
          messages.add(new UserMessage(content));
        } else if (event.type() == AgentEventType.TURN_FINAL) {
          messages.add(new AssistantMessage(content, Optional.empty(), Map.of()));
        }
      }
    }
    return new RestoredSession(
        maximumTurn + 1L,
        List.copyOf(messages),
        restoreTasks(events),
        restoreApproval(sessionId, events),
        restoreProgress(events));
  }

  private static List<TodoItem> restoreTasks(List<AgentEvent> events) {
    List<TodoItem> restored = List.of();
    for (AgentEvent event : events) {
      if (event.type() != AgentEventType.TASK_UPDATED) {
        continue;
      }
      Object rawItems = event.payload().get("items");
      if (!(rawItems instanceof List<?> values)) {
        continue;
      }
      List<TodoItem> parsed = new ArrayList<>();
      for (Object value : values) {
        if (value instanceof Map<?, ?> item) {
          try {
            parsed.add(
                new TodoItem(
                    String.valueOf(item.get("id")),
                    String.valueOf(item.get("content")),
                    Status.valueOf(String.valueOf(item.get("status")).toUpperCase())));
          } catch (IllegalArgumentException ignored) {
            // Preserve the remaining valid task snapshot when one durable item is malformed.
          }
        }
      }
      restored = List.copyOf(parsed);
    }
    return restored;
  }

  private static Optional<PendingApproval> restoreApproval(
      SessionId sessionId, List<AgentEvent> events) {
    AgentEvent pending = null;
    for (AgentEvent event : events) {
      if (event.type() == AgentEventType.APPROVAL_REQUESTED) {
        pending = event;
      } else if (event.type() == AgentEventType.APPROVAL_RESOLVED
          || event.type() == AgentEventType.TURN_FINAL) {
        pending = null;
      }
    }
    if (pending == null || !pending.payload().containsKey("approvalId")) {
      return Optional.empty();
    }
    Map<String, Object> payload = pending.payload();
    ToolCall call =
        new ToolCall(
            String.valueOf(payload.get("toolCallId")),
            String.valueOf(payload.get("tool")),
            String.valueOf(payload.get("arguments")));
    ApprovalRequest request =
        new ApprovalRequest(
            UUID.fromString(String.valueOf(payload.get("approvalId"))),
            call,
            RiskLevel.valueOf(String.valueOf(payload.get("risk"))),
            String.valueOf(payload.get("target")),
            String.valueOf(payload.get("reason")),
            Optional.ofNullable(payload.get("beforeHash")).map(String::valueOf),
            Optional.ofNullable(payload.get("diffHash")).map(String::valueOf),
            Instant.parse(String.valueOf(payload.get("requestedAt"))));
    String preview = Optional.ofNullable(payload.get("preview")).map(String::valueOf).orElse(null);
    TurnId turn = pending.turnId();
    SessionId graphThread = SessionId.of(sessionId.value() + "-turn-" + turn.value());
    return Optional.of(new PendingApproval(request, preview, turn, graphThread));
  }

  private static RestoredProgress restoreProgress(List<AgentEvent> events) {
    RestoredProgress restored = RestoredProgress.empty();
    for (AgentEvent event : events) {
      if (event.type() == AgentEventType.COMPACTION) {
        restored =
            new RestoredProgress(
                "compaction",
                number(event.payload().get("afterEstimatedTokens")),
                number(event.payload().get("inputBudgetTokens")),
                number(event.payload().get("compactionCount")));
      } else if (event.type() == AgentEventType.TURN_STAGE) {
        restored =
            new RestoredProgress(
                String.valueOf(event.payload().getOrDefault("phase", "restored")),
                number(event.payload().get("estimatedInputTokens")),
                number(event.payload().get("inputBudgetTokens")),
                number(event.payload().get("compactionCount")));
      }
    }
    return restored;
  }

  private static int number(Object value) {
    return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
  }

  record RestoredSession(
      long nextTurn,
      List<AgentMessage> messages,
      List<TodoItem> tasks,
      Optional<PendingApproval> pendingApproval,
      RestoredProgress progress) {}

  record PendingApproval(
      ApprovalRequest request, String preview, TurnId turn, SessionId graphThread) {}

  record RestoredProgress(
      String phase, int estimatedTokens, int inputBudgetTokens, int compactionCount) {
    static RestoredProgress empty() {
      return new RestoredProgress("restored", 0, 0, 0);
    }
  }
}
