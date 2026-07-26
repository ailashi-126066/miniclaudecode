package dev.miniclaudecode.domain.approval;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PermissionRule(
    UUID ruleId,
    String workspace,
    String qualifiedToolName,
    String normalizedTarget,
    Instant createdAt)
    implements Serializable {
  public PermissionRule(
      UUID ruleId,
      String workspace,
      String qualifiedToolName,
      String normalizedTarget,
      Instant createdAt) {
    Objects.requireNonNull(ruleId, "ruleId must not be null");
    workspace = requireText(workspace, "workspace");
    qualifiedToolName = requireText(qualifiedToolName, "qualifiedToolName");
    normalizedTarget = requireText(normalizedTarget, "normalizedTarget");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.ruleId = ruleId;
    this.workspace = workspace;
    this.qualifiedToolName = qualifiedToolName;
    this.normalizedTarget = normalizedTarget;
    this.createdAt = createdAt;
  }

  public boolean matches(String candidateWorkspace, String candidateTool, String candidateTarget) {
    return this.workspace.equals(candidateWorkspace)
        && this.qualifiedToolName.equals(candidateTool)
        && this.normalizedTarget.equals(candidateTarget);
  }

  private static String requireText(String value, String field) {
    if (value != null && !value.isBlank()) {
      return value.trim();
    } else {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
