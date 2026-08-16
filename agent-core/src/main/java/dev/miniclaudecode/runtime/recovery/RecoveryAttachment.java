package dev.miniclaudecode.runtime.recovery;

import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.planning.Plan;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Structured state that survives semantic history replacement at a compact boundary. */
public record RecoveryAttachment(
    String boundaryId,
    String objective,
    Optional<Plan> plan,
    Optional<String> currentStep,
    Map<String, List<String>> completedEvidence,
    List<ReadFile> readFiles,
    List<String> modifiedFiles,
    String workspaceStatus,
    List<String> verifications,
    List<String> loadedSkills,
    List<String> discoveredTools,
    Optional<ApprovalRequest> pendingApproval,
    Optional<String> memoryReview,
    List<String> backgroundAgents,
    List<String> teamTasks,
    List<String> toolResultReferences,
    Map<String, Long> providerUsage,
    Instant createdAt)
    implements Serializable {

  public RecoveryAttachment {
    boundaryId = requireText(boundaryId, "boundaryId");
    objective = Objects.requireNonNullElse(objective, "").strip();
    plan = Objects.requireNonNull(plan);
    currentStep = Objects.requireNonNull(currentStep);
    completedEvidence = immutableEvidence(completedEvidence);
    readFiles = List.copyOf(readFiles);
    modifiedFiles = List.copyOf(modifiedFiles);
    workspaceStatus = Objects.requireNonNullElse(workspaceStatus, "unknown").strip();
    verifications = List.copyOf(verifications);
    loadedSkills = List.copyOf(loadedSkills);
    discoveredTools = List.copyOf(discoveredTools);
    pendingApproval = Objects.requireNonNull(pendingApproval);
    memoryReview = Objects.requireNonNull(memoryReview);
    backgroundAgents = List.copyOf(backgroundAgents);
    teamTasks = List.copyOf(teamTasks);
    toolResultReferences = List.copyOf(toolResultReferences);
    providerUsage = Map.copyOf(providerUsage);
    createdAt = Objects.requireNonNull(createdAt);
  }

  @Override
  public Map<String, List<String>> completedEvidence() {
    return immutableEvidence(this.completedEvidence);
  }

  /** A compact, bounded representation inserted into model context after the summary. */
  public String toPromptText(int maximumCharacters) {
    StringBuilder text = new StringBuilder();
    line(text, "boundary", boundaryId);
    line(text, "objective", objective);
    plan.ifPresent(value -> line(text, "plan", value.status() + " v" + value.version()));
    currentStep.ifPresent(value -> line(text, "currentStep", value));
    line(text, "workspace", workspaceStatus);
    list(text, "modifiedFiles", modifiedFiles);
    list(text, "verifications", verifications);
    list(text, "loadedSkills", loadedSkills);
    list(text, "discoveredTools", discoveredTools);
    list(text, "toolResultReferences", toolResultReferences);
    list(text, "backgroundAgents", backgroundAgents);
    list(text, "teamTasks", teamTasks);
    if (!readFiles.isEmpty()) {
      text.append("readFiles:\n");
      for (ReadFile file : readFiles) {
        text.append("- ")
            .append(file.path())
            .append(" lines=")
            .append(file.startLine())
            .append('-')
            .append(file.endLine())
            .append(" hash=")
            .append(file.contentHash())
            .append(file.stale() ? " stale=true" : "")
            .append(" snippet=")
            .append(file.snippet().replaceAll("\\s+", " "))
            .append('\n');
      }
    }
    pendingApproval.ifPresent(
        value -> line(text, "pendingApproval", value.approvalId().toString()));
    memoryReview.ifPresent(value -> line(text, "memoryReview", value));
    line(text, "providerUsage", providerUsage.toString());
    String result = text.toString().stripTrailing();
    int limit = Math.max(256, maximumCharacters);
    return result.length() <= limit
        ? result
        : result.substring(0, limit) + "\n[attachment truncated]";
  }

  public record ReadFile(
      String path, String contentHash, int startLine, int endLine, String snippet, boolean stale)
      implements Serializable {
    public ReadFile {
      path = requireText(path, "path");
      contentHash = requireText(contentHash, "contentHash");
      if (startLine < 1 || endLine < startLine) {
        throw new IllegalArgumentException("read file line range is invalid");
      }
      snippet = Objects.requireNonNullElse(snippet, "").strip();
    }
  }

  private static Map<String, List<String>> immutableEvidence(Map<String, List<String>> evidence) {
    java.util.LinkedHashMap<String, List<String>> copy = new java.util.LinkedHashMap<>();
    Objects.requireNonNull(evidence).forEach((key, value) -> copy.put(key, List.copyOf(value)));
    return Map.copyOf(copy);
  }

  private static void line(StringBuilder text, String name, String value) {
    if (value != null && !value.isBlank())
      text.append(name).append(": ").append(value).append('\n');
  }

  private static void list(StringBuilder text, String name, List<String> values) {
    if (!values.isEmpty()) line(text, name, String.join(", ", values));
  }

  private static String requireText(String value, String field) {
    value = Objects.requireNonNull(value, field + " must not be null").strip();
    if (value.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
    return value;
  }
}
