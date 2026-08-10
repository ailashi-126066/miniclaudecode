package dev.miniclaudecode.cli;

import dev.miniclaudecode.cli.commands.SlashCommand;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class SessionCommandHandler implements SlashCommandHandler {

  private static final String HELP =
      String.join(
          System.lineSeparator(),
          "/help             Show this help",
          "/status           Show session, provider, model and thinking status",
          "/usage            Show token usage and provider prompt-cache hit rate",
          "/provider [name]  Show or select a provider profile",
          "/model [name]     Show or select a model",
          "/thinking on|off  Toggle provider thinking summaries",
          "/tools            List available tools",
          "/compact          Compact the current conversation",
          "/checkpoints      List Git snapshots made before each turn",
          "/restore <id>     Preview restoring a Git snapshot",
          "/restore <id> apply  Restore snapshot files after preview",
          "/recovery         Alias for /checkpoints (deprecated)",
          "/undo             Restore the previous Git checkpoint",
          "/redo             Restore the next undone Git checkpoint",
          "/sessions         List saved sessions",
          "/resume <id>      Resume a saved session",
          "/mcp              Show MCP servers",
          "/skills           Show discovered skills",
          "/plan             Show the current durable Plan",
          "/plan history     Show Plan event history",
          "/plan evidence <step-id>  Show evidence for one step",
          "/memory list      List long-term memory",
          "/memory archive <id>  Archive a memory entry",
          "/memory edit <id> <content>  Edit a memory entry",
          "/memory search <query>  Search active long-term memory",
          "/memory export    Export memory as Markdown",
          "/memory clear     Archive all active memory",
          "/config           Show the user configuration path",
          "/config setup     Run the masked provider configuration wizard",
          "/exit             Save history and exit");

  private final Map<String, List<String>> providerModels;
  private final Collection<String> tools;
  private final Supplier<String> sessionStatus;
  private final Supplier<String> usage;
  private final Supplier<String> sessions;
  private final Supplier<String> mcp;
  private final Supplier<String> skills;
  private final Supplier<String> checkpoints;
  private final Runnable compact;
  private final Consumer<String> resume;
  private final java.util.function.Function<SlashCommand.Restore, String> restore;
  private final Supplier<String> undo;
  private final Supplier<String> redo;
  private final Path configFile;
  private java.util.function.Function<SlashCommand.PlanView, String> plan =
      ignored -> "No Plan is available.";
  private java.util.function.Function<SlashCommand.Memory, String> memory =
      ignored -> "Long-term memory is unavailable.";
  private String activeProvider;
  private String activeModel;
  private boolean thinking;

  public SessionCommandHandler(
      Map<String, List<String>> providerModels,
      String activeProvider,
      String activeModel,
      boolean thinking,
      Collection<String> tools,
      Supplier<String> sessionStatus,
      Supplier<String> usage,
      Supplier<String> sessions,
      Supplier<String> mcp,
      Supplier<String> skills,
      Supplier<String> checkpoints,
      Runnable compact,
      Consumer<String> resume,
      java.util.function.Function<SlashCommand.Restore, String> restore,
      Supplier<String> undo,
      Supplier<String> redo,
      Path configFile) {
    this.providerModels = Map.copyOf(providerModels);
    if (!this.providerModels.containsKey(activeProvider)) {
      throw new IllegalArgumentException("unknown active provider: " + activeProvider);
    }
    this.activeProvider = activeProvider;
    this.activeModel = requireModel(activeProvider, activeModel);
    this.thinking = thinking;
    this.tools = List.copyOf(tools);
    this.sessionStatus = Objects.requireNonNull(sessionStatus, "sessionStatus must not be null");
    this.usage = Objects.requireNonNull(usage, "usage must not be null");
    this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
    this.mcp = Objects.requireNonNull(mcp, "mcp must not be null");
    this.skills = Objects.requireNonNull(skills, "skills must not be null");
    this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints must not be null");
    this.compact = Objects.requireNonNull(compact, "compact must not be null");
    this.resume = Objects.requireNonNull(resume, "resume must not be null");
    this.restore = Objects.requireNonNull(restore, "restore must not be null");
    this.undo = Objects.requireNonNull(undo, "undo must not be null");
    this.redo = Objects.requireNonNull(redo, "redo must not be null");
    this.configFile = Objects.requireNonNull(configFile, "configFile must not be null");
  }

  public synchronized SessionCommandHandler withPlanAndMemory(
      java.util.function.Function<SlashCommand.PlanView, String> plan,
      java.util.function.Function<SlashCommand.Memory, String> memory) {
    this.plan = Objects.requireNonNull(plan, "plan must not be null");
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
    return this;
  }

  @Override
  public synchronized String execute(SlashCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    return switch (command) {
      case SlashCommand.Help ignored -> HELP;
      case SlashCommand.Status ignored -> status();
      case SlashCommand.Usage ignored -> usage.get();
      case SlashCommand.Provider provider -> provider(provider);
      case SlashCommand.Model model -> model(model);
      case SlashCommand.Thinking value -> thinking(value);
      case SlashCommand.Tools ignored -> sorted(tools);
      case SlashCommand.Compact ignored -> {
        compact.run();
        yield "Conversation compacted.";
      }
      case SlashCommand.Checkpoints ignored -> checkpoints.get();
      case SlashCommand.Restore restoreCommand -> restore.apply(restoreCommand);
      case SlashCommand.Recovery ignored -> checkpoints.get();
      case SlashCommand.Undo ignored -> undo.get();
      case SlashCommand.Redo ignored -> redo.get();
      case SlashCommand.Sessions ignored -> sessions.get();
      case SlashCommand.Resume value -> {
        resume.accept(value.sessionId());
        yield "Resumed session " + value.sessionId();
      }
      case SlashCommand.Mcp ignored -> mcp.get();
      case SlashCommand.Skills ignored -> skills.get();
      case SlashCommand.PlanView value -> plan.apply(value);
      case SlashCommand.Memory value -> memory.apply(value);
      case SlashCommand.Config config ->
          config.setup()
              ? "Configuration setup is available only in the interactive terminal."
              : configFile.toAbsolutePath().normalize()
                  + System.lineSeparator()
                  + "Run /config setup to edit providers.";
    };
  }

  public synchronized String activeProvider() {
    return activeProvider;
  }

  public synchronized String activeModel() {
    return activeModel;
  }

  public synchronized boolean thinkingEnabled() {
    return thinking;
  }

  private String status() {
    return "Provider: "
        + activeProvider
        + System.lineSeparator()
        + "Model: "
        + activeModel
        + System.lineSeparator()
        + "Thinking: "
        + (thinking ? "on" : "off")
        + System.lineSeparator()
        + sessionStatus.get();
  }

  private String provider(SlashCommand.Provider command) {
    if (command.profile().isEmpty()) {
      return "Active provider: "
          + activeProvider
          + System.lineSeparator()
          + sorted(providerModels.keySet());
    }
    String selected = command.profile().orElseThrow();
    List<String> models = providerModels.get(selected);
    if (models == null) {
      throw new IllegalArgumentException("unknown provider profile: " + selected);
    }
    activeProvider = selected;
    if (!models.contains(activeModel)) {
      activeModel = models.getFirst();
    }
    return "Provider set to " + selected + " (model " + activeModel + ")";
  }

  private String model(SlashCommand.Model command) {
    if (command.model().isEmpty()) {
      return "Active model: "
          + activeModel
          + System.lineSeparator()
          + sorted(providerModels.get(activeProvider));
    }
    activeModel = requireModel(activeProvider, command.model().orElseThrow());
    return "Model set to " + activeModel;
  }

  private String thinking(SlashCommand.Thinking command) {
    thinking = command.enabled();
    return "Thinking summaries " + (thinking ? "enabled" : "disabled");
  }

  private String requireModel(String provider, String model) {
    if (!providerModels.getOrDefault(provider, List.of()).contains(model)) {
      throw new IllegalArgumentException("model is not configured for " + provider + ": " + model);
    }
    return model;
  }

  private static String sorted(Collection<String> values) {
    return values.stream()
        .sorted()
        .reduce((left, right) -> left + System.lineSeparator() + right)
        .orElse("(none)");
  }
}
