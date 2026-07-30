package dev.miniclaudecode.cli.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolResult;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Explicitly accepts or discards a committed isolated subtask; neither action is automatic. */
final class WorktreeControlTool implements AgentTool {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final IsolatedWorktreeService worktrees;

  WorktreeControlTool(IsolatedWorktreeService worktrees) {
    this.worktrees = worktrees;
  }

  public ToolDescriptor descriptor() {
    return new ToolDescriptor(
        "workspace",
        "worktree",
        "Record a layered review, then explicitly merge or discard an isolated delegated worktree commit",
        "{\"type\":\"object\",\"properties\":{\"action\":{\"enum\":[\"review\",\"merge\",\"discard\"]},\"layer\":{\"enum\":[\"function\",\"quality\",\"security\"]},\"id\":{\"type\":\"string\"}},\"required\":[\"action\",\"id\"]}",
        RiskLevel.HIGH);
  }

  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      JsonNode args = JSON.readTree(call.argumentsJson());
      String action = args.path("action").asText();
      String id = args.path("id").asText();
      IsolatedWorktreeService.Snapshot snapshot = this.worktrees.snapshot(id);
      if ("review".equals(action)) {
        String layer = args.path("layer").asText();
        IsolatedWorktreeService.ReviewLayer reviewLayer =
            IsolatedWorktreeService.ReviewLayer.valueOf(layer.toUpperCase(java.util.Locale.ROOT));
        return CompletableFuture.completedFuture(
            new ToolResult(
                call.toolCallId(),
                ToolResult.Status.COMPLETED,
                this.worktrees.recordReview(id, reviewLayer),
                Optional.empty(),
                Map.of("worktreeId", id, "layer", reviewLayer.name())));
      }
      Object request = context.attributes().get("approvalRequest");
      Object decision = context.attributes().get("approvalDecision");
      if (request == null && decision == null) {
        ApprovalRequest approval =
            new ApprovalRequest(
                UUID.randomUUID(),
                call,
                RiskLevel.HIGH,
                id,
                "Explicit " + action + " of isolated worktree commit " + snapshot.commit(),
                Optional.empty(),
                Optional.empty(),
                Instant.now());
        return CompletableFuture.completedFuture(
            new ToolResult(
                call.toolCallId(),
                ToolResult.Status.APPROVAL_REQUIRED,
                "Approval required to " + action + " isolated worktree " + id,
                Optional.empty(),
                Map.of("approvalRequest", approval)));
      }
      if (!(request instanceof ApprovalRequest approval)
          || !(decision instanceof ApprovalDecision approved)
          || !approval.approvalId().equals(approved.approvalId())
          || !approval.toolCall().equals(call)) {
        throw new SecurityException("worktree approval does not match this action");
      }
      if (approved.choice() == ApprovalDecision.Choice.REJECT) {
        return CompletableFuture.completedFuture(
            new ToolResult(
                call.toolCallId(),
                ToolResult.Status.CANCELLED,
                approved.feedback().orElse("worktree action rejected"),
                Optional.empty(),
                Map.of()));
      }
      String result =
          switch (action) {
            case "merge" -> this.worktrees.merge(id);
            case "discard" -> this.worktrees.discard(id);
            default ->
                throw new IllegalArgumentException("action must be review, merge, or discard");
          };
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.COMPLETED,
              result,
              Optional.empty(),
              Map.of("worktreeId", id)));
    } catch (Exception error) {
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.FAILED,
              error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
              Optional.empty(),
              Map.of()));
    }
  }
}
