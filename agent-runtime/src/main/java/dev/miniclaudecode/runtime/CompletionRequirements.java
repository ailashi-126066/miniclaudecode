package dev.miniclaudecode.runtime;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.runtime.node.ExecuteToolsNode;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.List;

/** Shared, deterministic completion gates used by routing and terminal validation. */
public final class CompletionRequirements {
  private CompletionRequirements() {}

  public static boolean requiresVerification(MiniClaudeState state) {
    if (!Boolean.TRUE.equals(state.request().attributes().get("requireVerification"))) {
      return false;
    }
    int lastMutation = -1;
    int lastSuccessfulVerification = -1;
    List<AgentMessage> messages = state.messages();
    for (int index = 0; index < messages.size(); index++) {
      if (messages.get(index) instanceof ToolMessage tool) {
        if (isMutation(tool.qualifiedToolName()) && !tool.error()) {
          lastMutation = index;
        } else if ("shell:run".equals(tool.qualifiedToolName())
            && !tool.error()
            && tool.text().startsWith(ExecuteToolsNode.VERIFICATION_SUCCEEDED_PREFIX)) {
          lastSuccessfulVerification = index;
        }
      }
    }
    return lastMutation > lastSuccessfulVerification;
  }

  public static boolean hasIncompleteTasks(MiniClaudeState state) {
    if (!Boolean.TRUE.equals(state.request().attributes().get("requireTaskCompletion"))) {
      return false;
    }
    ToolMessage latest = null;
    for (AgentMessage message : state.messages()) {
      if (message instanceof ToolMessage tool
          && "task:todo".equals(tool.qualifiedToolName())
          && !tool.error()) {
        latest = tool;
      }
    }
    return latest != null && (latest.text().contains("[ ]") || latest.text().contains("[>]"));
  }

  private static boolean isMutation(String name) {
    return "workspace:write".equals(name)
        || "workspace:edit".equals(name)
        || "workspace:apply_patch".equals(name);
  }
}
