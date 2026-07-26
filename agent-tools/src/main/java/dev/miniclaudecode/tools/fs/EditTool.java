package dev.miniclaudecode.tools.fs;

import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.tools.approval.PermissionEngine;
import dev.miniclaudecode.tools.approval.RiskClassifier;
import dev.miniclaudecode.tools.diff.UnifiedDiffService;
import dev.miniclaudecode.tools.internal.ToolArguments;

public final class EditTool extends AbstractFileMutationTool {
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "workspace",
          "edit",
          "Replace exact text in a UTF-8 workspace file after diff-bound approval",
          "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"oldText\":{\"type\":\"string\"},\"newText\":{\"type\":\"string\"},\"replaceAll\":{\"type\":\"boolean\"}},\"required\":[\"path\",\"oldText\",\"newText\"]}",
          RiskLevel.MEDIUM);

  public EditTool(WorkspacePathResolver resolver, PermissionEngine permissionEngine) {
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
    String oldText = arguments.requiredText("oldText");
    String newText = arguments.requiredString("newText");
    boolean replaceAll = arguments.optionalBoolean("replaceAll", false);
    int first = before.indexOf(oldText);
    if (first < 0) {
      throw new IllegalArgumentException("oldText was not found in the target file");
    } else {
      int second = before.indexOf(oldText, first + oldText.length());
      if (!replaceAll && second >= 0) {
        throw new IllegalArgumentException("oldText occurs more than once; set replaceAll=true");
      } else {
        return replaceAll
            ? before.replace(oldText, newText)
            : before.substring(0, first) + newText + before.substring(first + oldText.length());
      }
    }
  }

  @Override
  protected String mutationReason(String displayPath) {
    return "edit workspace file " + displayPath;
  }
}
