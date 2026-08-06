package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.cli.StreamingRenderer.RenderEvent;
import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent.UsageReported;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.persistence.checkpoint.FileCheckpointSaver;
import dev.miniclaudecode.persistence.config.ProviderProfile;
import dev.miniclaudecode.persistence.ledger.JsonToolExecutionLedger;
import dev.miniclaudecode.runtime.AgentGraphFactory;
import dev.miniclaudecode.runtime.AgentThreadRunner;
import dev.miniclaudecode.runtime.LedgeredToolExecutor;
import dev.miniclaudecode.runtime.TurnLimits;
import dev.miniclaudecode.runtime.TurnProgressListener;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Creates one bounded model/tool runner and its durable per-turn collaborators. */
final class TurnCoordinator {
  private final WorkspaceComponents components;
  private final SessionAuditService audit;
  private final Clock clock;

  TurnCoordinator(WorkspaceComponents components, SessionAuditService audit, Clock clock) {
    this.components = components;
    this.audit = audit;
    this.clock = clock;
  }

  ModelRequest request(ApplicationSession.TurnSelection selected, List<AgentMessage> messages) {
    ProviderProfile profile = components.config().providers().get(selected.provider());
    if (profile == null) {
      throw new IllegalArgumentException("unknown provider profile: " + selected.provider());
    }
    return new ModelRequest(
        selected.provider(),
        selected.model(),
        messages,
        components.tools().descriptors(),
        selected.thinking(),
        profile.maxOutputTokens(),
        requestAttributes(profile));
  }

  AgentThreadRunner createRunner(
      SessionId sessionId,
      TurnId turn,
      CancellationToken cancellationToken,
      Consumer<RenderEvent> renderer,
      Consumer<UsageReported> usageObserver,
      TurnProgressListener progressListener) {
    AuditedModelClient model =
        new AuditedModelClient(
            components.modelClient(),
            sessionId,
            turn,
            audit.store(),
            renderer,
            clock,
            usageObserver);
    RegistryToolExecutor executor =
        new RegistryToolExecutor(
            components.tools(),
            sessionId,
            turn,
            components.workspace(),
            audit.store(),
            cancellationToken,
            renderer,
            clock);
    Path sessionRoot =
        components.layout().sessionWorkspaceRoot(components.workspace()).resolve(sessionId.value());
    JsonToolExecutionLedger ledger =
        new JsonToolExecutionLedger(sessionRoot.resolve("tool-ledger-" + turn.value() + ".json"));
    FileCheckpointSaver<MiniClaudeState> checkpoint =
        new FileCheckpointSaver<>(
            components
                .layout()
                .checkpointsRoot()
                .resolve(components.layout().workspaceHash(components.workspace())),
            MiniClaudeState::new);
    return new AgentThreadRunner(
        new AgentGraphFactory(
            model,
            new LedgeredToolExecutor(executor, ledger, clock),
            new TurnLimits(24, 64),
            checkpoint,
            cancellationToken,
            progressListener));
  }

  private Map<String, Object> requestAttributes(ProviderProfile profile) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("workspace", components.workspace().toString());
    attributes.put("requireVerification", true);
    attributes.put("requireTaskCompletion", true);
    attributes.put("maxRetries", profile.maxRetries());
    attributes.put("maxCompactions", 3);
    attributes.put("requireRagCitations", true);
    attributes.put("outputProtocol", profile.outputProtocol());
    attributes.put("maxOutputRepairs", profile.maxOutputRepairs());
    return Map.copyOf(attributes);
  }
}
