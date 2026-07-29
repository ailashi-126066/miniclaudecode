package dev.miniclaudecode.cli;

import dev.miniclaudecode.cli.StreamingRenderer.RenderEvent;
import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.domain.session.AgentStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

public final class Repl {

  private static final Duration RENDER_POLL_INTERVAL = Duration.ofMillis(20);
  private static final AttributedStyle PROMPT_STYLE =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold();

  private volatile List<String> headerLines = List.of();

  private final LineReader reader;
  private final SlashCommandParser commandParser;
  private final SlashCommandHandler commandHandler;
  private final TurnHandler turnHandler;
  private final StreamingRenderer renderer;
  private final ApprovalMenu approvalMenu;
  private final ConfigurationHandler configurationHandler;
  private final AtomicReference<CancellationToken> activeTurn = new AtomicReference<>();

  public Repl(
      LineReader reader,
      SlashCommandParser commandParser,
      SlashCommandHandler commandHandler,
      TurnHandler turnHandler,
      StreamingRenderer renderer,
      ApprovalMenu approvalMenu) {
    this(
        reader,
        commandParser,
        commandHandler,
        turnHandler,
        renderer,
        approvalMenu,
        ignored -> "Configuration wizard is not available.");
  }

  public Repl(
      LineReader reader,
      SlashCommandParser commandParser,
      SlashCommandHandler commandHandler,
      TurnHandler turnHandler,
      StreamingRenderer renderer,
      ApprovalMenu approvalMenu,
      ConfigurationHandler configurationHandler) {
    this.reader = Objects.requireNonNull(reader, "reader must not be null");
    this.commandParser = Objects.requireNonNull(commandParser, "commandParser must not be null");
    this.commandHandler = Objects.requireNonNull(commandHandler, "commandHandler must not be null");
    this.turnHandler = Objects.requireNonNull(turnHandler, "turnHandler must not be null");
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    this.approvalMenu = Objects.requireNonNull(approvalMenu, "approvalMenu must not be null");
    this.configurationHandler =
        Objects.requireNonNull(configurationHandler, "configurationHandler must not be null");
  }

  public static Repl create(
      Terminal terminal,
      Path historyFile,
      Completer completer,
      SlashCommandHandler commandHandler,
      TurnHandler turnHandler) {
    return create(
        terminal,
        historyFile,
        completer,
        commandHandler,
        turnHandler,
        ignored -> "Configuration wizard is not available.");
  }

  public static Repl create(
      Terminal terminal,
      Path historyFile,
      Completer completer,
      SlashCommandHandler commandHandler,
      TurnHandler turnHandler,
      ConfigurationHandler configurationHandler) {
    Objects.requireNonNull(terminal, "terminal must not be null");
    Objects.requireNonNull(historyFile, "historyFile must not be null");
    try {
      Path parent = historyFile.toAbsolutePath().normalize().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
    } catch (IOException error) {
      throw new IllegalArgumentException("cannot create history directory", error);
    }
    LineReader reader =
        LineReaderBuilder.builder()
            .terminal(terminal)
            .completer(completer)
            .variable(LineReader.HISTORY_FILE, historyFile)
            .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
            .build();
    StreamingRenderer renderer = new StreamingRenderer(terminal);
    return new Repl(
        reader,
        new SlashCommandParser(),
        commandHandler,
        turnHandler,
        renderer,
        new ApprovalMenu(reader, Clock.systemUTC()),
        configurationHandler);
  }

  public Repl withHeader(List<String> lines) {
    this.headerLines = List.copyOf(Objects.requireNonNull(lines, "lines must not be null"));
    return this;
  }

  public void run() {
    Terminal terminal = reader.getTerminal();
    var previousHandler =
        terminal.handle(
            Terminal.Signal.INT,
            signal -> {
              CancellationToken token = activeTurn.get();
              if (token != null) {
                token.cancel();
              }
            });
    try {
      if (headerLines.isEmpty()) {
        terminal.writer().println("MiniClaudeCode — type /help for commands");
      } else {
        headerLines.forEach(terminal.writer()::println);
      }
      terminal.flush();
      while (true) {
        Optional<String> nextInput = readInput();
        if (nextInput.isEmpty()) {
          break;
        }
        try {
          String input = nextInput.orElseThrow();
          if (input.isBlank()) {
            continue;
          }
          if (input.strip().equalsIgnoreCase("/exit")) {
            break;
          } else if (commandParser.isSlashCommand(input)) {
            var command = commandParser.parse(input);
            String output =
                command instanceof dev.miniclaudecode.cli.commands.SlashCommand.Config config
                        && config.setup()
                    ? configurationHandler.configure(reader)
                    : commandHandler.execute(command);
            if (output != null && !output.isBlank()) {
              terminal.writer().println(output);
              terminal.flush();
            }
            resumePendingApprovalIfPresent();
          } else {
            executeTurn(input);
          }
        } catch (RuntimeException error) {
          renderer.submit(new RenderEvent.Error(safeMessage(error)));
          renderer.renderAvailable();
        }
      }
    } finally {
      terminal.handle(Terminal.Signal.INT, previousHandler);
      try {
        reader.getHistory().save();
      } catch (IOException ignored) {
        // The session can still terminate normally if history persistence fails.
      }
    }
  }

