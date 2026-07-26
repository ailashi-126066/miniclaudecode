package dev.miniclaudecode.cli;

import java.util.List;
import java.util.Objects;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

public final class ReplHeader {

  private static final AttributedStyle MARK =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold();
  private static final AttributedStyle TITLE = AttributedStyle.DEFAULT.bold();
  private static final AttributedStyle FRAME =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT).faint();
  private static final AttributedStyle INFO =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
  private static final AttributedStyle LABEL = AttributedStyle.DEFAULT.faint();

  private ReplHeader() {}

  public static List<String> render(
      Terminal terminal, String provider, String model, boolean thinking, String workspace) {
    Objects.requireNonNull(terminal, "terminal must not be null");
    AttributedStringBuilder title =
        new AttributedStringBuilder()
            .append("✳ ", MARK)
            .append("MiniClaudeCode", TITLE)
            .append("  Java terminal coding agent", LABEL);
    AttributedStringBuilder providerLine =
        new AttributedStringBuilder()
            .append(provider, INFO)
            .append(" · ", LABEL)
            .append(model, INFO)
            .append(" · thinking " + (thinking ? "on" : "off"), LABEL);
    AttributedStringBuilder workspaceLine = new AttributedStringBuilder().append(workspace, LABEL);
    AttributedStringBuilder helpLine =
        new AttributedStringBuilder().append("type /help for commands · /exit to quit", LABEL);
    int width =
        2
            + List.of(title, providerLine, workspaceLine, helpLine).stream()
                .mapToInt(AttributedStringBuilder::columnLength)
                .max()
                .orElse(40);
    String top = "┌" + "─".repeat(width) + "┐";
    String bottom = "└" + "─".repeat(width) + "┘";
    return List.of(
        new AttributedString(top, FRAME).toAnsi(terminal),
        boxed(terminal, width, title),
        boxed(terminal, width, providerLine),
        boxed(terminal, width, workspaceLine),
        boxed(terminal, width, helpLine),
        new AttributedString(bottom, FRAME).toAnsi(terminal));
  }

  private static String boxed(Terminal terminal, int width, AttributedStringBuilder content) {
    int padding = Math.max(0, width - 2 - content.columnLength());
    return new AttributedStringBuilder()
        .append("│ ", FRAME)
        .append(content.toAttributedString())
        .append(" ".repeat(padding))
        .append(" │", FRAME)
        .toAnsi(terminal);
  }
}
