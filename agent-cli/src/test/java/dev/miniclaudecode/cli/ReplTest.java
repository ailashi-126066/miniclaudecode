package dev.miniclaudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplTest {

  @TempDir Path temporaryDirectory;

  @Test
  void configuresPersistentHistory() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (var terminal =
        TerminalBuilder.builder()
            .system(false)
            .dumb(true)
            .streams(new ByteArrayInputStream(new byte[0]), output)
            .encoding(StandardCharsets.UTF_8)
            .build()) {
      Path history = temporaryDirectory.resolve("state/history");
      Repl repl =
          Repl.create(
              terminal,
              history,
              new AgentCompleter(temporaryDirectory, List::of, List::of, List::of),
              command -> "ok",
              new Repl.TurnHandler() {
                @Override
                public java.util.concurrent.CompletionStage<Repl.TurnOutcome> start(
                    String prompt,
                    dev.miniclaudecode.domain.runtime.CancellationToken token,
                    java.util.function.Consumer<StreamingRenderer.RenderEvent> events) {
                  return CompletableFuture.completedFuture(Repl.TurnOutcome.completed());
                }

                @Override
                public java.util.concurrent.CompletionStage<Repl.TurnOutcome> resume(
                    dev.miniclaudecode.domain.approval.ApprovalDecision decision,
                    dev.miniclaudecode.domain.runtime.CancellationToken token,
                    java.util.function.Consumer<StreamingRenderer.RenderEvent> events) {
                  return CompletableFuture.completedFuture(Repl.TurnOutcome.completed());
                }
              });

      assertThat(repl.reader().getVariable(LineReader.HISTORY_FILE)).isEqualTo(history);
    }
  }

  @Test
  void ctrlCCancelsTheActiveTurnAndCtrlDExits() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (var terminal =
        TerminalBuilder.builder()
            .system(false)
            .dumb(true)
            .streams(new ByteArrayInputStream(new byte[0]), output)
            .encoding(StandardCharsets.UTF_8)
            .build()) {
      LineReader reader = org.mockito.Mockito.mock(LineReader.class);
      History history = org.mockito.Mockito.mock(History.class);
      org.mockito.Mockito.when(reader.getTerminal()).thenReturn(terminal);
      org.mockito.Mockito.when(reader.getHistory()).thenReturn(history);
      org.mockito.Mockito.when(reader.readLine(org.mockito.ArgumentMatchers.anyString()))
          .thenReturn("long task")
          .thenThrow(new EndOfFileException());
      CountDownLatch started = new CountDownLatch(1);
      AtomicInteger cancellations = new AtomicInteger();
      Repl repl =
          new Repl(
              reader,
              new SlashCommandParser(),
              command -> "",
              new Repl.TurnHandler() {
                @Override
                public java.util.concurrent.CompletionStage<Repl.TurnOutcome> start(
                    String prompt,
                    dev.miniclaudecode.domain.runtime.CancellationToken token,
                    java.util.function.Consumer<StreamingRenderer.RenderEvent> events) {
                  CompletableFuture<Repl.TurnOutcome> result = new CompletableFuture<>();
                  token.onCancel(
                      () -> {
                        cancellations.incrementAndGet();
                        result.complete(Repl.TurnOutcome.completed());
                      });
                  started.countDown();
                  return result;
                }

                @Override
                public java.util.concurrent.CompletionStage<Repl.TurnOutcome> resume(
                    dev.miniclaudecode.domain.approval.ApprovalDecision decision,
                    dev.miniclaudecode.domain.runtime.CancellationToken token,
                    java.util.function.Consumer<StreamingRenderer.RenderEvent> events) {
                  return CompletableFuture.completedFuture(Repl.TurnOutcome.completed());
                }
              },
              new StreamingRenderer(terminal),
              org.mockito.Mockito.mock(ApprovalMenu.class));

      CompletableFuture<Void> running = CompletableFuture.runAsync(repl::run);
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      terminal.raise(Terminal.Signal.INT);
      running.get(5, TimeUnit.SECONDS);

      assertThat(cancellations).hasValue(1);
      assertThat(output.toString(StandardCharsets.UTF_8)).contains("MiniClaudeCode");
    }
  }

  @Test
  void routesConfigSetupToTheInteractiveConfigurationHandler() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (var terminal =
        TerminalBuilder.builder()
            .system(false)
            .dumb(true)
            .streams(new ByteArrayInputStream(new byte[0]), output)
            .encoding(StandardCharsets.UTF_8)
            .build()) {
      LineReader reader = org.mockito.Mockito.mock(LineReader.class);
      History history = org.mockito.Mockito.mock(History.class);
      org.mockito.Mockito.when(reader.getTerminal()).thenReturn(terminal);
      org.mockito.Mockito.when(reader.getHistory()).thenReturn(history);
      org.mockito.Mockito.when(reader.readLine(org.mockito.ArgumentMatchers.anyString()))
          .thenReturn("/config setup")
          .thenThrow(new EndOfFileException());
      AtomicReference<LineReader> configuredReader = new AtomicReference<>();
      Repl repl =
          new Repl(
              reader,
              new SlashCommandParser(),
              command -> "ordinary command",
              completedTurnHandler(),
              new StreamingRenderer(terminal),
              org.mockito.Mockito.mock(ApprovalMenu.class),
              value -> {
                configuredReader.set(value);
                return "Configuration saved. Restart MiniClaudeCode.";
              });

      repl.run();

      assertThat(configuredReader).hasValue(reader);
      assertThat(output.toString(StandardCharsets.UTF_8))
          .contains("Configuration saved", "Restart MiniClaudeCode");
    }
  }

  private static Repl.TurnHandler completedTurnHandler() {
    return new Repl.TurnHandler() {
      @Override
      public java.util.concurrent.CompletionStage<Repl.TurnOutcome> start(
          String prompt,
          dev.miniclaudecode.domain.runtime.CancellationToken token,
          java.util.function.Consumer<StreamingRenderer.RenderEvent> events) {
        return CompletableFuture.completedFuture(Repl.TurnOutcome.completed());
      }

      @Override
      public java.util.concurrent.CompletionStage<Repl.TurnOutcome> resume(
          dev.miniclaudecode.domain.approval.ApprovalDecision decision,
          dev.miniclaudecode.domain.runtime.CancellationToken token,
          java.util.function.Consumer<StreamingRenderer.RenderEvent> events) {
        return CompletableFuture.completedFuture(Repl.TurnOutcome.completed());
      }
    };
  }
}
