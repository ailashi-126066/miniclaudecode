package dev.miniclaudecode.providers.anthropic;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.output.FinishReason;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.message.AgentMessage.AssistantMessage;
import dev.miniclaudecode.domain.message.AgentMessage.SystemMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.model.ModelStreamEvent.Completed;
import dev.miniclaudecode.domain.model.ModelStreamEvent.TextDelta;
import dev.miniclaudecode.domain.model.ModelStreamEvent.ThinkingDelta;
import dev.miniclaudecode.domain.model.ModelStreamEvent.ToolCallCompleted;
import dev.miniclaudecode.domain.model.ModelStreamEvent.ToolCallDelta;
import dev.miniclaudecode.domain.model.ModelStreamEvent.ToolCallStarted;
import dev.miniclaudecode.domain.model.ModelStreamEvent.UsageReported;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.providers.FlowTestSubscriber;
import dev.miniclaudecode.providers.ProviderSpec;
import dev.miniclaudecode.providers.ProviderSpec.Type;
import dev.miniclaudecode.providers.TestStreamingChatModel;
import dev.miniclaudecode.providers.ThinkingSupport;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class AnthropicModelClientTest {
  @Test
  void mapsMessagesToolsThinkingUsageAndCompletion() {
    TestStreamingChatModel model =
        new TestStreamingChatModel(
            handler -> {
              handler.onPartialThinking(new PartialThinking("checked the workspace"));
              handler.onPartialResponse("I found it");
              handler.onPartialToolCall(
                  PartialToolCall.builder()
                      .index(0)
                      .id("call-1")
                      .name("tool_0_workspace_read")
                      .partialArguments("{\"path\":")
                      .build());
              handler.onPartialToolCall(
                  PartialToolCall.builder()
                      .index(0)
                      .id("call-1")
                      .name("tool_0_workspace_read")
                      .partialArguments("\"pom.xml\"}")
                      .build());
              handler.onCompleteToolCall(
                  new CompleteToolCall(
                      0,
                      ToolExecutionRequest.builder()
                          .id("call-1")
                          .name("tool_0_workspace_read")
                          .arguments("{\"path\":\"pom.xml\"}")
                          .build()));
              handler.onCompleteResponse(
                  ChatResponse.builder()
                      .aiMessage(AiMessage.from("I found it"))
                      .id("response-1")
                      .modelName("claude-test")
                      .tokenUsage(
                          AnthropicTokenUsage.builder()
                              .inputTokenCount(12)
                              .outputTokenCount(7)
                              .cacheCreationInputTokens(3)
                              .cacheReadInputTokens(8)
                              .build())
                      .finishReason(FinishReason.TOOL_EXECUTION)
                      .build());
            });
    AnthropicModelClient client = new AnthropicModelClient(model, spec());
    FlowTestSubscriber<ModelStreamEvent> subscriber = new FlowTestSubscriber();
    client.stream(request(true)).subscribe(subscriber);
    Assertions.assertThat(client.thinkingSupport()).isEqualTo(ThinkingSupport.NATIVE);
    Assertions.assertThat(subscriber.error()).isNull();
    Assertions.assertThat(subscriber.completed()).isTrue();
    Assertions.assertThat(subscriber.events())
        .containsExactly(
            new ModelStreamEvent[] {
              new ThinkingDelta("checked the workspace"),
              new TextDelta("I found it"),
              new ToolCallStarted("call-1", "workspace:read"),
              new ToolCallDelta("call-1", "{\"path\":"),
              new ToolCallDelta("call-1", "\"pom.xml\"}"),
              new ToolCallCompleted(
                  new ToolCall("call-1", "workspace:read", "{\"path\":\"pom.xml\"}")),
              new UsageReported(23L, 7L, 8L, 3L),
              new Completed(
                  "tool_execution", Map.of("responseId", "response-1", "model", "claude-test"))
            });
    Assertions.assertThat(model.request().modelName()).isEqualTo("request-model");
    Assertions.assertThat(model.request().maxOutputTokens()).isEqualTo(2048);
    Assertions.assertThat(model.request().toolSpecifications())
        .singleElement()
        .satisfies(
            tool -> {
              Assertions.assertThat(tool.name()).isEqualTo("tool_0_workspace_read");
              Assertions.assertThat(tool.description()).isEqualTo("Read a workspace file");
            });
    Assertions.assertThat(model.request().messages()).hasSize(4);
  }

  private static ProviderSpec spec() {
    return new ProviderSpec(
        Type.ANTHROPIC,
        Optional.of(URI.create("https://anthropic.example")),
        Optional.of("secret"),
        "claude-test",
        0.2,
        4096,
        true,
        Duration.ofSeconds(30L),
        2);
  }

  private static ModelRequest request(boolean thinking) {
    ToolDescriptor tool =
        new ToolDescriptor(
            "workspace",
            "read",
            "Read a workspace file",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
            RiskLevel.LOW);
    return new ModelRequest(
        "default",
        "request-model",
        List.of(
            new SystemMessage("You are a coding agent"),
            new UserMessage("Read pom.xml"),
            new AssistantMessage(
                "",
                Optional.of("Need the file"),
                List.of(new ToolCall("old-call", "workspace:read", "{\"path\":\"README.md\"}")),
                Map.of()),
            new ToolMessage("old-call", "workspace:read", "previous contents", false)),
        List.of(tool),
        thinking,
        2048,
        Map.of());
  }
}
