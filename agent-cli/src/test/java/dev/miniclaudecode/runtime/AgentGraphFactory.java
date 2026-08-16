package dev.miniclaudecode.runtime;

import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.runtime.state.MiniClaudeState;

/** Test-only bridge while historical graph-named contract tests are renamed to AgentLoop tests. */
final class AgentGraphFactory {
  private final AgentLoop loop;

  AgentGraphFactory(ModelClient model, ToolExecutor tools, TurnLimits limits) {
    this.loop = new AgentLoop(model, tools, limits);
  }

  AgentGraphFactory(
      ModelClient model,
      ToolExecutor tools,
      TurnLimits limits,
      Object ignoredCheckpoint,
      CancellationToken cancellation,
      TurnProgressListener progress) {
    this.loop =
        new AgentLoop(
            model,
            tools,
            limits,
            cancellation,
            progress,
            PlanProgressListener.noOp(),
            java.util.List.of());
  }

  MiniClaudeState run(dev.miniclaudecode.domain.model.ModelRequest request) {
    return loop.run(request);
  }
}
