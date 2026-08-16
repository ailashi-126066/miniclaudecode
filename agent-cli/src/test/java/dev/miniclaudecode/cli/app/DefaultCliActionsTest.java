package dev.miniclaudecode.cli.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.persistence.path.UserDataLayout;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultCliActionsTest {
  @TempDir Path temporaryDirectory;

  @Test
  void rejectsAgentStartupWithoutAnInteractiveTtyInsteadOfFallingBackToBatchMode() {
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    DefaultCliActions actions =
        new DefaultCliActions(
            UserDataLayout.forHome(temporaryDirectory.resolve("home")),
            Map.of(),
            new PrintWriter(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            new PrintWriter(errors, true, StandardCharsets.UTF_8),
            () -> false);

    assertThat(actions.interactive(temporaryDirectory.resolve("workspace"))).isEqualTo(2);
    assertThat(errors.toString(StandardCharsets.UTF_8))
        .contains("requires an interactive TTY", "config/index/rag")
        .doesNotContain("run");
  }
}
