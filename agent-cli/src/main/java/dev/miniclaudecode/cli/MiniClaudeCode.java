package dev.miniclaudecode.cli;

import dev.miniclaudecode.cli.app.DefaultCliActions;
import dev.miniclaudecode.cli.commands.ConfigCommand;
import dev.miniclaudecode.cli.commands.IndexCommand;
import dev.miniclaudecode.cli.commands.RagCommand;
import dev.miniclaudecode.cli.commands.RunCommand;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "miniclaude",
    description = "A Java 21 terminal coding agent",
    mixinStandardHelpOptions = true,
    version = "MiniClaudeCode 0.1.0")
public final class MiniClaudeCode implements Callable<Integer> {

  private final CliActions actions;

  @Option(
      names = {"-w", "--workspace"},
      defaultValue = ".")
  private Path workspace;

  public MiniClaudeCode(CliActions actions) {
    this.actions = Objects.requireNonNull(actions, "actions must not be null");
  }

  public CommandLine commandLine() {
    return new CommandLine(this)
        .addSubcommand("config", new ConfigCommand(actions))
        .addSubcommand("run", new RunCommand(actions))
        .addSubcommand("index", new IndexCommand(actions))
        .addSubcommand("rag", new RagCommand(actions));
  }

  @Override
  public Integer call() {
    return actions.interactive(workspace);
  }

  public static void main(String[] args) {
    int exitCode = new MiniClaudeCode(new DefaultCliActions()).commandLine().execute(args);
    System.exit(exitCode);
  }
}
