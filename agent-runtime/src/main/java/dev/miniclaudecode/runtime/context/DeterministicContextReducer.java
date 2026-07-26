package dev.miniclaudecode.runtime.context;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.AssistantMessage;
import dev.miniclaudecode.domain.message.AgentMessage.SystemMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DeterministicContextReducer {
  private final int recentMessageCount;
  private final int inlineToolCharacters;

  public DeterministicContextReducer() {
    this(8, 512);
  }

  public DeterministicContextReducer(int recentMessageCount, int inlineToolCharacters) {
    if (recentMessageCount >= 2 && inlineToolCharacters >= 32) {
      this.recentMessageCount = recentMessageCount;
      this.inlineToolCharacters = inlineToolCharacters;
    } else {
      throw new IllegalArgumentException("context reduction limits are invalid");
    }
  }

  public List<AgentMessage> reduce(List<AgentMessage> messages) {
    Objects.requireNonNull(messages, "messages must not be null");
    if (messages.size() <= this.recentMessageCount) {
      return this.reduceLargeOldToolResults(messages, 0);
    } else {
      int cutoff = safeCutoff(messages, messages.size() - this.recentMessageCount);
      if (cutoff <= 0) {
        return this.reduceLargeOldToolResults(messages, 3);
      }

      List<AgentMessage> older = messages.subList(0, cutoff);
      List<AgentMessage> reduced = new ArrayList<>();
      messages.stream()
          .limit((long) cutoff)
          .filter(SystemMessage.class::isInstance)
          .forEach(reduced::add);
      String summary = summary(older);
      if (!summary.isBlank()) {
        reduced.add(new SystemMessage("Conversation compact summary:\n" + summary));
      }

      reduced.addAll(this.reduceLargeOldToolResults(messages.subList(cutoff, messages.size()), 3));
      return List.copyOf(reduced);
    }
  }

  /**
   * Moves a proposed cutoff so that the retained tail never begins in the middle of a tool-call
   * group.
   *
   * <p>Every provider requires each {@code tool_result} to be preceded by the {@code tool_use} that
   * produced it. A naive {@code size - recentMessageCount} split lands mid-group whenever an
   * assistant message issued more than one tool call, which makes the very next request fail with
   * "tool_result block(s) provided when previous message does not contain any tool_use blocks" —
   * and because compaction only runs once per turn, that failure is terminal.
   *
   * <p>The cutoff is first walked backwards to the assistant message that owns the group, which
   * keeps strictly more context. If the whole prefix turns out to be tool results (defensive; the
   * first message is always a system or user message in practice) the cutoff is walked forwards
   * instead, dropping the orphans rather than emitting them.
   */
  private static int safeCutoff(List<AgentMessage> messages, int proposed) {
    int cutoff = Math.max(0, Math.min(proposed, messages.size()));
    int backwards = cutoff;
    while (backwards > 0 && messages.get(backwards) instanceof ToolMessage) {
      backwards--;
    }
    if (backwards > 0 || !(messages.get(0) instanceof ToolMessage)) {
      return backwards;
    }

    int forwards = cutoff;
    while (forwards < messages.size() && messages.get(forwards) instanceof ToolMessage) {
      forwards++;
    }
    return forwards;
  }

  private List<AgentMessage> reduceLargeOldToolResults(
      List<AgentMessage> messages, int protectedTail) {
    List<AgentMessage> reduced = new ArrayList<>(messages.size());
    int replaceBefore = Math.max(0, messages.size() - protectedTail);

    for (int index = 0; index < messages.size(); index++) {
      AgentMessage message = messages.get(index);
      if (index < replaceBefore
          && message instanceof ToolMessage tool
          && tool.text().length() > this.inlineToolCharacters) {
        String reference = extractReference(tool.text());
        reduced.add(
            new ToolMessage(
                tool.toolCallId(),
                tool.qualifiedToolName(),
                "[older tool output omitted; reference=" + reference + "]",
                tool.error()));
        continue;
      }

      reduced.add(message);
    }

    return List.copyOf(reduced);
  }

  private static String summary(List<AgentMessage> messages) {
    List<String> objectives = new ArrayList<>();
    List<String> decisions = new ArrayList<>();
    Set<String> changedFiles = new LinkedHashSet<>();
    List<String> verification = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    List<String> remaining = new ArrayList<>();

    for (AgentMessage message : messages) {
      if (message instanceof UserMessage user) {
        objectives.add(abbreviate(user.text(), 240));
      } else {
        if (message instanceof ToolMessage) {
          ToolMessage tool = (ToolMessage) message;
          if (tool.error()) {
            failures.add(tool.qualifiedToolName() + ": " + abbreviate(tool.text(), 200));
            continue;
          }
        }

        if (message instanceof ToolMessage) {
          ToolMessage tool = (ToolMessage) message;
          if (isMutation(tool.qualifiedToolName())) {
            changedFiles.add(extractChangedTarget(tool.text()));
            continue;
          }
        }

        if (message instanceof ToolMessage) {
          ToolMessage tool = (ToolMessage) message;
          if ("shell:run".equals(tool.qualifiedToolName())) {
            verification.add(abbreviate(tool.text(), 220));
            continue;
          }
        }

        if (message instanceof ToolMessage) {
          ToolMessage tool = (ToolMessage) message;
          if ("task:todo".equals(tool.qualifiedToolName())) {
            remaining.add(abbreviate(tool.text(), 300));
            continue;
          }
        }

        if (message instanceof AssistantMessage) {
          AssistantMessage assistant = (AssistantMessage) message;
          if (assistant.toolCalls().isEmpty() && !assistant.text().isBlank()) {
            decisions.add(abbreviate(assistant.text(), 220));
          }
        }
      }
    }

    List<String> sections = new ArrayList<>();
    addSection(sections, "Objective", objectives(objectives));
    addSection(sections, "Decisions and outcomes", tail(decisions, 3));
    addSection(sections, "Changed files", new ArrayList<>(changedFiles));
    addSection(sections, "Verification", tail(verification, 3));
    addSection(sections, "Failed attempts", tail(failures, 3));
    addSection(sections, "Remaining task state", tail(remaining, 1));
    return String.join("\n", sections);
  }

  private static boolean isMutation(String name) {
    return "workspace:write".equals(name)
        || "workspace:edit".equals(name)
        || "workspace:apply_patch".equals(name);
  }

  private static String extractChangedTarget(String text) {
    String prefix = "Applied approved change to ";
    String normalized = text.replaceAll("\\s+", " ").trim();
    return normalized.startsWith(prefix)
        ? normalized.substring(prefix.length())
        : abbreviate(text, 160);
  }

  private static void addSection(List<String> sections, String title, List<String> entries) {
    if (!entries.isEmpty()) {
      sections.add(title + ":");
      entries.forEach(entry -> sections.add("- " + entry));
    }
  }

  private static List<String> tail(List<String> values, int maximum) {
    return values.subList(Math.max(0, values.size() - maximum), values.size());
  }

  private static List<String> objectives(List<String> values) {
    if (values.size() <= 3) {
      return values;
    } else {
      Set<String> selected = new LinkedHashSet<>();
      selected.add(values.getFirst());
      selected.addAll(tail(values, 2));
      return List.copyOf(selected);
    }
  }

  private static String extractReference(String text) {
    Matcher matcher = Pattern.compile("sha256:[0-9a-f]{64}").matcher(text);
    return matcher.find() ? matcher.group() : "sha256:" + sha256(text).substring(0, 16);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException var2) {
      throw new IllegalStateException("SHA-256 is unavailable", var2);
    }
  }

  private static String abbreviate(String text, int maximum) {
    String normalized = text.replaceAll("\\s+", " ").trim();
    return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum) + "...";
  }
}
