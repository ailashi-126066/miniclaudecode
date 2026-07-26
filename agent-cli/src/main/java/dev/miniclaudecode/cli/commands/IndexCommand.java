package dev.miniclaudecode.cli.commands;

import dev.miniclaudecode.cli.CliActions;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "index",
    description = {"Build or incrementally update the AST-aware Lucene code index"})
public final class IndexCommand implements Callable<Integer> {
  private final CliActions actions;

  @Option(
      names = {"-w", "--workspace"},
      defaultValue = ".")
  private Path workspace;

  public IndexCommand(CliActions actions) {
    this.actions = actions;
  }

  public Integer call() {
    return this.actions.index(this.workspace);
  }
}
