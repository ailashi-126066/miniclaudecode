package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.cli.Repl.TurnOutcome;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.persistence.path.UserDataLayout;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NonInteractiveE2ETest {
  @TempDir Path temporaryDirectory;

  @Test
  void runsTheProductionCompositionRootInAUnicodeWorkspaceWithThePackagedFakeHook()
      throws Exception {
    Path workspace = this.temporaryDirectory.resolve("项目 with spaces");
    Files.createDirectories(workspace);
    Files.writeString(workspace.resolve("Example.java"), "class Example {}\n");
    StringWriter standardOutput = new StringWriter();
    StringWriter standardError = new StringWriter();
    UserDataLayout layout = UserDataLayout.forHome(this.temporaryDirectory.resolve("home"));
    DefaultCliActions actions =
        new DefaultCliActions(
            layout,
            Map.of("MINICLAUDE_FAKE_RESPONSE", "完成：workspace inspected."),
            new PrintWriter(standardOutput, true),
            new PrintWriter(standardError, true));
    int exitCode = actions.run(workspace, "检查项目");
    Assertions.assertThat(exitCode).isZero();
    Assertions.assertThat(standardOutput.toString())
        .contains(new CharSequence[] {"完成：workspace inspected."});
    Assertions.assertThat(standardError.toString()).isEmpty();
    Path events = layout.sessionWorkspaceRoot(workspace).resolve("events");
    Assertions.assertThat(events)
        .isDirectoryContaining(path -> path.getFileName().toString().endsWith(".jsonl"));

    String jsonl;
    try (Stream<Path> files = Files.list(events)) {
      jsonl = Files.readString(files.findFirst().orElseThrow());
    }

    Assertions.assertThat(jsonl)
        .contains(new CharSequence[] {"USER_MESSAGE", "TURN_FINAL", "检查项目"});
  }

  @Test
  void buildsAndReportsTheOfflineHybridIndex() throws Exception {
    Path workspace = this.temporaryDirectory.resolve("rag-workspace");
    Files.createDirectories(workspace);
    Files.writeString(
        workspace.resolve("OrderService.java"),
        "class OrderService { String findOrderById(String id) { return id; } }\n");
    StringWriter output = new StringWriter();
    DefaultCliActions actions =
        new DefaultCliActions(
            UserDataLayout.forHome(this.temporaryDirectory.resolve("rag-home")),
            Map.of("MINICLAUDE_FAKE_RESPONSE", "unused"),
            new PrintWriter(output, true),
            new PrintWriter(new StringWriter(), true));
    Assertions.assertThat(actions.index(workspace)).isZero();
    Assertions.assertThat(actions.rag(workspace, "stats")).isZero();
    Assertions.assertThat(actions.rag(workspace, "explain findOrderById")).isZero();
    Assertions.assertThat(output.toString())
        .contains(
            new CharSequence[] {
              "Indexed 1 files", "vectorDimensions=384", "BM25 candidates", "OrderService.java"
            });
  }

  @Test
  void mapsTerminalAgentStatesToAutomationFriendlyExitCodes() {
    Assertions.assertThat(DefaultCliActions.exitCode(TurnOutcome.completed())).isZero();
    Assertions.assertThat(DefaultCliActions.exitCode(TurnOutcome.finished(AgentStatus.FAILED)))
        .isEqualTo(2);
    Assertions.assertThat(DefaultCliActions.exitCode(TurnOutcome.finished(AgentStatus.CANCELLED)))
        .isEqualTo(130);
  }
}
