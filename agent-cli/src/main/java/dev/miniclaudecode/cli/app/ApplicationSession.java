package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.cli.Repl.TurnHandler;
import dev.miniclaudecode.cli.Repl.TurnOutcome;
import dev.miniclaudecode.cli.StreamingRenderer.RenderEvent;
import dev.miniclaudecode.cli.StreamingRenderer.RenderEvent.Completed;
import dev.miniclaudecode.cli.StreamingRenderer.RenderEvent.Error;
import dev.miniclaudecode.cli.StreamingRenderer.RenderEvent.Progress;
import dev.miniclaudecode.context.DeterministicContextReducer;
import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.SystemMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.domain.session.SessionEventStore.ReadResult;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.persistence.config.ProviderProfile;
import dev.miniclaudecode.prompt.DefaultCodingPromptContributors;
import dev.miniclaudecode.prompt.PromptBuildContext;
import dev.miniclaudecode.prompt.PromptPipeline;
import dev.miniclaudecode.runtime.AgentThreadRunner;
import dev.miniclaudecode.runtime.TurnProgressListener;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.tools.task.TodoTool.Status;
import dev.miniclaudecode.tools.task.TodoTool.TodoItem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

final class ApplicationSession implements TurnHandler {
  private final WorkspaceComponents components;
  private final SessionAuditService audit;
  private final Supplier<ApplicationSession.TurnSelection> selection;
  private final PromptPipeline promptPipeline;
  private final MemoryCoordinator memory;
  private final SessionRestorationService restoration = new SessionRestorationService();
  private final TurnCoordinator turns;
  private final GitCheckpointService checkpoints;
  private final SessionUsageStats usage = new SessionUsageStats();
  // Written only under the instance lock (start / switchTo), but read from both locked and
  // lock-free paths (emit() is reached from unsynchronized callers). volatile gives those reads
  // correct visibility of the latest committed id without pretending the field is lock-guarded.
  private volatile SessionId sessionId = SessionId.random();
  private long nextTurn = 1L;
  private List<AgentMessage> messages;
  private AgentThreadRunner activeRunner;
  private SessionId activeGraphThread;
  private TurnId activeTurn;
  private ApprovalRequest restoredApproval;
  private String restoredPreview;
  private String lastPhase = "not started";
  private int lastEstimatedTokens;
  private int lastInputBudgetTokens;
  private int lastCompactionCount;
  private String lastCheckpoint = "(none)";
  private String lastVerification = "not run";

  ApplicationSession(
      WorkspaceComponents components,
      Supplier<ApplicationSession.TurnSelection> selection,
      Clock clock) {
    this.components = Objects.requireNonNull(components, "components must not be null");
    this.selection = Objects.requireNonNull(selection, "selection must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    this.promptPipeline = new PromptPipeline(DefaultCodingPromptContributors.create());
    this.audit =
        new SessionAuditService(
            components.workspace(), components.layout(), components.secrets(), clock);
    this.memory = new MemoryCoordinator(components, audit, clock);
    this.turns = new TurnCoordinator(components, audit, clock);
    this.checkpoints = new GitCheckpointService(components.workspace());
    this.messages = List.of(new SystemMessage(this.systemPrompt()));
  }

  public CompletionStage<TurnOutcome> start(
      String prompt, CancellationToken cancellationToken, Consumer<RenderEvent> renderer) {
    Objects.requireNonNull(prompt, "prompt must not be null");
    ApplicationSession.TurnSelection selected = this.selection.get();
    TurnId turn;
    SessionId graphThread;
    AgentThreadRunner runner;
    ModelRequest request;
    synchronized (this) {
      if (this.activeRunner != null || this.restoredApproval != null) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("the current turn is waiting for approval"));
      }

      turn = TurnId.of(this.nextTurn++);
      this.lastPhase = "starting";
      this.lastEstimatedTokens = 0;
      this.lastInputBudgetTokens = 0;
      this.lastCompactionCount = 0;
      graphThread = SessionId.of(this.sessionId.value() + "-turn-" + turn.value());
      this.emit(turn, AgentEventType.USER_MESSAGE, Map.of("text", prompt));
      List<AgentMessage> turnMessages = new ArrayList<>(this.messages);
      if (turnMessages.isEmpty()) {
        turnMessages.add(new SystemMessage(this.systemPrompt(selected)));
      } else {
        turnMessages.set(0, new SystemMessage(this.systemPrompt(selected)));
      }
      String memoryContext = this.memory.contextForTurn(prompt);
      if (!memoryContext.isBlank()) {
        turnMessages.add(new SystemMessage(memoryContext));
      }
      turnMessages.add(new UserMessage(prompt));
      request = this.turns.request(selected, turnMessages);
      runner = this.createRunner(turn, cancellationToken, renderer);
      this.activeRunner = runner;
      this.activeGraphThread = graphThread;
      this.activeTurn = turn;
    }
    this.memory
        .approveExplicitCandidate(prompt)
        .ifPresent(
            id ->
                renderer.accept(
                    new Progress(
                        "Approved project memory candidate "
                            + id.substring(0, Math.min(12, id.length())))));

