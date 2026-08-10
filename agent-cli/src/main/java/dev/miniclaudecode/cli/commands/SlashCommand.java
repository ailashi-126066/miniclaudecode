package dev.miniclaudecode.cli.commands;

import java.util.Objects;
import java.util.Optional;

public sealed interface SlashCommand
    permits SlashCommand.Help,
        SlashCommand.Status,
        SlashCommand.Usage,
        SlashCommand.Provider,
        SlashCommand.Model,
        SlashCommand.Thinking,
        SlashCommand.Tools,
        SlashCommand.Compact,
        SlashCommand.Checkpoints,
        SlashCommand.Restore,
        SlashCommand.Recovery,
        SlashCommand.Undo,
        SlashCommand.Redo,
        SlashCommand.Sessions,
        SlashCommand.Resume,
        SlashCommand.Mcp,
        SlashCommand.Skills,
        SlashCommand.PlanView,
        SlashCommand.Memory,
        SlashCommand.Config {
  String name();

  private static Optional<String> normalized(Optional<String> value, String field) {
    return Objects.requireNonNull(value, field + " must not be null")
        .map(String::trim)
        .filter(text -> !text.isEmpty());
  }

  private static String requireText(String value, String field) {
    if (value != null && !value.isBlank()) {
      return value.trim();
    } else {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  public static record Compact() implements SlashCommand {
    @Override
    public String name() {
      return "compact";
    }
  }

  public static record Checkpoints() implements SlashCommand {
    @Override
    public String name() {
      return "checkpoints";
    }
  }

  public static record Config(boolean setup) implements SlashCommand {
    public Config() {
      this(false);
    }

    @Override
    public String name() {
      return "config";
    }
  }

  public static record Help() implements SlashCommand {
    @Override
    public String name() {
      return "help";
    }
  }

  public static record Mcp() implements SlashCommand {
    @Override
    public String name() {
      return "mcp";
    }
  }

  public static record PlanView(String action, Optional<String> stepId) implements SlashCommand {
    public PlanView {
      action = SlashCommand.requireText(action, "action").toLowerCase(java.util.Locale.ROOT);
      stepId = SlashCommand.normalized(stepId, "stepId");
    }

    @Override
    public String name() {
      return "plan";
    }
  }

  public static record Memory(String action, Optional<String> value) implements SlashCommand {
    public Memory {
      action = SlashCommand.requireText(action, "action").toLowerCase(java.util.Locale.ROOT);
      value = SlashCommand.normalized(value, "value");
    }

    @Override
    public String name() {
      return "memory";
    }
  }

  public static record Model(Optional<String> model) implements SlashCommand {
    public Model(Optional<String> model) {
      model = SlashCommand.normalized(model, "model");
      this.model = model;
    }

    @Override
    public String name() {
      return "model";
    }
  }

  public static record Provider(Optional<String> profile) implements SlashCommand {
    public Provider(Optional<String> profile) {
      profile = SlashCommand.normalized(profile, "profile");
      this.profile = profile;
    }

    @Override
    public String name() {
      return "provider";
    }
  }

  public static record Resume(String sessionId) implements SlashCommand {
    public Resume(String sessionId) {
      sessionId = SlashCommand.requireText(sessionId, "sessionId");
      this.sessionId = sessionId;
    }

    @Override
    public String name() {
      return "resume";
    }
  }

  public static record Restore(String revision, boolean apply) implements SlashCommand {
    public Restore(String revision) {
      this(revision, false);
    }

    public Restore {
      revision = SlashCommand.requireText(revision, "revision");
    }

    @Override
    public String name() {
      return "restore";
    }
  }

  public static record Recovery() implements SlashCommand {
    @Override
    public String name() {
      return "recovery";
    }
  }

  public static record Undo() implements SlashCommand {
    @Override
    public String name() {
      return "undo";
    }
  }

  public static record Redo() implements SlashCommand {
    @Override
    public String name() {
      return "redo";
    }
  }

  public static record Sessions() implements SlashCommand {
    @Override
    public String name() {
      return "sessions";
    }
  }

  public static record Skills() implements SlashCommand {
    @Override
    public String name() {
      return "skills";
    }
  }

  public static record Status() implements SlashCommand {
    @Override
    public String name() {
      return "status";
    }
  }

  public static record Thinking(boolean enabled) implements SlashCommand {
    @Override
    public String name() {
      return "thinking";
    }
  }

  public static record Tools() implements SlashCommand {
    @Override
    public String name() {
      return "tools";
    }
  }

  public static record Usage() implements SlashCommand {
    @Override
    public String name() {
      return "usage";
    }
  }
}
