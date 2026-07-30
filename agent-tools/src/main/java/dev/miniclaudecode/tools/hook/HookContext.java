package dev.miniclaudecode.tools.hook;

import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import java.util.Objects;
import java.util.Optional;

/** Immutable event passed to hooks. Hooks may inspect but never mutate a tool context. */
public record HookContext(
    HookPhase phase, ToolCall call, Optional<ToolResult> result, ToolContext toolContext) {
  public HookContext {
    Objects.requireNonNull(phase, "phase must not be null");
    Objects.requireNonNull(toolContext, "toolContext must not be null");
    result = Objects.requireNonNull(result, "result must not be null");
    if ((phase == HookPhase.BEFORE_TOOL) != result.isEmpty()) {
      throw new IllegalArgumentException(
          "before-tool hooks have no result; later hooks require one");
    }
  }
}
