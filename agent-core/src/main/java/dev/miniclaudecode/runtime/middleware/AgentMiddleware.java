package dev.miniclaudecode.runtime.middleware;

import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.Map;

/** Ordered, non-routing lifecycle hooks. Middleware must not own graph transitions. */
public interface AgentMiddleware {
  default void beforeTurn(MiniClaudeState state) {}

  default void beforeModel(MiniClaudeState state) {}

  default void afterModel(MiniClaudeState state, Map<String, Object> update) {}

  default void beforeTool(MiniClaudeState state, ToolCall call) {}

  default void afterTool(MiniClaudeState state, ToolResult result) {}

  default void afterTurn(MiniClaudeState state) {}
}
