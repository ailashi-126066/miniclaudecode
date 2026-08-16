package dev.miniclaudecode.cli.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Bounded virtual-thread scheduler with persisted task snapshots and parent-session notifications.
 */
final class BackgroundAgentManager implements AutoCloseable {
  static final int MAX_CONCURRENT = 4;
  static final int MAX_TASKS = 64;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final TaskRunner runner;
  private final ToolResultStore resultStore;
  private final Path storeFile;
  private final Clock clock;
  private final ExecutorService executor =
      Executors.newFixedThreadPool(
          MAX_CONCURRENT, Thread.ofVirtual().name("miniclaude-background-", 0).factory());
  private final Map<String, TaskControl> tasks = new ConcurrentHashMap<>();
  private final Map<String, List<String>> notifications = new ConcurrentHashMap<>();

  BackgroundAgentManager(
      TaskRunner runner, ToolResultStore resultStore, Path storeFile, Clock clock) {
    this.runner = Objects.requireNonNull(runner);
    this.resultStore = Objects.requireNonNull(resultStore);
    this.storeFile = Objects.requireNonNull(storeFile).toAbsolutePath().normalize();
    this.clock = Objects.requireNonNull(clock);
    restore();
  }

  String start(TaskSpec spec, ToolContext parent) {
    Objects.requireNonNull(spec);
    Objects.requireNonNull(parent);
    if (spec.depth() > 1) throw new IllegalArgumentException("subagent fork depth exceeds 1");
    long active =
        tasks.values().stream().filter(task -> !task.snapshot.status().terminal()).count();
    if (active >= MAX_TASKS) throw new IllegalStateException("background task limit reached");
    String id = "bg-" + UUID.randomUUID().toString().substring(0, 12);
    Instant created = Instant.now(clock);
    TaskSnapshot queued =
        new TaskSnapshot(
            id,
            parent.sessionId().value(),
            parent.turnId().value(),
            spec.task(),
            spec.role(),
            spec.mode(),
            TaskStatus.QUEUED,
            created,
            Optional.empty(),
            Optional.empty(),
            "",
            Optional.empty(),
            "",
            0,
            0);
    TaskControl control = new TaskControl(queued, new CancellationToken());
    tasks.put(id, control);
    persist();
    emit(parent, AgentEventType.BACKGROUND_STARTED, queued);
    control.future =
        CompletableFuture.supplyAsync(() -> run(control, spec, parent), executor)
            .exceptionally(
                failure -> {
                  completeFailure(control, failure);
                  return control.snapshot;
                });
    return id;
  }

  List<TaskSnapshot> list(String parentSession) {
    return tasks.values().stream()
        .map(control -> control.snapshot)
        .filter(snapshot -> parentSession == null || snapshot.parentSession().equals(parentSession))
        .sorted(Comparator.comparing(TaskSnapshot::createdAt))
        .toList();
  }

  TaskSnapshot status(String id) {
    return require(id).snapshot;
  }

  TaskSnapshot waitFor(String id, long timeoutMillis) {
    TaskControl control = require(id);
    CompletableFuture<TaskSnapshot> future = control.future;
    if (future == null || control.snapshot.status().terminal()) return control.snapshot;
    try {
      return future.get(Math.max(1, Math.min(timeoutMillis, 60_000)), TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException timeout) {
      return control.snapshot;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return control.snapshot;
    } catch (java.util.concurrent.ExecutionException failed) {
      return control.snapshot;
    }
  }

  TaskSnapshot cancel(String id) {
    TaskControl control = require(id);
    if (!control.snapshot.status().terminal()) {
      control.cancellation.cancel();
      update(
          control,
          control.snapshot.withTerminal(
              TaskStatus.CANCELLED,
              "cancelled by parent",
              Optional.empty(),
              Instant.now(clock),
              0,
              0));
    }
    return control.snapshot;
  }

  List<String> drainNotifications(String sessionId) {
    List<String> values = notifications.remove(sessionId);
    return values == null ? List.of() : List.copyOf(values);
  }

  String render(String sessionId) {
    List<TaskSnapshot> values = list(sessionId);
    if (values.isEmpty()) return "(no background agents)";
    return values.stream()
        .map(
            value ->
                value.id()
                    + " ["
                    + value.status()
                    + "] "
                    + value.role()
                    + " - "
                    + abbreviate(value.task(), 100))
        .reduce((left, right) -> left + System.lineSeparator() + right)
        .orElseThrow();
  }

  private TaskSnapshot run(TaskControl control, TaskSpec spec, ToolContext parent) {
    update(control, control.snapshot.withStarted(Instant.now(clock)));
    RunResult result = runner.run(spec, parent, control.cancellation);
    TaskStatus status =
        control.cancellation.isCancellationRequested()
            ? TaskStatus.CANCELLED
            : switch (result.status()) {
              case COMPLETED -> TaskStatus.COMPLETED;
              case CANCELLED -> TaskStatus.CANCELLED;
              default -> TaskStatus.FAILED;
            };
    String output = Objects.requireNonNullElse(result.output(), "");
    Optional<String> reference = Optional.empty();
    String summary = output;
    if (output.length() > 6_000) {
      reference = Optional.of(resultStore.put(output));
      summary = abbreviate(output, 6_000) + "\nFull result: " + reference.orElseThrow();
    }
    TaskSnapshot completed =
        control.snapshot.withTerminal(
            status,
            summary,
            reference,
            Instant.now(clock),
            result.modelSteps(),
            result.toolSteps());
    update(control, completed);
    AgentEventType eventType =
        switch (status) {
          case COMPLETED -> AgentEventType.BACKGROUND_COMPLETED;
          case CANCELLED -> AgentEventType.BACKGROUND_CANCELLED;
          default -> AgentEventType.BACKGROUND_FAILED;
        };
    emit(parent, eventType, completed);
    notifications
        .computeIfAbsent(completed.parentSession(), ignored -> new CopyOnWriteArrayList<>())
        .add(
            completed.id()
                + " "
                + completed.status()
                + ": "
                + abbreviate(completed.resultSummary(), 240));
    return completed;
  }

  private void completeFailure(TaskControl control, Throwable failure) {
    update(
        control,
        control.snapshot.withTerminal(
            TaskStatus.FAILED,
            Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName()),
            Optional.empty(),
            Instant.now(clock),
            0,
            0));
  }

