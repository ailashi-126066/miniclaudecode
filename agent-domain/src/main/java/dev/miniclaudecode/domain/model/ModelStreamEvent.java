package dev.miniclaudecode.domain.model;

import dev.miniclaudecode.domain.tool.ToolCall;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

public sealed interface ModelStreamEvent extends Serializable
    permits ModelStreamEvent.ThinkingDelta,
        ModelStreamEvent.TextDelta,
        ModelStreamEvent.ToolCallStarted,
        ModelStreamEvent.ToolCallDelta,
        ModelStreamEvent.ToolCallCompleted,
        ModelStreamEvent.UsageReported,
        ModelStreamEvent.Completed,
        ModelStreamEvent.Failed {
  private static String requireNonEmpty(String value, String field) {
    if (value != null && !value.isEmpty()) {
      return value;
    } else {
      throw new IllegalArgumentException(field + " must not be empty");
    }
  }

  public static record Completed(String finishReason, Map<String, Object> providerMetadata)
      implements ModelStreamEvent {
    public Completed(String finishReason, Map<String, Object> providerMetadata) {
      finishReason = ModelStreamEvent.requireNonEmpty(finishReason, "finishReason");
      providerMetadata =
          Map.copyOf(Objects.requireNonNull(providerMetadata, "providerMetadata must not be null"));
      this.finishReason = finishReason;
      this.providerMetadata = providerMetadata;
    }
  }

  public static record Failed(String errorType, String message, boolean retryable)
      implements ModelStreamEvent {
    public Failed(String errorType, String message, boolean retryable) {
      errorType = ModelStreamEvent.requireNonEmpty(errorType, "errorType");
      message = ModelStreamEvent.requireNonEmpty(message, "message");
      this.errorType = errorType;
      this.message = message;
      this.retryable = retryable;
    }
  }

  public static record TextDelta(String text) implements ModelStreamEvent {
    public TextDelta(String text) {
      text = ModelStreamEvent.requireNonEmpty(text, "text");
      this.text = text;
    }
  }

  public static record ThinkingDelta(String text) implements ModelStreamEvent {
    public ThinkingDelta(String text) {
      text = ModelStreamEvent.requireNonEmpty(text, "text");
      this.text = text;
    }
  }

  public static record ToolCallCompleted(ToolCall toolCall) implements ModelStreamEvent {
    public ToolCallCompleted(ToolCall toolCall) {
      Objects.requireNonNull(toolCall, "toolCall must not be null");
      this.toolCall = toolCall;
    }
  }

  public static record ToolCallDelta(String toolCallId, String argumentsFragment)
      implements ModelStreamEvent {
    public ToolCallDelta(String toolCallId, String argumentsFragment) {
      toolCallId = ModelStreamEvent.requireNonEmpty(toolCallId, "toolCallId");
      argumentsFragment =
          Objects.requireNonNull(argumentsFragment, "argumentsFragment must not be null");
      this.toolCallId = toolCallId;
      this.argumentsFragment = argumentsFragment;
    }
  }

  public static record ToolCallStarted(String toolCallId, String qualifiedToolName)
      implements ModelStreamEvent {
    public ToolCallStarted(String toolCallId, String qualifiedToolName) {
      toolCallId = ModelStreamEvent.requireNonEmpty(toolCallId, "toolCallId");
      qualifiedToolName = ModelStreamEvent.requireNonEmpty(qualifiedToolName, "qualifiedToolName");
      this.toolCallId = toolCallId;
      this.qualifiedToolName = qualifiedToolName;
    }
  }

  public static record UsageReported(
      long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens)
      implements ModelStreamEvent {
    public UsageReported(long inputTokens, long outputTokens) {
      this(inputTokens, outputTokens, 0L, 0L);
    }

    public UsageReported(
        long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
      if (inputTokens < 0L || outputTokens < 0L || cacheReadTokens < 0L || cacheWriteTokens < 0L) {
        throw new IllegalArgumentException("token counts must not be negative");
      } else if (cacheWriteTokens <= inputTokens
          && cacheReadTokens <= inputTokens - cacheWriteTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.cacheWriteTokens = cacheWriteTokens;
      } else {
        throw new IllegalArgumentException("cache token counts must not exceed inputTokens");
      }
    }
  }
}
