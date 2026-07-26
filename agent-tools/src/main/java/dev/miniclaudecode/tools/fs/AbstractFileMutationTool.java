package dev.miniclaudecode.tools.fs;

import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.tools.approval.PermissionEngine;
import dev.miniclaudecode.tools.approval.RiskClassifier;
import dev.miniclaudecode.tools.diff.FileHashes;
import dev.miniclaudecode.tools.diff.UnifiedDiffService;
import dev.miniclaudecode.tools.internal.TextFiles;
import dev.miniclaudecode.tools.internal.ToolArguments;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

abstract class AbstractFileMutationTool implements AgentTool {
  private final WorkspacePathResolver resolver;
  private final PermissionEngine permissionEngine;
  private final UnifiedDiffService diffService;
  private final AtomicFileWriter writer;
  private final RiskClassifier riskClassifier;

  protected AbstractFileMutationTool(
      WorkspacePathResolver resolver,
      PermissionEngine permissionEngine,
      UnifiedDiffService diffService,
      AtomicFileWriter writer,
      RiskClassifier riskClassifier) {
    this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    this.permissionEngine =
        Objects.requireNonNull(permissionEngine, "permissionEngine must not be null");
    this.diffService = Objects.requireNonNull(diffService, "diffService must not be null");
    this.writer = Objects.requireNonNull(writer, "writer must not be null");
    this.riskClassifier = Objects.requireNonNull(riskClassifier, "riskClassifier must not be null");
  }

  public final CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      ToolArguments arguments = ToolArguments.parse(call.argumentsJson());
      String requestedPath = arguments.requiredText("path");
      Path target = this.resolver.resolveForWrite(requestedPath);
      String before = readBefore(target);
      String after = this.createAfter(arguments, before);
      if (before.equals(after)) {
        return completed(
            call,
            new ToolResult(
                call.toolCallId(),
                Status.COMPLETED,
                "No changes were necessary for " + this.resolver.relativeDisplay(target),
                Optional.empty(),
                Map.of("changed", false)));
      } else {
        String displayPath = this.resolver.relativeDisplay(target);
        UnifiedDiffService.DiffResult diff = this.diffService.create(displayPath, before, after);
        String beforeHash = FileHashes.hash(target);
        RiskLevel risk = this.riskClassifier.classifyFileMutation(displayPath, RiskLevel.MEDIUM);
        PermissionEngine.MutationPlan plan =
            new PermissionEngine.MutationPlan(
                call,
                risk,
                this.resolver.workspace().toString(),
                displayPath,
                this.mutationReason(displayPath),
                beforeHash,
                diff.diffHash(),
                diff.unifiedDiff());
        PermissionEngine.Authorization authorization =
            this.permissionEngine.authorize(plan, context);
        Objects.requireNonNull(authorization);

        return switch (authorization) {
          case PermissionEngine.Authorization.Requested requested ->
              completed(call, approvalRequired(call, requested.request(), diff.unifiedDiff()));
          case PermissionEngine.Authorization.Rejected rejected ->
              completed(
                  call,
                  new ToolResult(
                      call.toolCallId(),
                      Status.CANCELLED,
                      "File change rejected: " + rejected.feedback(),
                      Optional.empty(),
                      Map.of("path", displayPath)));
          case PermissionEngine.Authorization.Allowed ignored -> {
            this.writer.write(target, after.getBytes(StandardCharsets.UTF_8), beforeHash);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("path", displayPath);
            metadata.put("beforeHash", beforeHash);
            metadata.put("afterHash", FileHashes.hash(target));
            metadata.put("diffHash", diff.diffHash());
            metadata.put("changed", true);
            yield completed(
                call,
                new ToolResult(
                    call.toolCallId(),
                    Status.COMPLETED,
                    "Applied approved change to " + displayPath,
                    Optional.empty(),
                    metadata));
          }
          default -> throw new MatchException(null, null);
        };
      }
    } catch (RuntimeException var20) {
      return completed(
          call,
          new ToolResult(
              call.toolCallId(), Status.FAILED, safeMessage(var20), Optional.empty(), Map.of()));
    }
  }

  protected abstract String createAfter(ToolArguments arguments, String before);

  protected abstract String mutationReason(String displayPath);

  private static String readBefore(Path target) {
    if (!Files.exists(target)) {
      return "";
    } else if (!Files.isRegularFile(target)) {
      throw new IllegalArgumentException("mutation target is not a regular file");
    } else {
      try {
        return TextFiles.decodeUtf8(Files.readAllBytes(target));
      } catch (IOException var2) {
        throw new IllegalArgumentException("failed to read mutation target", var2);
      }
    }
  }

  private static ToolResult approvalRequired(
      ToolCall call, ApprovalRequest request, String unifiedDiff) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("approvalRequest", request);
    metadata.put("unifiedDiff", unifiedDiff);
    return new ToolResult(
        call.toolCallId(), Status.APPROVAL_REQUIRED, unifiedDiff, Optional.empty(), metadata);
  }

  private static CompletionStage<ToolResult> completed(ToolCall call, ToolResult result) {
    Objects.requireNonNull(call, "call must not be null");
    return CompletableFuture.completedFuture(result);
  }

  private static String safeMessage(RuntimeException exception) {
    String message = exception.getMessage();
    return message != null && !message.isBlank() ? message : exception.getClass().getSimpleName();
  }
}