  private synchronized void update(TaskControl control, TaskSnapshot snapshot) {
    control.snapshot = snapshot;
    persist();
  }

  private TaskControl require(String id) {
    TaskControl value = tasks.get(Objects.requireNonNullElse(id, "").strip());
    if (value == null) throw new IllegalArgumentException("unknown background task: " + id);
    return value;
  }

  private void emit(ToolContext context, AgentEventType type, TaskSnapshot snapshot) {
    context
        .eventSink()
        .emit(
            AgentEvent.create(
                context.sessionId(),
                context.turnId(),
                type,
                Map.of(
                    "taskId", snapshot.id(),
                    "status", snapshot.status().name(),
                    "role", snapshot.role(),
                    "mode", snapshot.mode().name(),
                    "summary", abbreviate(snapshot.resultSummary(), 500)),
                clock));
  }

  private synchronized void persist() {
    try {
      Path parent = Objects.requireNonNull(storeFile.getParent(), "store file must have a parent");
      Files.createDirectories(parent);
      List<Map<String, Object>> values =
          list(null).stream().map(BackgroundAgentManager::map).toList();
      Path temporary = Files.createTempFile(parent, ".background-", ".tmp");
      Files.writeString(temporary, JSON.writeValueAsString(values), StandardCharsets.UTF_8);
      Files.move(temporary, storeFile, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot persist background agent state", failure);
    }
  }

  private void restore() {
    if (!Files.isRegularFile(storeFile)) return;
    try {
      JsonNode root = JSON.readTree(Files.readString(storeFile, StandardCharsets.UTF_8));
      if (!root.isArray()) return;
      for (JsonNode value : root) {
        TaskSnapshot snapshot = parse(value);
        if (!snapshot.status().terminal()) {
          snapshot =
              snapshot.withTerminal(
                  TaskStatus.INTERRUPTED,
                  "application stopped before task completion",
                  snapshot.resultReference(),
                  Instant.now(clock),
                  snapshot.modelSteps(),
                  snapshot.toolSteps());
        }
        tasks.put(snapshot.id(), new TaskControl(snapshot, new CancellationToken()));
      }
      persist();
    } catch (IOException | RuntimeException failure) {
      throw new IllegalStateException("cannot restore background agent state", failure);
    }
  }

  private static Map<String, Object> map(TaskSnapshot value) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", value.id());
    map.put("parentSession", value.parentSession());
    map.put("parentTurn", value.parentTurn());
    map.put("task", value.task());
    map.put("role", value.role());
    map.put("mode", value.mode().name());
    map.put("status", value.status().name());
    map.put("createdAt", value.createdAt().toString());
    value.startedAt().ifPresent(item -> map.put("startedAt", item.toString()));
    value.finishedAt().ifPresent(item -> map.put("finishedAt", item.toString()));
    map.put("resultSummary", value.resultSummary());
    value.resultReference().ifPresent(item -> map.put("resultReference", item));
    map.put("error", value.error());
    map.put("modelSteps", value.modelSteps());
    map.put("toolSteps", value.toolSteps());
    return Map.copyOf(map);
  }

