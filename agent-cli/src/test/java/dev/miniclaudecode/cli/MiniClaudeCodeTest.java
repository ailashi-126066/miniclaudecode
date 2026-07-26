package dev.miniclaudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MiniClaudeCodeTest {

  @Test
  void parsesInteractiveRunIndexAndRagTopLevelCommands() {
    AtomicReference<String> invocation = new AtomicReference<>();
    CliActions actions =
        new CliActions() {
          @Override
          public int configure() {
            invocation.set("config");
            return 0;
          }

          @Override
          public int interactive(Path workspace) {
            invocation.set("interactive:" + workspace);
            return 0;
          }

          @Override
          public int run(Path workspace, String prompt) {
            invocation.set("run:" + workspace + ":" + prompt);
            return 0;
          }

          @Override
          public int index(Path workspace) {
            invocation.set("index:" + workspace);
            return 0;
          }

          @Override
          public int rag(Path workspace, String query) {
            invocation.set("rag:" + workspace + ":" + query);
            return 0;
          }
        };
    MiniClaudeCode command = new MiniClaudeCode(actions);

    assertThat(command.commandLine().execute("config")).isZero();
    assertThat(invocation.get()).isEqualTo("config");
    assertThat(command.commandLine().execute("--workspace", "project")).isZero();
    assertThat(invocation.get()).isEqualTo("interactive:project");
    assertThat(command.commandLine().execute("run", "--workspace", "project", "fix", "tests"))
        .isZero();
    assertThat(invocation.get()).isEqualTo("run:project:fix tests");
    assertThat(command.commandLine().execute("index", "-w", "project")).isZero();
    assertThat(invocation.get()).isEqualTo("index:project");
    assertThat(command.commandLine().execute("rag", "find", "symbol")).isZero();
    assertThat(invocation.get()).isEqualTo("rag:.:find symbol");
  }
}
