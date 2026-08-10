package dev.miniclaudecode.tools.fs;

import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolEffect;
import dev.miniclaudecode.tools.approval.PermissionEngine;
import dev.miniclaudecode.tools.approval.RiskClassifier;
import dev.miniclaudecode.tools.diff.UnifiedDiffService;
import dev.miniclaudecode.tools.internal.ToolArguments;

public final class WriteTool extends AbstractFileMutationTool {
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "workspace",
          "write",
          "Create or replace a UTF-8 workspace file after diff-bound approval",
          "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}",
          RiskLevel.MEDIUM,
          ToolEffect.MUTATION);

  public WriteTool(WorkspacePathResolver resolver, PermissionEngine permissionEngine) {
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
    return arguments.requiredString("content");
  }

  @Override
  protected String mutationReason(String displayPath) {
    return "create or replace workspace file " + displayPath;
  }
}
