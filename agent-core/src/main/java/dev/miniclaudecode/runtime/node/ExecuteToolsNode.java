package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.runtime.AsyncNodeAction;
import dev.miniclaudecode.runtime.PlanExecutionContext;
import dev.miniclaudecode.runtime.ToolExecutor;
import dev.miniclaudecode.runtime.TurnLimits;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class ExecuteToolsNode implements AsyncNodeAction<MiniClaudeState> {
  public static final String VERIFICATION_SUCCEEDED_PREFIX = "[verification-command-succeeded] ";
  private static final Pattern VERIFICATION_COMMAND =
      Pattern.compile(
          "(?i)\\b(mvn|gradle|npm|pnpm|yarn|pytest|go\\s+test|cargo\\s+test|dotnet\\s+test|jest|vitest|ruff|eslint|spotless|checkstyle|lint|compile|build)\\b");
  private final ToolExecutor toolExecutor;
  private final TurnLimits limits;

  public ExecuteToolsNode(ToolExecutor toolExecutor, TurnLimits limits) {
    this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
    this.limits = Objects.requireNonNull(limits, "limits must not be null");
  }

  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    List<ToolCall> calls = state.pendingToolCalls();
    // A batch that paused for approval is replayed in full when the decision arrives. Results the
    // earlier pass already produced are carried forward instead of re-executed, because re-running
    // them only reaches the ledger's de-duplication path, which returns a placeholder rather than
    // the original output.
    Map<String, ToolResult> carried = carriedResults(state, calls);
    List<ToolCall> outstanding =
        calls.stream().filter(call -> !carried.containsKey(call.toolCallId())).toList();

    if (state.toolSteps() + outstanding.size() > this.limits.maxToolSteps()) {
      return CompletableFuture.completedFuture(failed("tool step limit exceeded"));
    } else {
      try {
        return this.toolExecutor
            .execute(
                outstanding,
                state.pendingApproval(),
                state.approvalDecision(),
                state
                    .plan()
                    .flatMap(
                        plan ->
                            plan.currentStep()
                                .map(
                                    step ->
                                        new PlanExecutionContext(
                                            plan.id(), step.id(), step.expectedEffects()))))
            .handle(
                (results, error) ->
                    error == null
                        ? completed(
                            state,
                            calls,
                            merge(
                                calls, carried, Objects.requireNonNull((List<ToolResult>) results)),
                            outstanding.size())
                        : failed("tool execution failed: " + safeMessage(error)))
            .toCompletableFuture();
      } catch (RuntimeException var4) {
        return CompletableFuture.completedFuture(
            failed("tool execution failed: " + safeMessage(var4)));
      }
    }
  }

  /** Terminal results from an earlier pass of this same batch, keyed by tool call id. */
  private static Map<String, ToolResult> carriedResults(
      MiniClaudeState state, List<ToolCall> calls) {
    if (state.approvalDecision().isEmpty()) {
      return Map.of();
    }
    Set<String> batch = calls.stream().map(ToolCall::toolCallId).collect(Collectors.toSet());
    Map<String, ToolResult> carried = new LinkedHashMap<>();
    for (ToolResult result : state.toolResults()) {
      if (batch.contains(result.toolCallId())
          && result.status() != Status.APPROVAL_REQUIRED
          && !carried.containsKey(result.toolCallId())) {
        carried.put(result.toolCallId(), result);
      }
    }
    return Map.copyOf(carried);
  }

  /** Re-orders carried and freshly produced results to match the order the model asked for. */
  private static List<ToolResult> merge(
      List<ToolCall> calls, Map<String, ToolResult> carried, List<ToolResult> fresh) {
    Map<String, ToolResult> byId = new LinkedHashMap<>(carried);
    fresh.forEach(result -> byId.put(result.toolCallId(), result));
    List<ToolResult> ordered = new ArrayList<>(byId.size());
    for (ToolCall call : calls) {
      ToolResult result = byId.get(call.toolCallId());
      if (result != null) {
        ordered.add(result);
      }
    }
    return List.copyOf(ordered);
  }

  private static Map<String, Object> completed(
      MiniClaudeState state, List<ToolCall> calls, List<ToolResult> results, int executedCalls) {
    for (ToolResult result : results) {
      if (result.status() == Status.APPROVAL_REQUIRED) {
        if (result.metadata().get("approvalRequest") instanceof ApprovalRequest approvalRequest) {
          Map<String, Object> update = new LinkedHashMap<>();
          update.put("toolResults", List.copyOf(results));
          update.put("pendingApproval", approvalRequest);
          update.put("approvalDecision", "");
          update.put("status", AgentStatus.WAITING_APPROVAL);
          update.put("trace", StateSchema.traceEntry("execute_tools"));
          return update;
        }

        return failed("approval-required tool result is missing ApprovalRequest metadata");
      }
    }

    Map<String, ToolCall> callsById = new LinkedHashMap<>();
    calls.forEach(callx -> callsById.put(callx.toolCallId(), callx));
    List<AgentMessage> messages = new ArrayList<>(state.messages());

    for (ToolResult resultx : results) {
      ToolCall call = callsById.get(resultx.toolCallId());
      String qualifiedName = call == null ? "unknown" : call.qualifiedName();
      String summary = resultx.summary();
      if (call != null && resultx.status() == Status.COMPLETED && isVerificationCommand(call)) {
        summary = VERIFICATION_SUCCEEDED_PREFIX + summary;
      }
      messages.add(
          new ToolMessage(resultx.toolCallId(), qualifiedName, summary, resultx.isError()));
    }

    Map<String, Object> update = new LinkedHashMap<>();
    update.put("messages", List.copyOf(messages));
    update.put("toolResults", List.copyOf(results));
    update.put("pendingToolCalls", List.of());
    update.put("pendingApproval", "");
    update.put("approvalDecision", "");
    update.put("toolSteps", state.toolSteps() + executedCalls);
    update.put("status", AgentStatus.RUNNING);
    update.put("trace", StateSchema.traceEntry("execute_tools"));
    appendDiscoveredSchemas(state, results, update);
    return Map.copyOf(update);
  }

  private static void appendDiscoveredSchemas(
      MiniClaudeState state, List<ToolResult> results, Map<String, Object> update) {
    LinkedHashMap<String, ToolDescriptor> tools = new LinkedHashMap<>();
    state.request().tools().forEach(tool -> tools.put(tool.qualifiedName(), tool));
    LinkedHashSet<String> discovered = new LinkedHashSet<>(state.discoveredTools());
    for (ToolResult result : results) {
      Object rawDescriptors = result.metadata().get("discoveredToolDescriptors");
      if (rawDescriptors instanceof List<?> descriptors) {
        for (Object value : descriptors) {
          if (value instanceof ToolDescriptor descriptor) {
            tools.put(descriptor.qualifiedName(), descriptor);
            discovered.add(descriptor.qualifiedName());
          }
        }
      }
    }
    if (!discovered.equals(new LinkedHashSet<>(state.discoveredTools()))) {
      ModelRequest current = state.request();
      update.put(
          MiniClaudeState.REQUEST,
          new ModelRequest(
              current.providerProfile(),
              current.modelName(),
              current.messages(),
              List.copyOf(tools.values()),
              current.thinkingEnabled(),
              current.maxOutputTokens(),
              current.attributes()));
      update.put(MiniClaudeState.DISCOVERED_TOOLS, List.copyOf(discovered));
    }
  }

  private static Map<String, Object> failed(String message) {
    return Map.of(
        "status",
        AgentStatus.FAILED,
        "error",
        message,
        "trace",
        StateSchema.traceEntry("execute_tools"));
  }

  private static boolean isVerificationCommand(ToolCall call) {
    return "shell:run".equals(call.qualifiedName())
        && VERIFICATION_COMMAND.matcher(call.argumentsJson()).find();
  }

  private static String safeMessage(Throwable error) {
    Throwable cause = error.getCause() == null ? error : error.getCause();
    return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
  }
}
