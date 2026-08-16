package dev.miniclaudecode.cli.tui;

import com.williamcallahan.tui4j.compat.bubbletea.Command;
import com.williamcallahan.tui4j.compat.bubbletea.KeyPressMessage;
import com.williamcallahan.tui4j.compat.bubbletea.Message;
import com.williamcallahan.tui4j.compat.bubbletea.Model;
import com.williamcallahan.tui4j.compat.bubbletea.Program;
import com.williamcallahan.tui4j.compat.bubbletea.QuitMessage;
import com.williamcallahan.tui4j.compat.bubbletea.UpdateResult;
import com.williamcallahan.tui4j.compat.bubbletea.WindowSizeMessage;
import dev.miniclaudecode.cli.SlashCommandHandler;
import dev.miniclaudecode.cli.SlashCommandParser;
import dev.miniclaudecode.cli.TurnEvent;
import dev.miniclaudecode.cli.TurnHandler;
import dev.miniclaudecode.cli.TurnOutcome;
import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** Thin TUI4J model: key input and async callbacks become reducer events. */
public final class TuiModel implements Model {
  private final TurnHandler turns;
  private final SlashCommandParser parser;
  private final SlashCommandHandler commands;
  private final TuiReducer reducer;
  private final TuiView view;
  private final String workspace;
  private final Supplier<TuiDashboard> dashboard;
  private TuiState state = TuiState.initial();
  private volatile Program program;
  private volatile CancellationToken activeCancellation;

  public TuiModel(
      TurnHandler turns,
      SlashCommandParser parser,
      SlashCommandHandler commands,
      String workspace,
      Supplier<TuiDashboard> dashboard) {
    this.turns = Objects.requireNonNull(turns, "turns must not be null");
    this.parser = Objects.requireNonNull(parser, "parser must not be null");
    this.commands = Objects.requireNonNull(commands, "commands must not be null");
    this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
    this.dashboard = Objects.requireNonNull(dashboard, "dashboard must not be null");
    this.reducer = new TuiReducer();
    this.view = new TuiView();
    refreshDashboard();
  }

  public void attach(Program program) {
    this.program = Objects.requireNonNull(program, "program must not be null");
  }

  TuiState state() {
    return this.state;
  }

  @Override
  public Command init() {
    return Command.batch(Command.checkWindowSize(), Command.setWindowTitle("MiniClaudeCode"));
  }

  @Override
  public UpdateResult<? extends Model> update(Message message) {
    if (message instanceof UiEventMessage event) {
      this.state = this.reducer.reduce(this.state, event.event());
      refreshDashboard();
      return UpdateResult.from(this);
    }
    if (message instanceof WindowSizeMessage resized) {
      this.state =
          this.reducer.reduce(this.state, new TuiEvent.Resize(resized.width(), resized.height()));
      return UpdateResult.from(this);
    }
    if (message instanceof QuitMessage) {
      return UpdateResult.from(this, Command.quit());
    }
    if (!(message instanceof KeyPressMessage key)) {
      return UpdateResult.from(this);
    }
    return handleKey(key);
  }

  @Override
  public String view() {
    return this.view.render(this.state, this.workspace);
  }

  private UpdateResult<TuiModel> handleKey(KeyPressMessage keyPress) {
    String key = keyPress.key();
    if ("ctrl+c".equals(key)) {
      if (this.state.running()) {
        CancellationToken cancellation = this.activeCancellation;
        if (cancellation != null) {
          cancellation.cancel();
        }
        this.state =
            this.reducer.reduce(this.state, new TuiEvent.Progress("Cancelling current turn..."));
        return UpdateResult.from(this);
      }
      return UpdateResult.from(this, Command.quit());
    }

    if (this.state.approval().isPresent()) {
      if ("y".equalsIgnoreCase(key)) {
        resume(ApprovalDecision.Choice.ALLOW);
      } else if ("n".equalsIgnoreCase(key) || "escape".equals(key)) {
        resume(ApprovalDecision.Choice.REJECT);
      }
      return UpdateResult.from(this);
    }
    if (this.state.running()) {
      return UpdateResult.from(this);
    }
    if ("enter".equals(key)) {
      return submitInput();
    }
    if ("backspace".equals(key) || "ctrl+h".equals(key)) {
      String input = this.state.input();
      if (!input.isEmpty()) {
        this.state =
            this.reducer.reduce(
                this.state,
                new TuiEvent.InputChanged(
                    input.substring(
                        0,
                        input.offsetByCodePoints(0, input.codePointCount(0, input.length()) - 1))));
      }
      return UpdateResult.from(this);
    }
    if ("space".equals(key) || " ".equals(key)) {
      appendInput(" ");
      return UpdateResult.from(this);
    }
    char[] runes = keyPress.runes();
    if (runes != null && runes.length > 0) {
      StringBuilder printable = new StringBuilder();
      for (char rune : runes) {
        if (rune >= 32) {
          printable.append(rune);
        }
      }
      appendInput(printable.toString());
    } else if (key.length() == 1 && key.charAt(0) >= 32) {
      appendInput(key);
    }
    return UpdateResult.from(this);
  }

