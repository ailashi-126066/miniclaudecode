package dev.miniclaudecode.cli.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.persistence.path.UserDataLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeInstructionsTest {

  @TempDir Path temporaryDirectory;

  @Test
  void loadsGlobalAndNestedProjectInstructionsButNotGeneratedAgentState() throws Exception {
    Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
    Path module = Files.createDirectory(workspace.resolve("module"));
    UserDataLayout layout = UserDataLayout.forHome(temporaryDirectory.resolve("home"));
    Files.createDirectories(layout.globalMiniclaudeFile().getParent());
    Files.writeString(layout.globalMiniclaudeFile(), "Global fact", StandardCharsets.UTF_8);
    Files.writeString(workspace.resolve("miniclaude.md"), "Root fact", StandardCharsets.UTF_8);
    Files.writeString(module.resolve("miniclaude.md"), "Module fact", StandardCharsets.UTF_8);
    Files.createDirectories(workspace.resolve(".miniclaudecode"));
    Files.writeString(
        workspace.resolve(".miniclaudecode/miniclaude.md"),
        "Must not load",
        StandardCharsets.UTF_8);

    String loaded = new ClaudeInstructions(workspace, layout).load();

    assertThat(loaded)
        .contains("Global fact", "Root fact", "Module fact")
        .doesNotContain("Must not load");
  }
}
