package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.runtime.CompletionRequirements;
import dev.miniclaudecode.runtime.output.EngineeringReportValidator;
import dev.miniclaudecode.runtime.output.RagCitationValidator;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncNodeAction;

/** Converts an exhausted completion gate into an explicit terminal failure. */
public final class FailCompletionNode implements AsyncNodeAction<MiniClaudeState> {
  private final RagCitationValidator citations = new RagCitationValidator();
  private final EngineeringReportValidator reports = new EngineeringReportValidator();

  @Override
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    return CompletableFuture.completedFuture(
        Map.of(
            MiniClaudeState.ERROR,
            failureMessage(state),
            MiniClaudeState.STATUS,
            AgentStatus.FAILED,
            MiniClaudeState.TRACE,
            StateSchema.traceEntry("completion_validation_failed")));
  }

  private String failureMessage(MiniClaudeState state) {
    if (CompletionRequirements.requiresVerification(state)) {
      return "Verification is required after workspace changes, but no successful verification "
          + "command was recorded.";
    }
    if (CompletionRequirements.hasIncompleteTasks(state)) {
      return "The task checklist is still incomplete after the maximum number of completion "
          + "prompts.";
    }
    RagCitationValidator.Evaluation citation =
        this.citations.evaluate(state.request(), state.messages(), state.finalText());
    if (!citation.valid()) {
      return citation.repairInstruction();
    }
    EngineeringReportValidator.Evaluation report =
        this.reports.evaluate(state.messages(), state.finalText());
    if (!report.valid()) {
      return report.repairInstruction();
    }
    return "The model response did not satisfy the configured completion requirements.";
  }
}
