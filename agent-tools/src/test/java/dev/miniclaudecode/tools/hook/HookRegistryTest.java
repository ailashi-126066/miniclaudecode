package dev.miniclaudecode.tools.hook;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HookRegistryTest {
  @TempDir Path workspace;

  @Test
  void firstDenyWinsOverLaterHooks() {
    HookRegistry registry =
        new HookRegistry(
            List.of(context -> HookDecision.deny("blocked"), context -> HookDecision.allow()));

    assertThat(registry.evaluate(beforeTool()).kind()).isEqualTo(HookDecision.Kind.DENY);
    assertThat(registry.evaluate(beforeTool()).reason()).isEqualTo("blocked");
  }

  private HookContext beforeTool() {
    ToolContext context =
        new ToolContext(SessionId.random(), TurnId.of(1), workspace, EventSink.NOOP, Map.of());
    return new HookContext(
        HookPhase.BEFORE_TOOL,
        new ToolCall("call", "workspace:write", "{\"path\":\"src/App.java\"}"),
        Optional.empty(),
        context);
  }
}
