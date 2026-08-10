package dev.miniclaudecode.domain.tool;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ToolResult(
    String toolCallId,
    ToolResult.Status status,
    String summary,
    Optional<String> resultReference,
    Map<String, Object> metadata)
    implements Serializable {
  public ToolResult(
      String toolCallId,
      ToolResult.Status status,
      String summary,
      Optional<String> resultReference,
      Map<String, Object> metadata) {
    toolCallId = requireText(toolCallId, "toolCallId");
    Objects.requireNonNull(status, "status must not be null");
    summary = requireText(summary, "summary");
    resultReference = Objects.requireNonNull(resultReference, "resultReference must not be null");
    metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    this.toolCallId = toolCallId;
    this.status = status;
    this.summary = summary;
    this.resultReference = resultReference;
    this.metadata = metadata;
  }

  public boolean isError() {
    return this.status == ToolResult.Status.FAILED;
  }

  private Object writeReplace() {
    return new ToolResult.SerializedForm(
        this.toolCallId,
        this.status,
        this.summary,
        this.resultReference.orElse(null),
        this.metadata);
  }

  private static String requireText(String value, String field) {
    if (value != null && !value.isBlank()) {
      return value.trim();
    } else {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  private static record SerializedForm(
      String toolCallId,
      ToolResult.Status status,
      String summary,
      String resultReference,
      Map<String, Object> metadata)
      implements Serializable {
    private Object readResolve() {
      return new ToolResult(
          this.toolCallId,
          this.status,
          this.summary,
          Optional.ofNullable(this.resultReference),
          this.metadata);
    }
  }

  public static enum Status {
    COMPLETED,
    FAILED,
    CANCELLED,
    APPROVAL_REQUIRED;
  }
}
