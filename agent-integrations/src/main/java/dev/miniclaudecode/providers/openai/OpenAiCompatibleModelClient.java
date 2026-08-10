package dev.miniclaudecode.providers.openai;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder;
import dev.miniclaudecode.providers.LangChainStreamingModelClient;
import dev.miniclaudecode.providers.ProviderSpec;
import dev.miniclaudecode.providers.ProviderSpec.Type;
import dev.miniclaudecode.providers.ThinkingSupport;
import java.net.URI;

public final class OpenAiCompatibleModelClient extends LangChainStreamingModelClient {
  public OpenAiCompatibleModelClient(ProviderSpec spec) {
    this(build(requireType(spec)), spec);
  }

  OpenAiCompatibleModelClient(StreamingChatModel model, ProviderSpec spec) {
    super(model, ThinkingSupport.BEST_EFFORT, spec.apiKey(), spec.timeout());
  }

  private static StreamingChatModel build(ProviderSpec spec) {
    OpenAiStreamingChatModelBuilder builder =
        OpenAiStreamingChatModel.builder()
            .apiKey((String) spec.apiKey().orElseThrow())
            .modelName(spec.model())
            .temperature(spec.temperature())
            .maxCompletionTokens(spec.maxOutputTokens())
            .timeout(spec.timeout())
            .returnThinking(true)
            .sendThinking(true);
    spec.baseUrl().ifPresent(uri -> builder.baseUrl(normalizeBaseUrl(uri)));
    if (spec.thinking()) {
      builder.reasoningEffort("medium");
    }

    return builder.build();
  }

  static String normalizeBaseUrl(URI baseUrl) {
    String value = baseUrl.toString();

    while (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }

    String path = baseUrl.getPath();
    boolean rootOnly = path == null || path.isEmpty() || path.equals("/");
    return rootOnly ? value + "/v1" : value;
  }

  private static ProviderSpec requireType(ProviderSpec spec) {
    if (spec != null && spec.type() == Type.OPENAI_COMPATIBLE) {
      return spec;
    } else {
      throw new IllegalArgumentException(
          "OpenAiCompatibleModelClient requires an OPENAI_COMPATIBLE spec");
    }
  }
}
