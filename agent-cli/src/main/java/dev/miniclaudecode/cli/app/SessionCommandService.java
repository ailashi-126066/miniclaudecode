package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.cli.commands.SlashCommand;
import dev.miniclaudecode.cli.tui.TuiDashboard;
import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.persistence.memory.AceBullet;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** Session-scoped command queries and management operations, separate from turn execution. */
final class SessionCommandService {
  private final WorkspaceComponents components;
  private final SessionAuditService audit;
  private final SessionUsageStats usage;
  private final GitCheckpointService checkpoints;

  SessionCommandService(
      WorkspaceComponents components,
      SessionAuditService audit,
      SessionUsageStats usage,
      GitCheckpointService checkpoints) {
    this.components = Objects.requireNonNull(components);
    this.audit = Objects.requireNonNull(audit);
    this.usage = Objects.requireNonNull(usage);
    this.checkpoints = Objects.requireNonNull(checkpoints);
  }

  String plan(SessionId sessionId, SlashCommand.PlanView command) {
    List<AgentEvent> events = planEvents(sessionId);
    if ("history".equals(command.action())) {
      if (events.isEmpty()) return "No Plan events for this session.";
      return events.stream()
          .map(
              event ->
                  event.occurredAt()
                      + " "
                      + event.type()
                      + " plan="
                      + event.payload().getOrDefault("planId", "?")
                      + " version="
                      + event.payload().getOrDefault("version", "?"))
          .reduce((left, right) -> left + System.lineSeparator() + right)
          .orElseThrow();
    }
    if ("evidence".equals(command.action())) {
      String stepId = command.stepId().orElseThrow();
      for (int index = events.size() - 1; index >= 0; index--) {
        Object rawSteps = events.get(index).payload().get("steps");
        if (rawSteps instanceof List<?> steps) {
          for (Object value : steps) {
            if (value instanceof Map<?, ?> step && stepId.equals(String.valueOf(step.get("id")))) {
              Object evidence = step.get("evidence");
              return evidence == null
                  ? "No evidence recorded for Plan step " + stepId
                  : "Evidence for " + stepId + ":\n" + evidence;
            }
          }
        }
      }
      return "Unknown Plan step: " + stepId;
    }
    return events.isEmpty()
        ? "No Plan is available for this session."
        : renderPlan(events.getLast().payload());
  }

  String status(
      SessionId sessionId,
      String state,
      long nextTurn,
      String taskSummary,
      String phase,
      int estimatedTokens,
      int inputBudgetTokens,
      int compactionCount,
      String lastVerification,
      String lastCheckpoint) {
    String budget = inputBudgetTokens > 0 ? "/" + inputBudgetTokens : "";
    String context =
        estimatedTokens <= 0
            ? "not estimated"
            : estimatedTokens + budget + " estimated tokens; compactions=" + compactionCount;
    return String.join(
        System.lineSeparator(),
        "Session: " + sessionId.value(),
        "State: " + state,
        "Turn: " + nextTurn,
        "Tasks: " + taskSummary,
        "Plan: " + currentPlanSummary(sessionId),
        "Phase: " + phase,
        "Context: " + context,
        "Last verification: " + lastVerification,
        "Checkpoint: " + lastCheckpoint);
  }

  String memory(SlashCommand.Memory command) {
    return switch (command.action()) {
      case "list" -> renderMemories(this.components.bullets().list());
      case "search" ->
          renderMemories(this.components.bullets().search(command.value().orElseThrow(), 20));
      case "archive" ->
          this.components.bullets().archive(command.value().orElseThrow())
              ? "Memory archived: " + command.value().orElseThrow()
              : "Unknown memory id: " + command.value().orElseThrow();
      case "edit" -> editMemory(command.value().orElseThrow());
      case "export" -> exportMemories(this.components.bullets().list());
      case "clear" -> {
        int archived = this.components.bullets().clear();
        yield "Archived " + archived + " active memory entr" + (archived == 1 ? "y." : "ies.");
      }
      default -> throw new IllegalArgumentException("unknown memory action: " + command.action());
    };
  }

  String currentPlanSummary(SessionId sessionId) {
    List<AgentEvent> events = planEvents(sessionId);
    if (events.isEmpty()) return "(none)";
    Map<String, Object> payload = events.getLast().payload();
    return payload.getOrDefault("status", "?")
        + " v"
        + payload.getOrDefault("version", "?")
        + " - "
        + payload.getOrDefault("goal", "");
  }

