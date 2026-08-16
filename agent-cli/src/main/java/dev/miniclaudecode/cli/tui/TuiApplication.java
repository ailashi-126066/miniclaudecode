package dev.miniclaudecode.cli.tui;

import com.williamcallahan.tui4j.compat.bubbletea.Program;
import dev.miniclaudecode.cli.SlashCommandHandler;
import dev.miniclaudecode.cli.SlashCommandParser;
import dev.miniclaudecode.cli.TurnHandler;
import java.util.Objects;
import java.util.function.Supplier;

/** Owns only the full-screen terminal lifecycle. */
public final class TuiApplication {
  private final TurnHandler turns;
  private final SlashCommandHandler commands;
  private final String workspace;
  private final Supplier<TuiDashboard> dashboard;

  public TuiApplication(
      TurnHandler turns,
      SlashCommandHandler commands,
      String workspace,
      Supplier<TuiDashboard> dashboard) {
    this.turns = Objects.requireNonNull(turns, "turns must not be null");
    this.commands = Objects.requireNonNull(commands, "commands must not be null");
    this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
    this.dashboard = Objects.requireNonNull(dashboard, "dashboard must not be null");
  }

  public void run() {
    TuiModel model =
        new TuiModel(
            this.turns, new SlashCommandParser(), this.commands, this.workspace, this.dashboard);
    Program program = new Program(model).withAltScreen();
    model.attach(program);
    program.run();
  }
}
