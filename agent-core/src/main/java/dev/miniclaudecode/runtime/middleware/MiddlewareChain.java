package dev.miniclaudecode.runtime.middleware;

import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MiddlewareChain {
  private final List<AgentMiddleware> middleware;

  public MiddlewareChain(List<AgentMiddleware> middleware) {
    this.middleware =
        List.copyOf(Objects.requireNonNull(middleware, "middleware must not be null"));
  }

  public static MiddlewareChain empty() {
    return new MiddlewareChain(List.of());
  }

  public void beforeTurn(MiniClaudeState state) {
    middleware.forEach(item -> item.beforeTurn(state));
  }

  public void beforeModel(MiniClaudeState state) {
    middleware.forEach(item -> item.beforeModel(state));
  }

  public void afterModel(MiniClaudeState state, Map<String, Object> update) {
    middleware.forEach(item -> item.afterModel(state, update));
  }

  public void beforeTools(MiniClaudeState state) {
    state
        .pendingToolCalls()
        .forEach(call -> middleware.forEach(item -> item.beforeTool(state, call)));
  }

  public void afterTools(MiniClaudeState state, Map<String, Object> update) {
    Object raw = update.get(MiniClaudeState.TOOL_RESULTS);
    if (raw instanceof Iterable<?> values) {
      for (Object value : values) {
        if (value instanceof ToolResult result) {
          middleware.forEach(item -> item.afterTool(state, result));
        }
      }
    }
  }

  public void afterTurn(MiniClaudeState state) {
    middleware.forEach(item -> item.afterTurn(state));
  }
}
