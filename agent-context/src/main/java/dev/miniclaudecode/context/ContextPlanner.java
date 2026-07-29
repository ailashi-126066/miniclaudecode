package dev.miniclaudecode.context;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.AssistantMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ContextPlanner {
  private static final int DEFAULT_CONTEXT_WINDOW = 128000;
  private final double compactionThreshold;

  public ContextPlanner() {
    this(0.82);
  }

  public ContextPlanner(double compactionThreshold) {
    if (!(compactionThreshold <= 0.0) && !(compactionThreshold >= 1.0)) {
      this.compactionThreshold = compactionThreshold;
    } else {
      throw new IllegalArgumentException("compactionThreshold must be between zero and one");
    }
  }

  public ContextPlanner.Plan plan(ModelRequest request, List<AgentMessage> messages) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(messages, "messages must not be null");
    int contextWindow = contextWindow(request);
    int inputBudget = Math.max(1, contextWindow - request.maxOutputTokens());
    int estimatedTokens = this.estimateTokens(messages);
    return new ContextPlanner.Plan(
        estimatedTokens,
        inputBudget,
        (double) estimatedTokens >= Math.floor((double) inputBudget * this.compactionThreshold));
  }

  public boolean isContextOverflow(String failureType, String message) {
    String value =
        ((failureType == null ? "" : failureType) + " " + (message == null ? "" : message))
            .toLowerCase(Locale.ROOT);
    return value.contains("context_length")
        || value.contains("context window")
        || value.contains("too many tokens")
        || value.contains("prompt is too long");
  }

  public int estimateTokens(List<AgentMessage> messages) {
    long characters =
        messages.stream()
            .mapToLong(
                message ->
                    (long)
                        (message.text().length()
                            + (message instanceof AssistantMessage assistant
                                ? assistant.toolCalls().stream()
                                    .mapToInt(call -> call.argumentsJson().length() + 16)
                                    .sum()
                                : 0)
                            + 12))
            .sum();
    return (int) Math.min(2147483647L, Math.max(1L, (characters + 3L) / 4L));
  }

  private static int contextWindow(ModelRequest request) {
    if (request.attributes().get("contextWindowTokens") instanceof Number number
        && number.intValue() > 0) {
      return number.intValue();
    }

    return 128000;
  }

  public static record Plan(int estimatedInputTokens, int inputBudgetTokens, boolean compact) {}
}
