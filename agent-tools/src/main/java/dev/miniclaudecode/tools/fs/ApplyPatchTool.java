package dev.miniclaudecode.tools.fs;

import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.tools.approval.PermissionEngine;
import dev.miniclaudecode.tools.approval.RiskClassifier;
import dev.miniclaudecode.tools.diff.UnifiedDiffService;
import dev.miniclaudecode.tools.diff.UnifiedPatchApplier;
import dev.miniclaudecode.tools.internal.ToolArguments;

public final class ApplyPatchTool extends AbstractFileMutationTool {
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "workspace",
          "apply_patch",
          "Apply a unified diff to one UTF-8 workspace file after diff-bound approval",
          "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"patch\":{\"type\":\"string\"}},\"required\":[\"path\",\"patch\"]}",
          RiskLevel.MEDIUM);
  private final UnifiedPatchApplier patchApplier = new UnifiedPatchApplier();

  public ApplyPatchTool(WorkspacePathResolver resolver, PermissionEngine permissionEngine) {
    super(
        resolver,
        permissionEngine,
        new UnifiedDiffService(),
        new AtomicFileWriter(),
        new RiskClassifier());
  }

  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  protected String createAfter(ToolArguments arguments, String before) {
    return this.patchApplier.apply(before, arguments.requiredText("patch"));
  }

  @Override
  protected String mutationReason(String displayPath) {
    return "apply patch to workspace file " + displayPath;
  }
}
