package dev.miniclaudecode.tools.process;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Choice;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Scope;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.PermissionRule;
import dev.miniclaudecode.domain.approval.PermissionRuleStore;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.tools.fs.WorkspacePathResolver;
import dev.miniclaudecode.tools.internal.ToolArguments;
import dev.miniclaudecode.tools.internal.ToolResults;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class RunCommandTool implements AgentTool {
  public static final String CANCELLATION_TOKEN_ATTRIBUTE = "cancellationToken";
  private static final int DEFAULT_TIMEOUT_SECONDS = 120;
  private static final int MAX_TIMEOUT_SECONDS = 600;
  private static final int DEFAULT_MAX_OUTPUT_BYTES = 524288;
  private static final int MAX_OUTPUT_BYTES = 4194304;
  private static final int INLINE_OUTPUT_BYTES = 32768;
  private static final Executor VIRTUAL_EXECUTOR = command -> Thread.startVirtualThread(command);
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "shell",
          "run",
          "Run a command in the workspace using PowerShell on Windows or /bin/sh on POSIX",
          "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"},\"workingDirectory\":{\"type\":\"string\"},\"timeoutSeconds\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":600},\"maxOutputBytes\":{\"type\":\"integer\",\"minimum\":1}},\"required\":[\"command\"]}",
          RiskLevel.HIGH);
  private final WorkspacePathResolver resolver;
  private final ProcessRunner processRunner;
  private final ToolResultStore resultStore;
  private final CommandRiskClassifier riskClassifier;
  private final PermissionRuleStore ruleStore;
  private final Clock clock;

  public RunCommandTool(
      WorkspacePathResolver resolver, ProcessRunner processRunner, ToolResultStore resultStore) {
    this(
        resolver,
        processRunner,
        resultStore,
        new CommandRiskClassifier(),
        PermissionRuleStore.NONE,
        Clock.systemUTC());
  }

  public RunCommandTool(
      WorkspacePathResolver resolver,
      ProcessRunner processRunner,
      ToolResultStore resultStore,
      CommandRiskClassifier riskClassifier,
      PermissionRuleStore ruleStore,
      Clock clock) {
    this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    this.processRunner = Objects.requireNonNull(processRunner, "processRunner must not be null");
    this.resultStore = Objects.requireNonNull(resultStore, "resultStore must not be null");
    this.riskClassifier = Objects.requireNonNull(riskClassifier, "riskClassifier must not be null");
    this.ruleStore = Objects.requireNonNull(ruleStore, "ruleStore must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(context, "context must not be null");

    try {
      ToolArguments arguments = ToolArguments.parse(call.argumentsJson());
      String command = arguments.requiredText("command");
      String requestedDirectory = arguments.optionalText("workingDirectory", ".");
      int timeoutSeconds = arguments.optionalPositiveInt("timeoutSeconds", 120, 600);
      int maxOutputBytes = arguments.optionalPositiveInt("maxOutputBytes", 524288, 4194304);
      Path workingDirectory = this.resolver.resolveExisting(requestedDirectory);
      if (!Files.isDirectory(workingDirectory)) {
        throw new IllegalArgumentException("workingDirectory is not a directory");
      } else {
        RiskLevel risk = this.riskClassifier.classify(command);
        Optional<ToolResult> authorization = this.authorize(call, context, command, risk);
        if (authorization.isPresent()) {
          return CompletableFuture.completedFuture(authorization.orElseThrow());
        } else {
          CancellationToken token = cancellationToken(context);
          ProcessRunner.ProcessRequest request =
              new ProcessRunner.ProcessRequest(
                  command,
                  workingDirectory,
                  Duration.ofSeconds((long) timeoutSeconds),
                  maxOutputBytes,
                  true);
          return CompletableFuture.supplyAsync(
              () ->
                  this.toToolResult(call, workingDirectory, this.processRunner.run(request, token)),
              VIRTUAL_EXECUTOR);
        }
      }
    } catch (RuntimeException var13) {
      return CompletableFuture.completedFuture(ToolResults.failed(call, var13));
    }
  }

  /** In-memory, per-turn shell allowances granted with {@link Scope#TURN}; never persisted. */
  private final java.util.Set<String> turnAllowances =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  private static String turnKey(String workspace, ToolContext context, String command) {
    return workspace + " " + context.turnId().value() + " " + command;
  }

  private Optional<ToolResult> authorize(
      ToolCall call, ToolContext context, String command, RiskLevel risk) {
    String workspace = context.workspace().toString();
    if (risk != RiskLevel.LOW
        && !this.turnAllowances.contains(turnKey(workspace, context, command))
        && !this.ruleStore.list().stream()
            .anyMatch(rule -> rule.matches(workspace, call.qualifiedName(), command))) {
      Object requestValue = context.attributes().get("approvalRequest");
      Object decisionValue = context.attributes().get("approvalDecision");
      if (requestValue == null && decisionValue == null) {
        // The sandbox state travels inside the prompt so a user approving a command always sees
        // whether OS-level isolation applies or the platform degraded to classification-only.
        ApprovalRequest request =
            new ApprovalRequest(
                UUID.randomUUID(),
                call,
                risk,
                command,
                "Shell commands can modify files or cause external side effects (sandbox: "
                    + this.processRunner.sandboxDescription()
                    + ")",
                Optional.empty(),
                Optional.empty(),
                Instant.now(this.clock));
        return Optional.of(
            new ToolResult(
                call.toolCallId(),
                Status.APPROVAL_REQUIRED,
                "Approval required before running command: " + command,
                Optional.empty(),
                Map.of("approvalRequest", request, "riskLevel", risk.name())));
      } else {
        if (requestValue instanceof ApprovalRequest request
            && decisionValue instanceof ApprovalDecision decision) {
          boolean matches =
              request.toolCall().equals(call)
                  && request.target().equals(command)
                  && request.approvalId().equals(decision.approvalId());
          if (!matches) {
            throw new SecurityException("command changed after approval was requested");
          }

          if (decision.choice() == Choice.REJECT) {
            return Optional.of(
                new ToolResult(
                    call.toolCallId(),
                    Status.CANCELLED,
                    decision.feedback().orElse("user rejected the command"),
                    Optional.empty(),
                    Map.of("riskLevel", risk.name())));
          }

          if (decision.scope() == Scope.PERMANENT) {
            this.ruleStore.save(
                new PermissionRule(
                    UUID.randomUUID(),
                    workspace,
                    call.qualifiedName(),
                    command,
                    Instant.now(this.clock)));
          } else if (decision.scope() == Scope.TURN) {
            // "Allow for this turn" used to be discarded, making menu option 2 a synonym for
            // "allow once". The allowance is deliberately in-memory and keyed by turn so it can
            // never widen into a persisted rule.
            this.turnAllowances.add(turnKey(workspace, context, command));
          }

          return Optional.empty();
        }

        throw new IllegalArgumentException(
            "approval request and decision must be supplied together");
      }
    } else {
      return Optional.empty();
    }
  }

  private ToolResult toToolResult(
      ToolCall call, Path workingDirectory, ProcessRunner.ProcessResult processResult) {
    String output = processResult.stdout();
    if (!processResult.stderr().isEmpty()) {
      output = output + (output.isEmpty() ? "" : "\n") + processResult.stderr();
    }

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("exitCode", processResult.exitCode());
    metadata.put("workingDirectory", this.resolver.relativeDisplay(workingDirectory));
    metadata.put("timedOut", processResult.timedOut());
    metadata.put("cancelled", processResult.cancelled());
    metadata.put("outputTruncated", processResult.truncated());
    metadata.put("durationMillis", processResult.duration().toMillis());
    if (processResult.cancelled()) {
      return new ToolResult(
          call.toolCallId(),
          Status.CANCELLED,
          output.isBlank() ? "Command cancelled" : output,
          Optional.empty(),
          metadata);
    } else if (!processResult.timedOut() && processResult.exitCode() == 0) {
      return ToolResults.completed(call, output, metadata, this.resultStore, 32768);
    } else {
      String prefix =
          processResult.timedOut()
              ? "Command timed out"
              : "Command exited with code " + processResult.exitCode();
      return new ToolResult(
          call.toolCallId(),
          Status.FAILED,
          output.isBlank() ? prefix : prefix + "\n" + output,
          Optional.empty(),
          metadata);
    }
  }

  private static CancellationToken cancellationToken(ToolContext context) {
    Object value = context.attributes().get("cancellationToken");
    if (value == null) {
      return new CancellationToken();
    } else if (value instanceof CancellationToken) {
      return (CancellationToken) value;
    } else {
      throw new IllegalArgumentException("cancellationToken attribute has an invalid type");
    }
  }
}
