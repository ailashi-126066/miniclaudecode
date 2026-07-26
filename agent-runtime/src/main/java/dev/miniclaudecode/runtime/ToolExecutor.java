package dev.miniclaudecode.runtime;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ToolExecutor {

  CompletionStage<List<ToolResult>> execute(List<ToolCall> calls);

  default CompletionStage<List<ToolResult>> execute(
      List<ToolCall> calls,
      Optional<ApprovalRequest> pendingApproval,
      Optional<ApprovalDecision> approvalDecision) {
    return execute(calls);
  }
}