  String sessions() {
    if (!Files.isDirectory(this.audit.eventsRoot())) return "(none)";
    try (Stream<java.nio.file.Path> files = Files.list(this.audit.eventsRoot())) {
      return files
          .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
          .map(path -> path.getFileName().toString().replaceFirst("\\.jsonl$", ""))
          .sorted()
          .reduce((left, right) -> left + System.lineSeparator() + right)
          .orElse("(none)");
    } catch (IOException failure) {
      return "Cannot list sessions: " + failure.getMessage();
    }
  }

  String usage(SessionId sessionId) {
    refreshUsage(sessionId);
    return this.usage.summary();
  }

  String background(SessionId sessionId) {
    List<String> notifications = this.components.background().drainNotifications(sessionId.value());
    String tasks = this.components.background().render(sessionId.value());
    return notifications.isEmpty()
        ? tasks
        : "Notifications:\n- " + String.join("\n- ", notifications) + "\n\nTasks:\n" + tasks;
  }

  String teams() {
    return this.components.teams().renderAll();
  }

  TuiDashboard dashboard(SessionId sessionId, String phase) {
    refreshUsage(sessionId);
    SessionUsageStats.Snapshot totals = this.usage.snapshot();
    return new TuiDashboard(
        sessionId.value() + " / " + phase,
        currentPlanSummary(sessionId),
        totals.requests()
            + " req, "
            + totals.inputTokens()
            + " in, "
            + totals.outputTokens()
            + " out",
        this.components.background().render(sessionId.value()),
        this.components.teams().renderAll());
  }

  String checkpoints() {
    return this.checkpoints.list();
  }

  String restoreCheckpoint(SlashCommand.Restore command) {
    return command.apply()
        ? this.checkpoints.restore(command.revision())
        : this.checkpoints.previewRestore(command.revision());
  }

  String undo() {
    return this.checkpoints.undo();
  }

  String redo() {
    return this.checkpoints.redo();
  }

  private void refreshUsage(SessionId sessionId) {
    this.usage.restore(this.audit.read(sessionId).events());
  }

  private String editMemory(String arguments) {
    int separator = arguments.indexOf(' ');
    if (separator < 1 || separator == arguments.length() - 1) {
      throw new IllegalArgumentException("usage: /memory edit <id> <content>");
    }
    String id = arguments.substring(0, separator);
    String content = arguments.substring(separator + 1).strip();
    return this.components.bullets().edit(id, content)
        ? "Memory updated: " + id
        : "Unknown memory id: " + id;
  }

  private List<AgentEvent> planEvents(SessionId sessionId) {
    return this.audit.read(sessionId).events().stream()
        .filter(event -> event.type().name().startsWith("PLAN_"))
        .toList();
  }

  private static String renderPlan(Map<String, Object> payload) {
    StringBuilder output = new StringBuilder();
    output
        .append("Plan ")
        .append(payload.getOrDefault("planId", "?"))
        .append(" [")
        .append(payload.getOrDefault("status", "?"))
        .append("] v")
        .append(payload.getOrDefault("version", "?"))
        .append(" revisions=")
        .append(payload.getOrDefault("revisions", 0))
        .append("\nGoal: ")
        .append(payload.getOrDefault("goal", ""));
    if (payload.get("steps") instanceof List<?> steps) {
      for (Object value : steps) {
        if (value instanceof Map<?, ?> step) {
          output
              .append("\n- [")
              .append(step.get("status"))
              .append("] ")
              .append(step.get("id"))
              .append(": ")
              .append(step.get("description"));
        }
      }
    }
    return output.toString();
  }

  private static String renderMemories(List<AceBullet> memories) {
    if (memories.isEmpty()) return "(none)";
    return memories.stream()
        .map(
            memory ->
                memory.id()
                    + " ["
                    + memory.state()
                    + "] confidence="
                    + String.format(java.util.Locale.ROOT, "%.2f", memory.confidence())
                    + "\n  trigger: "
                    + memory.trigger()
                    + "\n  lesson: "
                    + memory.lesson())
        .reduce((left, right) -> left + System.lineSeparator() + right)
        .orElseThrow();
  }

  private static String exportMemories(List<AceBullet> memories) {
    StringBuilder markdown = new StringBuilder("# MiniClaudeCode memory export\n");
    for (AceBullet memory : memories) {
      markdown
          .append("\n## ")
          .append(memory.id())
          .append("\n\n- State: ")
          .append(memory.state())
          .append("\n- Trigger: ")
          .append(memory.trigger())
          .append("\n- Lesson: ")
          .append(memory.lesson())
          .append("\n");
    }
    return markdown.toString().stripTrailing();
  }
}
