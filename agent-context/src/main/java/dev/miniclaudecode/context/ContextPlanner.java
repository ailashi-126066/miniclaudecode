package dev.miniclaudecode.context;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.AssistantMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ContextPlanner {
  private static final int DEFAULT_CONTEXT_WINDOW = 128000;
  private static final int MESSAGE_OVERHEAD_TOKENS = 4;
  private static final Encoding TOKENIZER =
      Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
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
    return plan(request, messages, 0);
  }

  /**
   * Plans against an optional provider-reported input-token value. Provider usage is authoritative
   * for the request it describes, so it is used as a lower bound while the local tokenizer counts
   * messages added since that response.
   */
  public ContextPlanner.Plan plan(
      ModelRequest request, List<AgentMessage> messages, long providerReportedInputTokens) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(messages, "messages must not be null");
    int contextWindow = contextWindow(request);
    int inputBudget = Math.max(1, contextWindow - request.maxOutputTokens());
    int estimatedTokens = this.estimateTokens(messages);
    if (providerReportedInputTokens > 0) {
      estimatedTokens =
          (int) Math.min(Integer.MAX_VALUE, Math.max(estimatedTokens, providerReportedInputTokens));
    }
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
    long tokens = 0L;
    for (AgentMessage message : messages) {
      tokens += MESSAGE_OVERHEAD_TOKENS + TOKENIZER.countTokens(message.text());
      if (message instanceof AssistantMessage assistant) {
        for (var call : assistant.toolCalls()) {
          tokens += MESSAGE_OVERHEAD_TOKENS + TOKENIZER.countTokens(call.qualifiedName());
          tokens += TOKENIZER.countTokens(call.argumentsJson());
        }
      }
    }
    return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, tokens));
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
