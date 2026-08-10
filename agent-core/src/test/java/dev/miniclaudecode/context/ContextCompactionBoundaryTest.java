package dev.miniclaudecode.context;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.AssistantMessage;
import dev.miniclaudecode.domain.message.AgentMessage.SystemMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.tool.ToolCall;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Compaction must never emit a {@code tool_result} whose producing {@code tool_use} was dropped.
 *
 * <p>A plain {@code size - recentMessageCount} split lands in the middle of a tool-call group
 * whenever an assistant message issued more than one call. The provider then rejects the very next
 * request with "tool_result block(s) provided when previous message does not contain any tool_use
 * blocks", and because compaction is allowed only once per turn the failure is terminal.
 *
 * <p>The pre-existing suite used only {@link UserMessage}s, and a strictly alternating
 * one-call-per-assistant transcript happens to be parity-safe, which is why this went unnoticed.
 */
class ContextCompactionBoundaryTest {

  /** The provider contract, expressed as an assertion. */
  private static void assertNoOrphanedToolResult(List<AgentMessage> messages) {
    Set<String> issued = new HashSet<>();
    for (AgentMessage message : messages) {
      if (message instanceof AssistantMessage assistant) {
        assistant.toolCalls().forEach(call -> issued.add(call.toolCallId()));
      } else if (message instanceof ToolMessage tool) {
        assertThat(issued)
            .as("tool_result %s has no preceding tool_use after compaction", tool.toolCallId())
            .contains(tool.toolCallId());
      }
    }
  }

  private static AssistantMessage assistantWith(String... toolCallIds) {
    List<ToolCall> calls = new ArrayList<>();
    for (String id : toolCallIds) {
      calls.add(new ToolCall(id, "workspace:read", "{}"));
    }
    return new AssistantMessage("", Optional.empty(), List.copyOf(calls), Map.of());
  }

  private static ToolMessage resultFor(String toolCallId) {
    return new ToolMessage(toolCallId, "workspace:read", "content for " + toolCallId, false);
  }

  private static List<AgentMessage> transcript(int groups, int callsPerGroup) {
    List<AgentMessage> messages = new ArrayList<>();
    messages.add(new SystemMessage("system"));
    messages.add(new UserMessage("go"));
    for (int group = 0; group < groups; group++) {
      String[] ids = new String[callsPerGroup];
      for (int call = 0; call < callsPerGroup; call++) {
        ids[call] = "g" + group + "c" + call;
      }
      messages.add(assistantWith(ids));
      for (String id : ids) {
        messages.add(resultFor(id));
      }
    }
    return messages;
  }

  @Test
  @DisplayName("a split landing inside a parallel tool-call group leaves no orphan")
  void parallelToolCallGroupIsNeverSplit() {
    List<AgentMessage> messages =
        new ArrayList<>(
            List.of(
                new SystemMessage("system"),
                new UserMessage("fix the failing test"),
                assistantWith("tcA", "tcB"),
                resultFor("tcA"),
                resultFor("tcB"),
                assistantWith("tcC"),
                resultFor("tcC"),
                assistantWith("tcD"),
                resultFor("tcD"),
                assistantWith("tcE"),
                resultFor("tcE"),
                assistantWith("tcF"),
                resultFor("tcF"),
                assistantWith("tcG")));

    assertNoOrphanedToolResult(new DeterministicContextReducer().reduce(messages));
  }

  @DisplayName("no fan-out width and transcript length combination produces an orphan")
  @ParameterizedTest(name = "{0} groups of {1} parallel calls")
  @CsvSource({
    "1,1", "2,1", "3,1", "5,1", "9,1",
    "1,2", "2,2", "3,2", "5,2", "9,2",
    "1,3", "2,3", "3,3", "5,3", "9,3",
    "1,4", "2,4", "3,4", "5,4", "9,4"
  })
  void compactionNeverOrphansAToolResult(int groups, int callsPerGroup) {
    assertNoOrphanedToolResult(
        new DeterministicContextReducer().reduce(transcript(groups, callsPerGroup)));
  }

  @Test
  @DisplayName("compaction still actually reduces the transcript")
  void compactionStillCompacts() {
    List<AgentMessage> messages = new ArrayList<>();
    messages.add(new SystemMessage("system"));
    for (int index = 0; index < 40; index++) {
      messages.add(new UserMessage("message " + index));
    }
    assertThat(new DeterministicContextReducer().reduce(messages)).hasSizeLessThan(messages.size());
  }
}
