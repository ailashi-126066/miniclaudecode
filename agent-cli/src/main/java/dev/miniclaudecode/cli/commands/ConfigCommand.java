package dev.miniclaudecode.cli.commands;

import dev.miniclaudecode.cli.CliActions;
import java.util.Objects;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(
    name = "config",
    description = {"Interactively configure a model provider"})
public final class ConfigCommand implements Callable<Integer> {
  private final CliActions actions;

  public ConfigCommand(CliActions actions) {
    this.actions = Objects.requireNonNull(actions, "actions must not be null");
  }

  public Integer call() {
    return this.actions.configure();
  }
}
