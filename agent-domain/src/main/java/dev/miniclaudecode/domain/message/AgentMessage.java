package dev.miniclaudecode.domain.message;

import dev.miniclaudecode.domain.tool.ToolCall;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public sealed interface AgentMessage extends Serializable
    permits AgentMessage.SystemMessage,
        AgentMessage.UserMessage,
        AgentMessage.AssistantMessage,
        AgentMessage.ToolMessage {
  String text();

  private static String requireText(String value, String field) {
    if (value != null && !value.isBlank()) {
      return value.trim();
    } else {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  public static record AssistantMessage(
      String text,
      Optional<String> thinking,
      List<ToolCall> toolCalls,
      Map<String, Object> providerMetadata)
      implements AgentMessage {
    public AssistantMessage(
        String text,
        Optional<String> thinking,
        List<ToolCall> toolCalls,
        Map<String, Object> providerMetadata) {
      text = Objects.requireNonNull(text, "text must not be null");
      thinking =
          Objects.requireNonNull(thinking, "thinking must not be null")
              .map(String::trim)
              .filter(value -> !value.isEmpty());
      toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls must not be null"));
      providerMetadata =
          Map.copyOf(Objects.requireNonNull(providerMetadata, "providerMetadata must not be null"));
      this.text = text;
      this.thinking = thinking;
      this.toolCalls = toolCalls;
      this.providerMetadata = providerMetadata;
    }

    public AssistantMessage(
        String text, Optional<String> thinking, Map<String, Object> providerMetadata) {
      this(text, thinking, List.of(), providerMetadata);
    }

    private Object writeReplace() {
      return new AgentMessage.AssistantMessage.SerializedForm(
          this.text, this.thinking.orElse(null), this.toolCalls, this.providerMetadata);
    }

    private static record SerializedForm(
        String text,
        String thinking,
        List<ToolCall> toolCalls,
        Map<String, Object> providerMetadata)
        implements Serializable {
      private Object readResolve() {
        return new AgentMessage.AssistantMessage(
            this.text, Optional.ofNullable(this.thinking), this.toolCalls, this.providerMetadata);
      }
    }
  }

  public static record SystemMessage(String text) implements AgentMessage {
    public SystemMessage(String text) {
      text = AgentMessage.requireText(text, "text");
      this.text = text;
    }
  }

  public static record ToolMessage(
      String toolCallId, String qualifiedToolName, String text, boolean error)
      implements AgentMessage {
    public ToolMessage(String toolCallId, String qualifiedToolName, String text, boolean error) {
      toolCallId = AgentMessage.requireText(toolCallId, "toolCallId");
      qualifiedToolName = AgentMessage.requireText(qualifiedToolName, "qualifiedToolName");
      text = Objects.requireNonNull(text, "text must not be null");
      this.toolCallId = toolCallId;
      this.qualifiedToolName = qualifiedToolName;
      this.text = text;
      this.error = error;
    }
  }

  public static record UserMessage(String text) implements AgentMessage {
    public UserMessage(String text) {
      text = AgentMessage.requireText(text, "text");
      this.text = text;
    }
  }
}
