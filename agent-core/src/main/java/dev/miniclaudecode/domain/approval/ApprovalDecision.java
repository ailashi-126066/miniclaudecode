package dev.miniclaudecode.domain.approval;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ApprovalDecision(
    UUID approvalId,
    ApprovalDecision.Choice choice,
    ApprovalDecision.Scope scope,
    Optional<String> feedback,
    Instant decidedAt)
    implements Serializable {
  public ApprovalDecision(
      UUID approvalId,
      ApprovalDecision.Choice choice,
      ApprovalDecision.Scope scope,
      Optional<String> feedback,
      Instant decidedAt) {
    Objects.requireNonNull(approvalId, "approvalId must not be null");
    Objects.requireNonNull(choice, "choice must not be null");
    Objects.requireNonNull(scope, "scope must not be null");
    feedback =
        Objects.requireNonNull(feedback, "feedback must not be null")
            .map(String::trim)
            .filter(value -> !value.isEmpty());
    Objects.requireNonNull(decidedAt, "decidedAt must not be null");
    this.approvalId = approvalId;
    this.choice = choice;
    this.scope = scope;
    this.feedback = feedback;
    this.decidedAt = decidedAt;
  }

  private Object writeReplace() {
    return new ApprovalDecision.SerializedForm(
        this.approvalId, this.choice, this.scope, this.feedback.orElse(null), this.decidedAt);
  }

  public static enum Choice {
    ALLOW,
    REJECT;
  }

  public static enum Scope {
    ONCE,
    TURN,
    FILE,
    PERMANENT;
  }

  private static record SerializedForm(
      UUID approvalId,
      ApprovalDecision.Choice choice,
      ApprovalDecision.Scope scope,
      String feedback,
      Instant decidedAt)
      implements Serializable {
    private Object readResolve() {
      return new ApprovalDecision(
          this.approvalId,
          this.choice,
          this.scope,
          Optional.ofNullable(this.feedback),
          this.decidedAt);
    }
  }
}
