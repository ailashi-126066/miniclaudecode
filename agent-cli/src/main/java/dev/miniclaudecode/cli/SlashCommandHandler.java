package dev.miniclaudecode.cli;

import dev.miniclaudecode.cli.commands.SlashCommand;

@FunctionalInterface
public interface SlashCommandHandler {

  String execute(SlashCommand command);
}
