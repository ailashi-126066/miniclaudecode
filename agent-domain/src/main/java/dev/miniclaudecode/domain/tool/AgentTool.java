package dev.miniclaudecode.domain.tool;

import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public interface AgentTool {
  ToolDescriptor descriptor();

  CompletionStage<ToolResult> execute(ToolCall call, AgentTool.ToolContext context);

  public static record ToolContext(
      SessionId sessionId,
      TurnId turnId,
      Path workspace,
      EventSink eventSink,
      Map<String, Object> attributes) {
    public ToolContext(
        SessionId sessionId,
        TurnId turnId,
        Path workspace,
        EventSink eventSink,
        Map<String, Object> attributes) {
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(turnId, "turnId must not be null");
      workspace =
          Objects.requireNonNull(workspace, "workspace must not be null")
              .toAbsolutePath()
              .normalize();
      Objects.requireNonNull(eventSink, "eventSink must not be null");
      attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
      this.sessionId = sessionId;
      this.turnId = turnId;
      this.workspace = workspace;
      this.eventSink = eventSink;
      this.attributes = attributes;
    }
  }
}
