package dev.miniclaudecode.domain.model;

import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ModelContractsTest {
  @Test
  void modelRequestSnapshotsMessagesToolsAndAttributes() {
    List<AgentMessage> messages = new ArrayList<>(List.of(new UserMessage("hello")));
    List<ToolDescriptor> tools =
        new ArrayList<>(
            List.of(
                new ToolDescriptor(
                    "workspace", "read", "read", "{\"type\":\"object\"}", RiskLevel.LOW)));
    Map<String, Object> attributes = new HashMap<>(Map.of("maxRetries", 5));
    ModelRequest request =
        new ModelRequest(" profile ", " model ", messages, tools, true, 4096, attributes);
    messages.clear();
    tools.clear();
    attributes.clear();

    Assertions.assertThat(request.providerProfile()).isEqualTo("profile");
    Assertions.assertThat(request.modelName()).isEqualTo("model");
    Assertions.assertThat(request.messages()).hasSize(1);
    Assertions.assertThat(request.tools()).hasSize(1);
    Assertions.assertThat(request.attributes()).containsEntry("maxRetries", 5);
    Assertions.assertThatThrownBy(
            () -> new ModelRequest("p", "m", List.of(), List.of(), false, 0, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void streamEventsCoverTextThinkingToolsUsageCompletionAndFailure() {
    ToolCall call = new ToolCall("call-1", "workspace:read", "{}");
    Assertions.assertThat(new ModelStreamEvent.TextDelta("x").text()).isEqualTo("x");
    Assertions.assertThat(new ModelStreamEvent.ThinkingDelta("why").text()).isEqualTo("why");
    Assertions.assertThat(new ModelStreamEvent.ToolCallStarted("call-1", "workspace:read"))
        .extracting(ModelStreamEvent.ToolCallStarted::toolCallId)
        .isEqualTo("call-1");
    Assertions.assertThat(new ModelStreamEvent.ToolCallDelta("call-1", "{\"path\":"))
        .extracting(ModelStreamEvent.ToolCallDelta::argumentsFragment)
        .isEqualTo("{\"path\":");
    Assertions.assertThat(new ModelStreamEvent.ToolCallCompleted(call).toolCall()).isEqualTo(call);
    Assertions.assertThat(new ModelStreamEvent.UsageReported(20, 5, 10, 2).cacheReadTokens())
        .isEqualTo(10);
    Assertions.assertThat(new ModelStreamEvent.Completed("stop", Map.of("id", "response")))
        .extracting(ModelStreamEvent.Completed::finishReason)
        .isEqualTo("stop");
    Assertions.assertThat(new ModelStreamEvent.Failed("timeout", "late", true).retryable())
        .isTrue();
    Assertions.assertThatThrownBy(() -> new ModelStreamEvent.TextDelta(""))
        .isInstanceOf(IllegalArgumentException.class);
    Assertions.assertThatThrownBy(() -> new ModelStreamEvent.UsageReported(2, 1, 2, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