  private UpdateResult<TuiModel> submitInput() {
    String input = this.state.input().trim();
    if (input.isEmpty()) {
      return UpdateResult.from(this);
    }
    if ("/exit".equalsIgnoreCase(input) || "/quit".equalsIgnoreCase(input)) {
      return UpdateResult.from(this, Command.quit());
    }
    if (this.parser.isSlashCommand(input)) {
      try {
        String result = this.commands.execute(this.parser.parse(input));
        this.state = this.reducer.reduce(this.state, new TuiEvent.InputChanged(""));
        this.state = this.reducer.reduce(this.state, new TuiEvent.CommandResult(result));
        refreshDashboard();
      } catch (RuntimeException failure) {
        this.state = this.reducer.reduce(this.state, new TuiEvent.InputChanged(""));
        this.state = this.reducer.reduce(this.state, new TuiEvent.Failure(safeMessage(failure)));
        refreshDashboard();
      }
      return UpdateResult.from(this);
    }

    this.state = this.reducer.reduce(this.state, new TuiEvent.PromptSubmitted(input));
    CancellationToken cancellation = new CancellationToken();
    this.activeCancellation = cancellation;
    this.turns
        .start(input, cancellation, this::acceptRenderEvent)
        .whenComplete(
            (outcome, failure) -> {
              this.activeCancellation = null;
              if (failure != null) {
                post(new TuiEvent.Failure(safeMessage(unwrap(failure))));
                post(new TuiEvent.TurnFinished(TurnOutcome.failed()));
              } else {
                post(new TuiEvent.TurnFinished(outcome));
              }
            });
    return UpdateResult.from(this);
  }

  private void resume(ApprovalDecision.Choice choice) {
    var request = this.state.approval().orElseThrow();
    ApprovalDecision decision =
        new ApprovalDecision(
            request.approvalId(),
            choice,
            ApprovalDecision.Scope.ONCE,
            Optional.empty(),
            Instant.now());
    this.state = this.reducer.reduce(this.state, new TuiEvent.ApprovalStarted());
    CancellationToken cancellation = new CancellationToken();
    this.activeCancellation = cancellation;
    this.turns
        .resume(decision, cancellation, this::acceptRenderEvent)
        .whenComplete(
            (outcome, failure) -> {
              this.activeCancellation = null;
              if (failure != null) {
                post(new TuiEvent.Failure(safeMessage(unwrap(failure))));
                post(new TuiEvent.TurnFinished(TurnOutcome.failed()));
              } else {
                post(new TuiEvent.TurnFinished(outcome));
              }
            });
  }

  private void acceptRenderEvent(TurnEvent event) {
    TuiEvent mapped =
        switch (event) {
          case TurnEvent.Text text -> new TuiEvent.TextDelta(text.text());
          case TurnEvent.Thinking thinking -> new TuiEvent.ThinkingDelta(thinking.text());
          case TurnEvent.Progress progress -> new TuiEvent.Progress(progress.text());
          case TurnEvent.Error error -> new TuiEvent.Failure(error.text());
          case TurnEvent.Completed ignored -> new TuiEvent.Progress("Completing turn...");
        };
    post(mapped);
  }

  private void post(TuiEvent event) {
    Program attached = this.program;
    if (attached != null && attached.isRunning()) {
      attached.send(new UiEventMessage(event));
    }
  }

  private void appendInput(String text) {
    if (!text.isEmpty()) {
      this.state =
          this.reducer.reduce(this.state, new TuiEvent.InputChanged(this.state.input() + text));
    }
  }

  private void refreshDashboard() {
    try {
      this.state =
          this.reducer.reduce(this.state, new TuiEvent.DashboardUpdated(this.dashboard.get()));
    } catch (RuntimeException ignored) {
      // Dashboard refresh must never interrupt input or a running turn.
    }
  }

  private static Throwable unwrap(Throwable failure) {
    return failure instanceof CompletionException && failure.getCause() != null
        ? failure.getCause()
        : failure;
  }

  private static String safeMessage(Throwable failure) {
    return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
  }

  public record UiEventMessage(TuiEvent event) implements Message {
    public UiEventMessage {
      event = Objects.requireNonNull(event, "event must not be null");
    }
  }
}