  private Optional<String> readInput() {
    try {
      return Optional.ofNullable(reader.readLine(prompt()));
    } catch (UserInterruptException ignored) {
      CancellationToken token = activeTurn.get();
      if (token != null) {
        token.cancel();
      }
      return Optional.of("");
    } catch (EndOfFileException ignored) {
      return Optional.empty();
    }
  }

  LineReader reader() {
    return reader;
  }

  String prompt() {
    return new AttributedString("> ", PROMPT_STYLE).toAnsi(reader.getTerminal());
  }

  private void executeTurn(String prompt) {
    CancellationToken token = new CancellationToken();
    activeTurn.set(token);
    try {
      TurnOutcome outcome = await(turnHandler.start(prompt, token, renderer::submit));
      while (outcome.approvalRequest().isPresent() && !token.isCancellationRequested()) {
        outcome.approvalPreview().ifPresent(this::printApprovalPreview);
        ApprovalDecision decision = approvalMenu.prompt(outcome.approvalRequest().orElseThrow());
        outcome = await(turnHandler.resume(decision, token, renderer::submit));
      }
    } finally {
      activeTurn.compareAndSet(token, null);
    }
  }

  private void resumePendingApprovalIfPresent() {
    turnHandler
        .pendingApproval()
        .ifPresent(
            request -> {
              CancellationToken token = new CancellationToken();
              activeTurn.set(token);
              try {
                turnHandler.pendingApprovalPreview().ifPresent(this::printApprovalPreview);
                ApprovalDecision decision = approvalMenu.prompt(request);
                TurnOutcome outcome = await(turnHandler.resume(decision, token, renderer::submit));
                while (outcome.approvalRequest().isPresent() && !token.isCancellationRequested()) {
                  outcome.approvalPreview().ifPresent(this::printApprovalPreview);
                  decision = approvalMenu.prompt(outcome.approvalRequest().orElseThrow());
                  outcome = await(turnHandler.resume(decision, token, renderer::submit));
                }
              } finally {
                activeTurn.compareAndSet(token, null);
              }
            });
  }

  private void printApprovalPreview(String preview) {
    Terminal terminal = reader.getTerminal();
    terminal.writer().println();
    terminal.writer().println("Proposed change:");
    terminal.writer().println(preview);
    terminal.flush();
  }

  private TurnOutcome await(CompletionStage<TurnOutcome> stage) {
    renderer.renderUntil(stage, RENDER_POLL_INTERVAL);
    try {
      return stage.toCompletableFuture().join();
    } catch (CompletionException error) {
      Throwable cause = error.getCause() == null ? error : error.getCause();
      renderer.submit(new RenderEvent.Error(safeMessage(cause)));
      renderer.renderAvailable();
      return TurnOutcome.failed();
    }
  }

  private static String safeMessage(Throwable error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }

  public interface TurnHandler {
    CompletionStage<TurnOutcome> start(
        String prompt,
        CancellationToken cancellationToken,
        java.util.function.Consumer<RenderEvent> events);

    CompletionStage<TurnOutcome> resume(
        ApprovalDecision decision,
        CancellationToken cancellationToken,
        java.util.function.Consumer<RenderEvent> events);

    default Optional<ApprovalRequest> pendingApproval() {
      return Optional.empty();
    }

    default Optional<String> pendingApprovalPreview() {
      return Optional.empty();
    }
  }

  @FunctionalInterface
  public interface ConfigurationHandler {
    String configure(LineReader reader);
  }

  public record TurnOutcome(
      AgentStatus status,
      Optional<ApprovalRequest> approvalRequest,
      Optional<String> approvalPreview) {
    public TurnOutcome {
      status = Objects.requireNonNull(status, "status must not be null");
      approvalRequest = Objects.requireNonNull(approvalRequest, "approvalRequest must not be null");
      approvalPreview =
          Objects.requireNonNull(approvalPreview, "approvalPreview must not be null")
              .map(String::strip)
              .filter(value -> !value.isEmpty());
    }

    public static TurnOutcome completed() {
      return finished(AgentStatus.COMPLETED);
    }

    public static TurnOutcome failed() {
      return finished(AgentStatus.FAILED);
    }

    public static TurnOutcome finished(AgentStatus status) {
      return new TurnOutcome(status, Optional.empty(), Optional.empty());
    }

    public static TurnOutcome waitingFor(ApprovalRequest request) {
      return waitingFor(request, null);
    }

    public static TurnOutcome waitingFor(ApprovalRequest request, String preview) {
      return new TurnOutcome(
          AgentStatus.WAITING_APPROVAL, Optional.of(request), Optional.ofNullable(preview));
    }
  }
}
