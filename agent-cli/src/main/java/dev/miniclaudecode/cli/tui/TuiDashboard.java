package dev.miniclaudecode.cli.tui;

import java.util.Objects;

/** Read-only operational state displayed alongside the conversation. */
public record TuiDashboard(
    String session, String plan, String usage, String background, String teams) {
  public TuiDashboard {
    session = text(session);
    plan = text(plan);
    usage = text(usage);
    background = text(background);
    teams = text(teams);
  }

  public static TuiDashboard empty() {
    return new TuiDashboard("-", "(none)", "0 requests", "(none)", "(none)");
  }

  private static String text(String value) {
    return Objects.requireNonNull(value, "dashboard value must not be null").strip();
  }
}
