package dev.miniclaudecode.runtime.workflow;

import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.Locale;
import java.util.regex.Pattern;

/** Rule floor plus model/tool upgrade. An upgrade can never lower a rule-mandated complex task. */
public final class TaskComplexityRouter {
  private static final Pattern COMPLEX =
      Pattern.compile(
          "(?is).*(跨模块|数据库迁移|schema migration|multiple modules|cross[- ]module|先.+再.+然后|分阶段|制定计划|create (a )?plan|high[- ]risk|生产部署|production deploy).*");
  private static final Pattern MUTATION =
      Pattern.compile(
          "(?is).*(修改|修复|实现|新增|添加|删除|重构|迁移|写入|改成|change|fix|implement|add|delete|remove|refactor|migrate|write|update).*");

  public Decision decide(MiniClaudeState state) {
    String prompt =
        state.messages().stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .map(UserMessage::text)
            .reduce((ignored, latest) -> latest)
            .orElse("")
            .toLowerCase(Locale.ROOT);
    boolean ruleComplex = COMPLEX.matcher(prompt).matches();
    boolean modelUpgrade =
        state.toolResults().stream()
            .anyMatch(result -> Boolean.TRUE.equals(result.metadata().get("planningRequested")));
    boolean hasExecutionTools =
        state.request().tools().stream().anyMatch(tool -> tool.effect().requiresPlan());
    return new Decision(
        ruleComplex || modelUpgrade ? ExecutionMode.PLANNED : ExecutionMode.SIMPLE,
        hasExecutionTools && MUTATION.matcher(prompt).matches());
  }

  public record Decision(ExecutionMode mode, boolean needsExecution) {}
}
