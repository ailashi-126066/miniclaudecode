package dev.miniclaudecode.runtime.output;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import java.util.Locale;

/**
 * Requires a bounded, auditable hand-off after the agent has changed workspace files.
 *
 * <p>This deliberately validates report structure rather than attempting to infer whether a
 * natural-language claim is true. The tool trace remains the source of truth for commands and
 * outcomes.
 */
public final class EngineeringReportValidator {
  public Evaluation evaluate(Iterable<AgentMessage> messages, String finalText) {
    if (!hasSuccessfulMutation(messages)) {
      return Evaluation.passed();
    }
    String normalized = finalText == null ? "" : finalText.toLowerCase(Locale.ROOT);
    boolean files = containsAny(normalized, "changed files", "changed file", "变更文件", "修改文件");
    boolean verification = containsAny(normalized, "verification", "tests run", "验证", "测试结果");
    boolean scope = containsAny(normalized, "unverified", "not verified", "未验证", "未覆盖");
    if (files && verification && scope) {
      return Evaluation.passed();
    }
    return Evaluation.invalid(
        "Because workspace files changed, finish with three explicit sections: Changed Files "
            + "(or 变更文件), Verification (or 验证/测试结果) listing only commands actually run "
            + "and their observed result, and Unverified Scope (or 未验证/未覆盖). Do not claim "
            + "a narrow test proves the whole project.");
  }

  private static boolean hasSuccessfulMutation(Iterable<AgentMessage> messages) {
    for (AgentMessage message : messages) {
      if (message instanceof ToolMessage tool
          && !tool.error()
          && ("workspace:write".equals(tool.qualifiedToolName())
              || "workspace:edit".equals(tool.qualifiedToolName())
              || "workspace:apply_patch".equals(tool.qualifiedToolName()))) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsAny(String text, String... fragments) {
    for (String fragment : fragments) {
      if (text.contains(fragment)) {
        return true;
      }
    }
    return false;
  }

  public record Evaluation(boolean valid, String repairInstruction) {
    static Evaluation passed() {
      return new Evaluation(true, "");
    }

    static Evaluation invalid(String repairInstruction) {
      return new Evaluation(false, repairInstruction);
    }
  }
}
