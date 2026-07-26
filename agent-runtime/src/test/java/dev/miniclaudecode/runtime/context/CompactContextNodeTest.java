package dev.miniclaudecode.runtime.context;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.runtime.node.CompactContextNode;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class CompactContextNodeTest {
  @Test
  void clearsAnOverflowFailureAndReturnsRunnableReducedState() {
    List<AgentMessage> messages = new ArrayList<>();

    for (int index = 0; index < 12; index++) {
      messages.add(new UserMessage("constraint " + index));
    }

    MiniClaudeState state =
        new MiniClaudeState(
            Map.of(
                "request",
                request(messages),
                "messages",
                messages,
                "error",
                "context window exceeded",
                "status",
                AgentStatus.FAILED));
    Map<String, Object> update =
        (Map<String, Object>)
            new CompactContextNode(new DeterministicContextReducer(4, 64)).apply(state).join();
    Assertions.assertThat(update.get("error")).isEqualTo("");
    Assertions.assertThat(update.get("status")).isEqualTo(AgentStatus.RUNNING);
    Assertions.assertThat((List) update.get("messages")).hasSizeLessThan(messages.size());
    Assertions.assertThat(update.get("compactionCount")).isEqualTo(1);
  }

  private static ModelRequest request(List<AgentMessage> messages) {
    return new ModelRequest("test", "fake", messages, List.of(), false, 100, Map.of());
  }
}
