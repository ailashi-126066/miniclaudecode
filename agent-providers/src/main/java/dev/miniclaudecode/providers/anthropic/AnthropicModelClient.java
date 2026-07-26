package dev.miniclaudecode.providers.anthropic;

import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel.AnthropicStreamingChatModelBuilder;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.miniclaudecode.providers.LangChainStreamingModelClient;
import dev.miniclaudecode.providers.ProviderSpec;
import dev.miniclaudecode.providers.ProviderSpec.Type;
import dev.miniclaudecode.providers.ThinkingSupport;
import java.net.URI;

public final class AnthropicModelClient extends LangChainStreamingModelClient {
  public AnthropicModelClient(ProviderSpec spec) {
    this(build(requireType(spec)), spec);
  }

  AnthropicModelClient(StreamingChatModel model, ProviderSpec spec) {
    super(model, ThinkingSupport.NATIVE, spec.apiKey(), spec.timeout());
  }

  private static StreamingChatModel build(ProviderSpec spec) {
    AnthropicStreamingChatModelBuilder builder =
        AnthropicStreamingChatModel.builder()
            .apiKey((String) spec.apiKey().orElseThrow())
            .modelName(spec.model())
            .temperature(spec.thinking() ? null : spec.temperature())
            .maxTokens(spec.maxOutputTokens())
            .timeout(spec.timeout())
            .returnThinking(true)
            .sendThinking(true)
            .cacheSystemMessages(true)
            .cacheTools(true)
            .returnCacheDiagnostics(true);
    spec.baseUrl().ifPresent(uri -> builder.baseUrl(normalizeBaseUrl(uri)));
    if (spec.thinking()) {
      builder
          .thinkingType("enabled")
          .thinkingBudgetTokens(Math.max(1024, Math.min(spec.maxOutputTokens() / 2, 8192)))
          .thinkingDisplay("summarized");
    }

    return builder.build();
  }

  static String normalizeBaseUrl(URI baseUrl) {
    String value = baseUrl.toString();

    while (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }

    return value.endsWith("/v1") ? value : value + "/v1";
  }

  private static ProviderSpec requireType(ProviderSpec spec) {
    if (spec != null && spec.type() == Type.ANTHROPIC) {
      return spec;
    } else {
      throw new IllegalArgumentException("AnthropicModelClient requires an ANTHROPIC spec");
    }
  }
}