    String checkpoint = this.checkpoints.create(turn.value());
    synchronized (this) {
      this.lastCheckpoint = checkpoint;
    }
    this.emit(turn, AgentEventType.GIT_CHECKPOINT_CREATED, Map.of("message", checkpoint));
    renderer.accept(new Progress(checkpoint));

    return CompletableFuture.<MiniClaudeState>supplyAsync(() -> runner.start(graphThread, request))
        .whenComplete((state, error) -> this.releaseTurnOnFailure(error))
        .thenApply(state -> this.finishState(state, turn, renderer));
  }

  /**
   * Releases the turn slot when the graph future completes exceptionally.
   *
   * <p>{@code finishState} runs on the success path only, so any exceptional completion — a graph
   * recursion limit, a checkpoint IO error — used to leave {@code activeRunner} set forever. Every
   * later prompt then failed with "the current turn is waiting for approval" and the REPL was
   * unusable until restart.
   */
  private synchronized void releaseTurnOnFailure(Throwable error) {
    if (error == null) {
      return;
    }
    this.activeRunner = null;
    this.activeGraphThread = null;
    this.activeTurn = null;
    this.restoredApproval = null;
    this.restoredPreview = null;
  }

  public CompletionStage<TurnOutcome> resume(
      ApprovalDecision decision,
      CancellationToken cancellationToken,
      Consumer<RenderEvent> renderer) {
    AgentThreadRunner runner;
    SessionId graphThread;
    TurnId turn;
    synchronized (this) {
      if (this.activeGraphThread == null || this.activeTurn == null) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("no turn is waiting for approval"));
      }

      runner =
          this.activeRunner != null
              ? this.activeRunner
              : this.createRunner(this.activeTurn, cancellationToken, renderer);
      this.activeRunner = runner;
      graphThread = this.activeGraphThread;
      turn = this.activeTurn;
      this.emit(
          turn,
          AgentEventType.APPROVAL_RESOLVED,
          Map.of("choice", decision.choice().name(), "scope", decision.scope().name()));
    }

    return CompletableFuture.<MiniClaudeState>supplyAsync(
            () -> runner.resume(graphThread, decision))
        .whenComplete((state, error) -> this.releaseTurnOnFailure(error))
        .thenApply(state -> this.finishState(state, turn, renderer));
  }

  public synchronized Optional<ApprovalRequest> pendingApproval() {
    return Optional.ofNullable(this.restoredApproval);
  }

  public synchronized Optional<String> pendingApprovalPreview() {
    return Optional.ofNullable(this.restoredPreview);
  }

  synchronized String status() {
    List<TodoItem> tasks = this.components.todoTool().items(this.sessionId);
    long done = tasks.stream().filter(item -> item.status() == Status.DONE).count();
    String state =
        this.restoredApproval != null
            ? "Awaiting approval"
            : this.activeRunner != null ? "Running" : "Idle";
    return "Session: "
        + this.sessionId.value()
        + System.lineSeparator()
        + "State: "
        + state
        + System.lineSeparator()
        + "Turn: "
        + this.nextTurn
        + System.lineSeparator()
        + "Tasks: "
        + done
        + "/"
        + tasks.size()
        + System.lineSeparator()
        + "Phase: "
        + this.lastPhase
        + System.lineSeparator()
        + "Context: "
        + contextStatus()
        + System.lineSeparator()
        + "Last verification: "
        + this.lastVerification
        + System.lineSeparator()
        + "Checkpoint: "
        + this.lastCheckpoint;
  }

  synchronized String sessions() {
    Path root = this.audit.eventsRoot();
    if (!Files.isDirectory(root)) {
      return "(none)";
    } else {
      try {
        String var4;
        try (Stream<Path> files = Files.list(root)) {
          String result =
              files
                  .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                  .map(path -> path.getFileName().toString().replaceFirst("\\.jsonl$", ""))
                  .sorted()
                  .reduce((left, right) -> left + System.lineSeparator() + right)
                  .orElse("(none)");
          var4 = result;
        }

        return var4;
      } catch (IOException var7) {
        return "Cannot list sessions: " + var7.getMessage();
      }
    }
  }

  synchronized String usage() {
    return this.usage.summary();
  }

  synchronized String checkpoints() {
    return this.checkpoints.list();
  }

  synchronized String restoreCheckpoint(
      dev.miniclaudecode.cli.commands.SlashCommand.Restore command) {
    Objects.requireNonNull(command, "command must not be null");
    ensureNoActiveTurn();
    return command.apply()
        ? this.checkpoints.restore(command.revision())
        : this.checkpoints.previewRestore(command.revision());
  }

  synchronized String undo() {
    ensureNoActiveTurn();
    return this.checkpoints.undo();
  }

  synchronized String redo() {
    ensureNoActiveTurn();
    return this.checkpoints.redo();
  }

  synchronized void switchTo(String value) {
    SessionId selected = SessionId.of(value);
    ReadResult read = this.audit.read(selected);
    if (read.events().isEmpty()) {
      throw new IllegalArgumentException("unknown session: " + value);
    }
    SessionRestorationService.RestoredSession restored =
        this.restoration.restore(selected, read.events(), this.systemPrompt());
    this.usage.restore(read.events());
    this.components.todoTool().restore(selected, restored.tasks());
    this.sessionId = selected;
    this.nextTurn = restored.nextTurn();
    this.messages = restored.messages();
    this.activeRunner = null;
    this.activeGraphThread = null;
    this.activeTurn = null;
    this.restoredApproval = null;
    this.restoredPreview = null;
    this.lastCheckpoint = "(unknown; use /checkpoints)";
    this.lastVerification = "not restored";
    SessionRestorationService.RestoredProgress progress = restored.progress();
    this.lastPhase = progress.phase();
    this.lastEstimatedTokens = progress.estimatedTokens();
    this.lastInputBudgetTokens = progress.inputBudgetTokens();
    this.lastCompactionCount = progress.compactionCount();
    Optional<SessionRestorationService.PendingApproval> pendingApproval =
        restored.pendingApproval();
    if (pendingApproval.isPresent()) {
      SessionRestorationService.PendingApproval pending = pendingApproval.orElseThrow();
      this.restoredApproval = pending.request();
      this.restoredPreview = pending.preview();
      this.activeTurn = pending.turn();
      this.activeGraphThread = pending.graphThread();
    }
  }

  synchronized void compact() {
    int before = new dev.miniclaudecode.context.ContextPlanner().estimateTokens(this.messages);
    this.messages = new DeterministicContextReducer().reduce(this.messages);
    int after = new dev.miniclaudecode.context.ContextPlanner().estimateTokens(this.messages);
    this.lastEstimatedTokens = after;
    this.lastInputBudgetTokens = 0;
    this.lastCompactionCount++;
    this.lastPhase = "manual compaction";
    if (this.nextTurn > 1L) {
      this.emit(
          TurnId.of(this.nextTurn - 1L),
          AgentEventType.COMPACTION,
          Map.of(
              "reason",
              "manual",
              "beforeEstimatedTokens",
              before,
              "afterEstimatedTokens",
              after,
              "inputBudgetTokens",
              0,
              "compactionCount",
              this.lastCompactionCount));
    }
  }

  private void ensureNoActiveTurn() {
    if (this.activeRunner != null || this.restoredApproval != null) {
      throw new IllegalStateException(
          "wait for the current agent turn before changing workspace files");
    }
  }

  private AgentThreadRunner createRunner(
      TurnId turn, CancellationToken cancellationToken, Consumer<RenderEvent> renderer) {
    return this.turns.createRunner(
        this.sessionId,
        turn,
        cancellationToken,
        renderer,
        this.usage::record,
        progress -> this.onLoopProgress(turn, progress, renderer));
  }

  private synchronized TurnOutcome finishState(
      MiniClaudeState state, TurnId turn, Consumer<RenderEvent> renderer) {
    this.messages = state.messages();
    this.lastVerification = verificationStatus(state);
    if (state.status() == AgentStatus.WAITING_APPROVAL) {
      renderer.accept(new Progress("Waiting for approval..."));
      ApprovalRequest pending = state.pendingApproval().orElseThrow();
      // Show only the diff belonging to the change actually being approved. Concatenating every
      // diff in the batch made the user believe one decision covered several file changes.
      String preview =
          state.toolResults().stream()
              .filter(result -> result.toolCallId().equals(pending.toolCall().toolCallId()))
              .map(result -> result.metadata().get("unifiedDiff"))
              .filter(String.class::isInstance)
              .map(String.class::cast)
              .findFirst()
              .orElse(null);
      return TurnOutcome.waitingFor(pending, preview);
    } else {
      if (state.status() == AgentStatus.CANCELLED) {
        this.emit(turn, AgentEventType.TURN_CANCELLED, Map.of("message", "turn cancelled by user"));
        renderer.accept(new Progress("Turn cancelled"));
      } else if (state.status() == AgentStatus.FAILED) {
        String error = state.error().orElse("agent turn failed");
        this.emit(turn, AgentEventType.ERROR, Map.of("message", error));
        this.memory.distillReflexion(this.sessionId, state, error, turn, renderer);
        renderer.accept(new Error(error));
      } else {
        this.emit(turn, AgentEventType.TURN_FINAL, Map.of("text", state.finalText()));
        this.memory.captureExplicitPreference(this.sessionId, state, turn, renderer);
        this.memory.distillReflexion(this.sessionId, state, "", turn, renderer);
        renderer.accept(new Completed());
      }

      this.activeRunner = null;
      this.activeGraphThread = null;
      this.activeTurn = null;
      this.restoredApproval = null;
      this.restoredPreview = null;
      return TurnOutcome.finished(state.status());
    }
  }

  private String systemPrompt() {
    return this.systemPrompt(
        new TurnSelection(
            this.components.config().activeProvider(),
            this.components.config().activeProfile().model(),
            this.components.config().activeProfile().thinking()));
  }

  private String systemPrompt(TurnSelection selected) {
    ProviderProfile profile = this.components.config().providers().get(selected.provider());
    if (profile == null) {
      throw new IllegalArgumentException("unknown provider profile: " + selected.provider());
    }
    return this.promptPipeline.build(
        new PromptBuildContext(
            this.components.workspace(),
            this.components.tools().descriptors(),
            this.components.skills().promptIndex(),
            profile.outputProtocol().promptInstruction(),
            Map.of(
                "provider", selected.provider(),
                "model", selected.model(),
                "thinking", selected.thinking())));
  }

  private void emit(TurnId turnId, AgentEventType type, Map<String, Object> payload) {
    this.audit.emit(this.sessionId, turnId, type, payload);
  }

  private synchronized void onLoopProgress(
      TurnId turn, TurnProgressListener.Progress progress, Consumer<RenderEvent> renderer) {
    this.lastPhase = progress.phase();
    this.lastEstimatedTokens = progress.estimatedInputTokens();
    this.lastInputBudgetTokens = progress.inputBudgetTokens();
    this.lastCompactionCount = progress.compactionCount();
    if (progress.compaction()) {
      this.emit(
          turn,
          AgentEventType.COMPACTION,
          Map.of(
              "reason", progress.compactionReason(),
              "beforeEstimatedTokens", progress.beforeCompactionTokens(),
              "afterEstimatedTokens", progress.estimatedInputTokens(),
              "inputBudgetTokens", progress.inputBudgetTokens(),
              "compactionCount", progress.compactionCount()));
      renderer.accept(
          new Progress(
              "Context compacted: "
                  + progress.beforeCompactionTokens()
                  + " -> "
                  + progress.estimatedInputTokens()
                  + " estimated tokens"));
    } else {
      this.emit(
          turn,
          AgentEventType.TURN_STAGE,
          Map.of(
              "phase", progress.phase(),
              "modelSteps", progress.modelSteps(),
              "toolSteps", progress.toolSteps(),
              "compactionCount", progress.compactionCount(),
              "estimatedInputTokens", progress.estimatedInputTokens(),
              "inputBudgetTokens", progress.inputBudgetTokens()));
    }
  }

  private String contextStatus() {
    if (this.lastEstimatedTokens <= 0) {
      return "not estimated";
    }
    String budget = this.lastInputBudgetTokens > 0 ? "/" + this.lastInputBudgetTokens : "";
    return this.lastEstimatedTokens
        + budget
        + " estimated tokens; compactions="
        + this.lastCompactionCount;
  }

  private static String verificationStatus(MiniClaudeState state) {
    AgentMessage.ToolMessage latest = null;
    for (AgentMessage message : state.messages()) {
      if (message instanceof AgentMessage.ToolMessage tool
          && "shell:run".equals(tool.qualifiedToolName())) {
        latest = tool;
      }
    }
    if (latest == null) {
      return "not run";
    }
    return latest.error() ? "failed" : "passed";
  }

  static record TurnSelection(String provider, String model, boolean thinking) {
    TurnSelection(String provider, String model, boolean thinking) {
      Objects.requireNonNull(provider);
      Objects.requireNonNull(model);
      this.provider = provider;
      this.model = model;
      this.thinking = thinking;
    }
  }
}
