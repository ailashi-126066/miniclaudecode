package dev.miniclaudecode.cli.commands;

import dev.miniclaudecode.cli.CliActions;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
    name = "rag",
    description = {
      "Run retrieval diagnostics: rag <query>, rag explain <query>, rag stats, or rag eval <jsonl>"
    })
public final class RagCommand implements Callable<Integer> {
  private final CliActions actions;

  @Option(
      names = {"-w", "--workspace"},
      defaultValue = ".")
  private Path workspace;

  @Parameters(arity = "1..*", paramLabel = "MODE_OR_QUERY")
  private List<String> query;

  public RagCommand(CliActions actions) {
    this.actions = actions;
  }

  public Integer call() {
    return this.actions.rag(this.workspace, String.join(" ", this.query));
  }
}
