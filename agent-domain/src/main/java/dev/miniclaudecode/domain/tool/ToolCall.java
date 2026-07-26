package dev.miniclaudecode.domain.tool;

import java.io.Serializable;

public record ToolCall(String toolCallId, String qualifiedName, String argumentsJson)
    implements Serializable {
  public ToolCall(String toolCallId, String qualifiedName, String argumentsJson) {
    toolCallId = requireText(toolCallId, "toolCallId");
    qualifiedName = requireText(qualifiedName, "qualifiedName");
    argumentsJson = requireText(argumentsJson, "argumentsJson");
    this.toolCallId = toolCallId;
    this.qualifiedName = qualifiedName;
    this.argumentsJson = argumentsJson;
  }

  private static String requireText(String value, String field) {
    if (value != null && !value.isBlank()) {
      return value.trim();
    } else {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
