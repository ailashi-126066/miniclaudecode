package dev.miniclaudecode.tools.rule;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.tools.hook.HookContext;
import dev.miniclaudecode.tools.hook.HookDecision;
import dev.miniclaudecode.tools.hook.HookPhase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopedRuleHookTest {
  @TempDir Path workspace;

  @Test
  void blocksMutationOfMatchedPath() throws Exception {
    Path rules = Files.createDirectories(workspace.resolve(".miniclaudecode/rules"));
    Files.writeString(
        rules.resolve("generated.md"),
        "---\npaths: generated/**\naction: deny\n---\nGenerated files are immutable.\n");
    ScopedRuleHook hook = ScopedRuleHook.load(workspace);

    assertThat(hook.evaluate(context("generated/Api.java")).kind())
        .isEqualTo(HookDecision.Kind.DENY);
    assertThat(hook.evaluate(context("src/Api.java")).kind()).isEqualTo(HookDecision.Kind.ALLOW);
  }

  private HookContext context(String path) {
    ToolContext toolContext =
        new ToolContext(SessionId.random(), TurnId.of(1), workspace, EventSink.NOOP, Map.of());
    return new HookContext(
        HookPhase.BEFORE_TOOL,
        new ToolCall("call", "workspace:write", "{\"path\":\"" + path + "\"}"),
        Optional.empty(),
        toolContext);
  }
}
