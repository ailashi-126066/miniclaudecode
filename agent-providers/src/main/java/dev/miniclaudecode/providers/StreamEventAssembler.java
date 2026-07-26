package dev.miniclaudecode.providers;

import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.tool.ToolCall;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class StreamEventAssembler {

  private final Map<String, PendingToolCall> pendingToolCalls = new LinkedHashMap<>();

  public synchronized ModelStreamEvent.ToolCallStarted startToolCall(
      String toolCallId, String qualifiedToolName) {
    String id = requireText(toolCallId, "toolCallId");
    String name = requireText(qualifiedToolName, "qualifiedToolName");
    if (pendingToolCalls.putIfAbsent(id, new PendingToolCall(name)) != null) {
      throw new IllegalStateException("tool call already started: " + id);
    }
    return new ModelStreamEvent.ToolCallStarted(id, name);
  }

  public synchronized ModelStreamEvent.ToolCallDelta appendToolArguments(
      String toolCallId, String argumentsFragment) {
    String id = requireText(toolCallId, "toolCallId");
    String fragment =
        Objects.requireNonNull(argumentsFragment, "argumentsFragment must not be null");
    PendingToolCall pending = requirePending(id);
    pending.arguments().append(fragment);
    return new ModelStreamEvent.ToolCallDelta(id, fragment);
  }

  public synchronized ModelStreamEvent.ToolCallCompleted completeToolCall(String toolCallId) {
    String id = requireText(toolCallId, "toolCallId");
    PendingToolCall pending = requirePending(id);
    String arguments = pending.arguments().toString();
    if (arguments.isBlank()) {
      arguments = "{}";
    }
    pendingToolCalls.remove(id);
    return new ModelStreamEvent.ToolCallCompleted(
        new ToolCall(id, pending.qualifiedName(), arguments));
  }

  public synchronized boolean hasPendingToolCalls() {
    return !pendingToolCalls.isEmpty();
  }

  public synchronized void verifyComplete() {
    if (!pendingToolCalls.isEmpty()) {
      throw new IllegalStateException(
          "unfinished tool calls: " + String.join(", ", pendingToolCalls.keySet()));
    }
  }

  public synchronized void reset() {
    pendingToolCalls.clear();
  }

  private PendingToolCall requirePending(String toolCallId) {
    PendingToolCall pending = pendingToolCalls.get(toolCallId);
    if (pending == null) {
      throw new IllegalStateException("unknown tool call: " + toolCallId);
    }
    return pending;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }

  private record PendingToolCall(String qualifiedName, StringBuilder arguments) {

    private PendingToolCall(String qualifiedName) {
      this(qualifiedName, new StringBuilder());
    }
  }
}
