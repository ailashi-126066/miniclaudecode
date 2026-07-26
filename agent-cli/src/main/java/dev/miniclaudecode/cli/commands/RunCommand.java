package dev.miniclaudecode.cli.commands;

import dev.miniclaudecode.cli.CliActions;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
    name = "run",
    description = {"Run one non-interactive agent prompt"})
public final class RunCommand implements Callable<Integer> {
  private final CliActions actions;

  @Option(
      names = {"-w", "--workspace"},
      defaultValue = ".")
  private Path workspace;

  @Parameters(arity = "1..*", paramLabel = "PROMPT")
  private List<String> prompt;

  public RunCommand(CliActions actions) {
    this.actions = actions;
  }

  public Integer call() {
    return this.actions.run(this.workspace, String.join(" ", this.prompt));
  }
}
