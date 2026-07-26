package dev.miniclaudecode.cli;

import dev.miniclaudecode.cli.commands.SlashCommand;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public final class SlashCommandParser {

  public SlashCommand parse(String input) {
    if (input == null || input.isBlank()) {
      throw new IllegalArgumentException("command must not be blank");
    }
    String trimmed = input.trim();
    if (!trimmed.startsWith("/")) {
      throw new IllegalArgumentException("slash command must start with '/'");
    }
    String[] tokens =
        Arrays.stream(trimmed.substring(1).split("\\s+"))
            .filter(token -> !token.isBlank())
            .toArray(String[]::new);
    if (tokens.length == 0) {
      throw new IllegalArgumentException("missing slash command name");
    }
    String name = tokens[0].toLowerCase(Locale.ROOT);
    return switch (name) {
      case "help" -> noArguments(tokens, new SlashCommand.Help());
      case "status" -> noArguments(tokens, new SlashCommand.Status());
      case "usage" -> noArguments(tokens, new SlashCommand.Usage());
      case "provider" -> new SlashCommand.Provider(optionalArgument(tokens, "provider"));
      case "model" -> new SlashCommand.Model(optionalArgument(tokens, "model"));
      case "thinking" -> parseThinking(tokens);
      case "tools" -> noArguments(tokens, new SlashCommand.Tools());
      case "compact" -> noArguments(tokens, new SlashCommand.Compact());
      case "sessions" -> noArguments(tokens, new SlashCommand.Sessions());
      case "resume" -> new SlashCommand.Resume(requiredArgument(tokens, "resume"));
      case "mcp" -> noArguments(tokens, new SlashCommand.Mcp());
      case "skills" -> noArguments(tokens, new SlashCommand.Skills());
      case "config" -> parseConfig(tokens);
      default -> throw new IllegalArgumentException("unknown slash command: /" + name);
    };
  }

  public boolean isSlashCommand(String input) {
    return input != null && input.stripLeading().startsWith("/");
  }

  private static SlashCommand parseThinking(String[] tokens) {
    String value = requiredArgument(tokens, "thinking").toLowerCase(Locale.ROOT);
    return switch (value) {
      case "on" -> new SlashCommand.Thinking(true);
      case "off" -> new SlashCommand.Thinking(false);
      default -> throw new IllegalArgumentException("usage: /thinking on|off");
    };
  }

  private static SlashCommand parseConfig(String[] tokens) {
    if (tokens.length == 1) {
      return new SlashCommand.Config();
    }
    if (tokens.length == 2 && tokens[1].equalsIgnoreCase("setup")) {
      return new SlashCommand.Config(true);
    }
    throw new IllegalArgumentException("usage: /config [setup]");
  }

  private static <T extends SlashCommand> T noArguments(String[] tokens, T command) {
    if (tokens.length != 1) {
      throw new IllegalArgumentException("/" + tokens[0] + " does not accept arguments");
    }
    return command;
  }

  private static Optional<String> optionalArgument(String[] tokens, String command) {
    if (tokens.length > 2) {
      throw new IllegalArgumentException("usage: /" + command + " [value]");
    }
    return tokens.length == 2 ? Optional.of(tokens[1]) : Optional.empty();
  }

  private static String requiredArgument(String[] tokens, String command) {
    if (tokens.length != 2) {
      throw new IllegalArgumentException("usage: /" + command + " <value>");
    }
    return tokens[1];
  }
}
