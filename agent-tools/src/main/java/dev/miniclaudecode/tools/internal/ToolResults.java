package dev.miniclaudecode.tools.internal;

import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ToolResults {
  private ToolResults() {}

  public static ToolResult completed(
      ToolCall call,
      String output,
      Map<String, Object> metadata,
      ToolResultStore store,
      int inlineByteLimit) {
    String normalized = output.isEmpty() ? "(no output)" : output;
    Map<String, Object> resultMetadata = new LinkedHashMap<>(metadata);
    if (normalized.getBytes(StandardCharsets.UTF_8).length <= inlineByteLimit) {
      resultMetadata.put("truncated", false);
      return new ToolResult(
          call.toolCallId(), Status.COMPLETED, normalized, Optional.empty(), resultMetadata);
    } else {
      String reference = store.put(normalized);
      int previewCharacters = Math.min(normalized.length(), Math.max(1, inlineByteLimit / 2));
      String preview = normalized.substring(0, previewCharacters);
      resultMetadata.put("truncated", true);
      resultMetadata.put("totalBytes", normalized.getBytes(StandardCharsets.UTF_8).length);
      return new ToolResult(
          call.toolCallId(),
          Status.COMPLETED,
          preview + "\n… output truncated; full result: " + reference,
          Optional.of(reference),
          resultMetadata);
    }
  }

  public static ToolResult failed(ToolCall call, RuntimeException exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      message = exception.getClass().getSimpleName();
    }

    return new ToolResult(call.toolCallId(), Status.FAILED, message, Optional.empty(), Map.of());
  }
}
