package dev.miniclaudecode.runtime.state;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.planning.Plan;
import dev.miniclaudecode.runtime.workflow.ExecutionMode;
import dev.miniclaudecode.runtime.workflow.ExecutionPhase;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bsc.langgraph4j.state.AgentState;

public final class MiniClaudeState extends AgentState {
  public static final String REQUEST = "request";
  public static final String MESSAGES = "messages";
  public static final String MODEL_EVENTS = "modelEvents";
  public static final String PENDING_TOOL_CALLS = "pendingToolCalls";
  public static final String TOOL_RESULTS = "toolResults";
  public static final String PENDING_APPROVAL = "pendingApproval";
  public static final String APPROVAL_DECISION = "approvalDecision";
  public static final String FINAL_TEXT = "finalText";
  public static final String THINKING = "thinking";
  public static final String PROVIDER_METADATA = "providerMetadata";
  public static final String STATUS = "status";
  public static final String ERROR = "error";
  public static final String FAILURE_TYPE = "failureType";
  public static final String FAILURE_RETRYABLE = "failureRetryable";
  public static final String RETRY_COUNT = "retryCount";
  public static final String COMPACTION_COUNT = "compactionCount";
  public static final String MODEL_STEPS = "modelSteps";
  public static final String TOOL_STEPS = "toolSteps";
  public static final String VERIFICATION_PROMPTS = "verificationPrompts";
  public static final String OUTPUT_REPAIR_COUNT = "outputRepairCount";
  public static final String TRACE = "trace";
  public static final String PLAN = "plan";
  public static final String PLANNING_PHASE = "planningPhase";
  public static final String STEP_DECISION = "stepDecision";
  public static final String EXECUTION_PHASE = "executionPhase";
  public static final String EXECUTION_MODE = "executionMode";
  public static final String WORKFLOW_ROUTE = "workflowRoute";
  public static final String DIRECT_ATTEMPTS = "directAttempts";
  public static final String WORKFLOW_SOURCE = "workflowSource";

  public MiniClaudeState(Map<String, Object> data) {
    super(data);
  }

  public ModelRequest request() {
    Object request = this.data().get("request");
    if (request instanceof ModelRequest) {
      return (ModelRequest) request;
    } else {
      throw new IllegalStateException("state does not contain a model request");
    }
  }

  public List<AgentMessage> messages() {
    return this.list("messages");
  }

  public List<ModelStreamEvent> modelEvents() {
    return this.list("modelEvents");
  }

  public List<ToolCall> pendingToolCalls() {
    return this.list("pendingToolCalls");
  }

  public List<ToolResult> toolResults() {
    return this.list("toolResults");
  }

  public Optional<ApprovalRequest> pendingApproval() {
    return this.optional("pendingApproval", ApprovalRequest.class);
  }

  public Optional<ApprovalDecision> approvalDecision() {
    return this.optional("approvalDecision", ApprovalDecision.class);
  }

  public String finalText() {
    return this.scalar("finalText", String.class, "");
  }

  public Optional<String> thinking() {
    return this.optionalText("thinking");
  }

  public Map<String, Object> providerMetadata() {
    return this.map("providerMetadata");
  }

  public AgentStatus status() {
    return this.scalar("status", AgentStatus.class, AgentStatus.RUNNING);
  }

  public Optional<String> error() {
    return this.optionalText("error");
  }

  public Optional<String> failureType() {
    return this.optionalText("failureType");
  }

  public boolean failureRetryable() {
    return this.scalar("failureRetryable", Boolean.class, false);
  }

  public int retryCount() {
    return this.scalar("retryCount", Integer.class, 0);
  }

  public int compactionCount() {
    return this.scalar("compactionCount", Integer.class, 0);
  }

  public int modelSteps() {
    return this.scalar("modelSteps", Integer.class, 0);
  }

  public int toolSteps() {
    return this.scalar("toolSteps", Integer.class, 0);
  }

  public int verificationPrompts() {
    return this.scalar("verificationPrompts", Integer.class, 0);
  }

  public int outputRepairCount() {
    return this.scalar("outputRepairCount", Integer.class, 0);
  }

  public List<String> trace() {
    return this.list("trace");
  }

  public Optional<Plan> plan() {
    return this.optional(PLAN, Plan.class);
  }

  public String planningPhase() {
    return this.scalar(PLANNING_PHASE, String.class, "DISCOVER");
  }

  public String stepDecision() {
    return this.scalar(STEP_DECISION, String.class, "");
  }

  public ExecutionPhase executionPhase() {
    return this.scalar(EXECUTION_PHASE, ExecutionPhase.class, ExecutionPhase.DISCOVERY);
  }

  public Optional<ExecutionMode> executionMode() {
    return this.optional(EXECUTION_MODE, ExecutionMode.class);
  }

  public String workflowRoute() {
    return this.scalar(WORKFLOW_ROUTE, String.class, "");
  }

  public int directAttempts() {
    return this.scalar(DIRECT_ATTEMPTS, Integer.class, 0);
  }

  public String workflowSource() {
    return this.scalar(WORKFLOW_SOURCE, String.class, "model");
  }

  private Optional<String> optionalText(String key) {
    return this.data().get(key) instanceof String text
        ? Optional.of(text.trim()).filter(item -> !item.isEmpty())
        : Optional.empty();
  }

  private <T> Optional<T> optional(String key, Class<T> type) {
    Object value = this.data().get(key);
    return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
  }

  private <T> List<T> list(String key) {
    @SuppressWarnings("unchecked")
    List<T> value = (List<T>) this.data().getOrDefault(key, List.of());
    return List.copyOf(value);
  }

  private Map<String, Object> map(String key) {
    @SuppressWarnings("unchecked")
    Map<String, Object> value = (Map<String, Object>) this.data().getOrDefault(key, Map.of());
    return Map.copyOf(value);
  }

  private <T> T scalar(String key, Class<T> type, T defaultValue) {
    Object value = this.data().get(key);
    return type.isInstance(value) ? type.cast(value) : defaultValue;
  }
}
