package dev.miniclaudecode.context;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import java.util.List;
import java.util.Objects;

/**
 * Ordered context stages that can be composed without coupling the loop to their implementations.
 */
public final class ContextPipeline {
  private final List<ContextTransformer> transformers;

  public ContextPipeline(List<ContextTransformer> transformers) {
    this.transformers =
        List.copyOf(Objects.requireNonNull(transformers, "transformers must not be null"));
  }

  public List<AgentMessage> transform(ModelRequest request, List<AgentMessage> messages) {
    List<AgentMessage> current =
        List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
    for (ContextTransformer transformer : this.transformers) {
      current =
          List.copyOf(
              Objects.requireNonNull(
                  transformer.transform(request, current),
                  "context transformer must not return null"));
    }
    return current;
  }
}
