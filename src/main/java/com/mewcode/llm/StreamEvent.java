package com.mewcode.llm;

import java.util.Map;
import java.util.Objects;

public sealed interface StreamEvent {

    record TextDelta(String text) implements StreamEvent {}

    record ThinkingDelta(String text) implements StreamEvent {}

    record ThinkingComplete(String thinking, String signature) implements StreamEvent {}

    record ToolCallStart(String toolId, String toolName) implements StreamEvent {}

    record ToolCallDelta(String text) implements StreamEvent {}

    record ToolCallComplete(
            String toolId,
            String toolName,
            Map<String, Object> arguments
    ) implements StreamEvent {}

    record StreamEnd(
            String stopReason,
            int inputTokens,
            int outputTokens,
            int cacheReadTokens,
            int cacheCreationTokens
    ) implements StreamEvent {

        /** Cold-start / non-cache providers: usage carries no cache breakdown. */
        public StreamEnd(String stopReason, int inputTokens, int outputTokens) {
            this(stopReason, inputTokens, outputTokens, 0, 0);
        }
    }

    /**
     * A terminal stream failure. The typed exception is retained so Agent can
     * make a recovery decision without parsing human-readable text.
     */
    record Error(LlmException exception) implements StreamEvent {

        public Error {
            Objects.requireNonNull(exception, "exception must not be null");
        }

        /** Compatibility constructor for local parsing/validation failures. */
        public Error(String message) {
            this(new LlmException(message == null ? "Unknown LLM error" : message));
        }

        public String message() {
            return exception.getMessage();
        }
    }
}
