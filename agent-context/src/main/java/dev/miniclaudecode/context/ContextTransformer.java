package dev.miniclaudecode.context;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import java.util.List;

/**
 * One independently testable context transformation stage.
 *
 * <p>Implementations must return a snapshot and must not mutate the input list.
 */
@FunctionalInterface
public interface ContextTransformer {
  List<AgentMessage> transform(ModelRequest request, List<AgentMessage> messages);
}
