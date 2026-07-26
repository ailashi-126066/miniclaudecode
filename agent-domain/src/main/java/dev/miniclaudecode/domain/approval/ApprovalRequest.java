package dev.miniclaudecode.domain.approval;

import dev.miniclaudecode.domain.tool.ToolCall;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ApprovalRequest(
    UUID approvalId,
    ToolCall toolCall,
    RiskLevel riskLevel,
    String target,
    String reason,
    Optional<String> beforeHash,
    Optional<String> diffHash,
    Instant requestedAt)
    implements Serializable {
  public ApprovalRequest(
      UUID approvalId,
      ToolCall toolCall,
      RiskLevel riskLevel,
      String target,
      String reason,
      Optional<String> beforeHash,
      Optional<String> diffHash,
      Instant requestedAt) {
    Objects.requireNonNull(approvalId, "approvalId must not be null");
    Objects.requireNonNull(toolCall, "toolCall must not be null");
    Objects.requireNonNull(riskLevel, "riskLevel must not be null");
    target = requireText(target, "target");
    reason = requireText(reason, "reason");
    beforeHash = normalize(beforeHash, "beforeHash");
    diffHash = normalize(diffHash, "diffHash");
    if (beforeHash.isPresent() != diffHash.isPresent()) {
      throw new IllegalArgumentException("beforeHash and diffHash must be supplied together");
    } else {
      Objects.requireNonNull(requestedAt, "requestedAt must not be null");
      this.approvalId = approvalId;
      this.toolCall = toolCall;
      this.riskLevel = riskLevel;
      this.target = target;
      this.reason = reason;
      this.beforeHash = beforeHash;
      this.diffHash = diffHash;
      this.requestedAt = requestedAt;
    }
  }

  public boolean isBoundToDiff() {
    return this.beforeHash.isPresent();
  }

  private Object writeReplace() {
    return new ApprovalRequest.SerializedForm(
        this.approvalId,
        this.toolCall,
        this.riskLevel,
        this.target,
        this.reason,
        this.beforeHash.orElse(null),
        this.diffHash.orElse(null),
        this.requestedAt);
  }

  private static Optional<String> normalize(Optional<String> value, String field) {
    Objects.requireNonNull(value, field + " must not be null");
    return value.map(text -> requireText(text, field));
  }

  private static String requireText(String value, String field) {
    if (value != null && !value.isBlank()) {
      return value.trim();
    } else {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  private static record SerializedForm(
      UUID approvalId,
      ToolCall toolCall,
      RiskLevel riskLevel,
      String target,
      String reason,
      String beforeHash,
      String diffHash,
      Instant requestedAt)
      implements Serializable {
    private Object readResolve() {
      return new ApprovalRequest(
          this.approvalId,
          this.toolCall,
          this.riskLevel,
          this.target,
          this.reason,
          Optional.ofNullable(this.beforeHash),
          Optional.ofNullable(this.diffHash),
          this.requestedAt);
    }
  }
}