  private static TaskSnapshot parse(JsonNode value) {
    return new TaskSnapshot(
        value.path("id").asText(),
        value.path("parentSession").asText(),
        value.path("parentTurn").asLong(),
        value.path("task").asText(),
        value.path("role").asText(),
        Mode.valueOf(value.path("mode").asText("ISOLATED")),
        TaskStatus.valueOf(value.path("status").asText("INTERRUPTED")),
        Instant.parse(value.path("createdAt").asText()),
        instant(value, "startedAt"),
        instant(value, "finishedAt"),
        value.path("resultSummary").asText(""),
        text(value, "resultReference"),
        value.path("error").asText(""),
        value.path("modelSteps").asInt(),
        value.path("toolSteps").asInt());
  }

  private static Optional<Instant> instant(JsonNode value, String name) {
    return value.hasNonNull(name)
        ? Optional.of(Instant.parse(value.path(name).asText()))
        : Optional.empty();
  }

  private static Optional<String> text(JsonNode value, String name) {
    return value.hasNonNull(name) ? Optional.of(value.path(name).asText()) : Optional.empty();
  }

  private static String abbreviate(String value, int maximum) {
    String normalized = Objects.requireNonNullElse(value, "").strip();
    return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum) + "...";
  }

  @Override
  public void close() {
    tasks.values().stream()
        .filter(task -> !task.snapshot.status().terminal())
        .forEach(task -> task.cancellation.cancel());
    executor.shutdown();
    try {
      if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
    synchronized (this) {
      tasks.values().stream()
          .filter(task -> !task.snapshot.status().terminal())
          .forEach(
              task ->
                  task.snapshot =
                      task.snapshot.withTerminal(
                          TaskStatus.INTERRUPTED,
                          "application stopped before task completion",
                          task.snapshot.resultReference(),
                          Instant.now(clock),
                          task.snapshot.modelSteps(),
                          task.snapshot.toolSteps()));
      persist();
    }
  }

  enum Mode {
    ISOLATED,
    FORK
  }

  enum TaskStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED;

    boolean terminal() {
      return this == COMPLETED || this == FAILED || this == CANCELLED || this == INTERRUPTED;
    }
  }

  record TaskSpec(
      String task, String role, int maxModelSteps, Mode mode, String forkContext, int depth) {
    TaskSpec {
      task = requireText(task, "task");
      role = requireText(role, "role").toLowerCase(java.util.Locale.ROOT);
      if (!List.of("explore", "review", "plan", "implement").contains(role)) {
        throw new IllegalArgumentException("unsupported background role: " + role);
      }
      if (maxModelSteps < 1 || maxModelSteps > 8) {
        throw new IllegalArgumentException("maxModelSteps must be between 1 and 8");
      }
      mode = Objects.requireNonNull(mode);
      forkContext = Objects.requireNonNullElse(forkContext, "").strip();
      if (depth < 0) throw new IllegalArgumentException("depth must not be negative");
    }
  }

  record RunResult(AgentStatus status, String output, int modelSteps, int toolSteps) {}

  record TaskSnapshot(
      String id,
      String parentSession,
      long parentTurn,
      String task,
      String role,
      Mode mode,
      TaskStatus status,
      Instant createdAt,
      Optional<Instant> startedAt,
      Optional<Instant> finishedAt,
      String resultSummary,
      Optional<String> resultReference,
      String error,
      int modelSteps,
      int toolSteps) {
    TaskSnapshot withStarted(Instant value) {
      return new TaskSnapshot(
          id,
          parentSession,
          parentTurn,
          task,
          role,
          mode,
          TaskStatus.RUNNING,
          createdAt,
          Optional.of(value),
          Optional.empty(),
          resultSummary,
          resultReference,
          error,
          modelSteps,
          toolSteps);
    }

    TaskSnapshot withTerminal(
        TaskStatus value,
        String output,
        Optional<String> reference,
        Instant finished,
        int modelCount,
        int toolCount) {
      return new TaskSnapshot(
          id,
          parentSession,
          parentTurn,
          task,
          role,
          mode,
          value,
          createdAt,
          startedAt,
          Optional.of(finished),
          output,
          reference,
          value == TaskStatus.FAILED ? output : "",
          modelCount,
          toolCount);
    }
  }

  @FunctionalInterface
  interface TaskRunner {
    RunResult run(TaskSpec spec, ToolContext parent, CancellationToken cancellation);
  }

  private static final class TaskControl {
    private volatile TaskSnapshot snapshot;
    private final CancellationToken cancellation;
    private volatile CompletableFuture<TaskSnapshot> future;

    private TaskControl(TaskSnapshot snapshot, CancellationToken cancellation) {
      this.snapshot = snapshot;
      this.cancellation = cancellation;
    }
  }

  private static String requireText(String value, String field) {
    value = Objects.requireNonNullElse(value, "").strip();
    if (value.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
    return value;
  }
}
