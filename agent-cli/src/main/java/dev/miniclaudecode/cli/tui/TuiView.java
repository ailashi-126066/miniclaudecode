package dev.miniclaudecode.cli.tui;

import java.util.ArrayList;
import java.util.List;

/** Renders a compact full-screen conversation view without owning any business state. */
public final class TuiView {
  public String render(TuiState state, String workspace) {
    int width = state.width();
    int inner = Math.max(20, width - 2);
    String border = "─".repeat(inner);
    List<String> content = new ArrayList<>();
    state.transcript().forEach(line -> content.addAll(wrap(line, inner)));
    if (!state.thinking().isBlank()) {
      content.addAll(wrap("Thinking: " + state.thinking(), inner));
    }
    if (!state.streamText().isBlank()) {
      content.addAll(wrap("Assistant: " + state.streamText(), inner));
    }
    state
        .approvalPreview()
        .ifPresent(preview -> content.addAll(wrap("Proposed change:\n" + preview, inner)));

    List<String> dashboard = dashboard(state.dashboard(), inner);
    int available = Math.max(3, state.height() - 8 - dashboard.size());
    int from = Math.max(0, content.size() - available);
    StringBuilder output = new StringBuilder();
    output.append("MiniClaudeCode  ").append(workspace).append('\n');
    output.append(border).append('\n');
    for (int index = from; index < content.size(); index++) {
      output.append(content.get(index)).append('\n');
    }
    for (int index = content.size() - from; index < available; index++) {
      output.append('\n');
    }
    output.append(border).append('\n');
    dashboard.forEach(line -> output.append(line).append('\n'));
    output.append(border).append('\n');
    output.append(state.status()).append('\n');
    if (state.approval().isPresent()) {
      output
          .append("Approve ")
          .append(state.approval().orElseThrow().target())
          .append("? [y] allow  [n] reject");
    } else if (state.running()) {
      output.append("[Ctrl+C to cancel]");
    } else {
      output.append("> ").append(state.input()).append("█");
    }
    return output.toString();
  }

  private static List<String> dashboard(TuiDashboard value, int width) {
    return List.of(
            "Session: " + value.session(),
            "Plan: " + oneLine(value.plan()),
            "Usage: " + oneLine(value.usage()),
            "Background: " + oneLine(value.background()),
            "Team: " + oneLine(value.teams()))
        .stream()
        .map(line -> line.length() <= width ? line : line.substring(0, width - 1) + "…")
        .toList();
  }

  private static String oneLine(String value) {
    return value.replaceAll("\\R+", " | ");
  }

  private static List<String> wrap(String value, int width) {
    List<String> lines = new ArrayList<>();
    for (String raw : value.split("\\R", -1)) {
      if (raw.isEmpty()) {
        lines.add("");
        continue;
      }
      for (int offset = 0; offset < raw.length(); offset += width) {
        lines.add(raw.substring(offset, Math.min(raw.length(), offset + width)));
      }
    }
    return lines;
  }
}
