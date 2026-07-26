package dev.miniclaudecode.domain.tool;

import dev.miniclaudecode.domain.approval.RiskLevel;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ToolExecutionRecord(
    String toolCallId,
    String qualifiedToolName,
    ToolExecutionRecord.Status status,
    RiskLevel riskLevel,
    Optional<String> beforeHash,
    Optional<String> afterHash,
    Optional<String> resultReference,
    Instant updatedAt)
    implements Serializable {
  public ToolExecutionRecord(
      String toolCallId,
      String qualifiedToolName,
      ToolExecutionRecord.Status status,
      RiskLevel riskLevel,
      Optional<String> beforeHash,
      Optional<String> afterHash,
      Optional<String> resultReference,
      Instant updatedAt) {
    toolCallId = requireText(toolCallId, "toolCallId");
    qualifiedToolName = requireText(qualifiedToolName, "qualifiedToolName");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(riskLevel, "riskLevel must not be null");
    beforeHash = normalize(beforeHash, "beforeHash");
    afterHash = normalize(afterHash, "afterHash");
    resultReference = normalize(resultReference, "resultReference");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    this.toolCallId = toolCallId;
    this.qualifiedToolName = qualifiedToolName;
    this.status = status;
    this.riskLevel = riskLevel;
    this.beforeHash = beforeHash;
    this.afterHash = afterHash;
    this.resultReference = resultReference;
    this.updatedAt = updatedAt;
  }

  private Object writeReplace() {
    return new ToolExecutionRecord.SerializedForm(
        this.toolCallId,
        this.qualifiedToolName,
        this.status,
        this.riskLevel,
        this.beforeHash.orElse(null),
        this.afterHash.orElse(null),
        this.resultReference.orElse(null),
        this.updatedAt);
  }

  private static Optional<String> normalize(Optional<String> value, String field) {
    return Objects.requireNonNull(value, field + " must not be null")
        .map(text -> requireText(text, field));
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
      String qualifiedToolName,
      ToolExecutionRecord.Status status,
      RiskLevel riskLevel,
      String beforeHash,
      String afterHash,
      String resultReference,
      Instant updatedAt)
      implements Serializable {
    private Object readResolve() {
      return new ToolExecutionRecord(
          this.toolCallId,
          this.qualifiedToolName,
          this.status,
          this.riskLevel,
          Optional.ofNullable(this.beforeHash),
          Optional.ofNullable(this.afterHash),
          Optional.ofNullable(this.resultReference),
          this.updatedAt);
    }
  }

  public static enum Status {
    PENDING,
    AWAITING_APPROVAL,
    COMPLETED,
    FAILED,
    UNKNOWN;
  }
}
