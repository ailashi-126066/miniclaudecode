package dev.miniclaudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.Parser;
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentCompleterTest {

  @TempDir Path workspace;

  @Test
  void completesCommandsProviderModelsToolsAndWorkspacePaths() throws Exception {
    Files.createDirectories(workspace.resolve("src/main/java"));
    Files.writeString(workspace.resolve("src/main/java/App.java"), "class App {}");
    AgentCompleter completer =
        new AgentCompleter(
            workspace,
            () -> List.of("anthropic", "openai"),
            () -> List.of("claude-sonnet-4", "gpt-4.1"),
            () -> List.of("workspace:read", "shell:run"));

    assertThat(complete(completer, "/th")).containsExactly("/thinking");
    assertThat(complete(completer, "/us")).containsExactly("/usage");
    assertThat(complete(completer, "/provider o")).containsExactly("openai");
    assertThat(complete(completer, "/model c")).containsExactly("claude-sonnet-4");
    assertThat(complete(completer, "/config s")).containsExactly("setup");
    assertThat(complete(completer, "work")).containsExactly("workspace:read");
    assertThat(complete(completer, "@src/main/j"))
        .contains("@src/main/java", "@src/main/java/App.java");
  }

  @Test
  void doesNotExposeInternalRagSlashCommands() {
    AgentCompleter completer = new AgentCompleter(workspace, List::of, List::of, List::of);

    assertThat(complete(completer, "/")).doesNotContain("/index", "/rag");
  }

  private static List<String> complete(AgentCompleter completer, String buffer) {
    var parsed = new DefaultParser().parse(buffer, buffer.length(), Parser.ParseContext.COMPLETE);
    List<Candidate> candidates = new ArrayList<>();
    completer.complete(null, parsed, candidates);
    return candidates.stream().map(Candidate::value).toList();
  }
}
