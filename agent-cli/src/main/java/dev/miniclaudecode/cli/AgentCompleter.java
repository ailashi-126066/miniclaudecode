package dev.miniclaudecode.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

public final class AgentCompleter implements Completer {

  public static final List<String> SLASH_COMMANDS =
      List.of(
          "/help",
          "/status",
          "/usage",
          "/provider",
          "/model",
          "/thinking",
          "/tools",
          "/compact",
          "/checkpoints",
          "/restore",
          "/recovery",
          "/undo",
          "/redo",
          "/sessions",
          "/resume",
          "/mcp",
          "/skills",
          "/config",
          "/exit");

  private final Path workspace;
  private final Supplier<? extends Collection<String>> providers;
  private final Supplier<? extends Collection<String>> models;
  private final Supplier<? extends Collection<String>> tools;

  public AgentCompleter(
      Path workspace,
      Supplier<? extends Collection<String>> providers,
      Supplier<? extends Collection<String>> models,
      Supplier<? extends Collection<String>> tools) {
    this.workspace =
        Objects.requireNonNull(workspace, "workspace must not be null")
            .toAbsolutePath()
            .normalize();
    this.providers = Objects.requireNonNull(providers, "providers must not be null");
    this.models = Objects.requireNonNull(models, "models must not be null");
    this.tools = Objects.requireNonNull(tools, "tools must not be null");
  }

  @Override
  public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    String word = line.word();
    if (line.wordIndex() == 0 && word.startsWith("/")) {
      addMatching(candidates, SLASH_COMMANDS, word);
      return;
    }
    String command = line.words().isEmpty() ? "" : line.words().getFirst();
    switch (command) {
      case "/provider" -> addMatching(candidates, providers.get(), word);
      case "/model" -> addMatching(candidates, models.get(), word);
      case "/thinking" -> addMatching(candidates, List.of("on", "off"), word);
      case "/config" -> addMatching(candidates, List.of("setup"), word);
      default -> {
        if (word.startsWith("@")) {
          completePath(candidates, word.substring(1));
        } else {
          addMatching(candidates, tools.get(), word);
        }
      }
    }
  }

  private void completePath(List<Candidate> candidates, String prefix) {
    if (!Files.isDirectory(workspace)) {
      return;
    }
    String normalizedPrefix = prefix.replace('\\', '/');
    try (var paths = Files.walk(workspace, 4)) {
      paths
          .filter(path -> !path.equals(workspace))
          .map(workspace::relativize)
          .map(Path::toString)
          .map(path -> path.replace('\\', '/'))
          .filter(path -> path.startsWith(normalizedPrefix))
          .sorted()
          .limit(100)
          .map(path -> new Candidate("@" + path))
          .forEach(candidates::add);
    } catch (IOException ignored) {
      // Completion remains best effort when the workspace changes concurrently.
    }
  }

  private static void addMatching(
      List<Candidate> candidates, Collection<String> values, String prefix) {
    values.stream()
        .filter(Objects::nonNull)
        .filter(value -> value.startsWith(prefix))
        .distinct()
        .sorted()
        .map(Candidate::new)
        .forEach(candidates::add);
  }
}
