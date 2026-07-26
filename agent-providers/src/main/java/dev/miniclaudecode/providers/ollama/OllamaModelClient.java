package dev.miniclaudecode.providers.ollama;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel.OllamaStreamingChatModelBuilder;
import dev.miniclaudecode.providers.LangChainStreamingModelClient;
import dev.miniclaudecode.providers.ProviderSpec;
import dev.miniclaudecode.providers.ProviderSpec.Type;
import dev.miniclaudecode.providers.ThinkingSupport;
import java.net.URI;

public final class OllamaModelClient extends LangChainStreamingModelClient {
  public OllamaModelClient(ProviderSpec spec) {
    this(build(requireType(spec)), spec);
  }

  OllamaModelClient(StreamingChatModel model, ProviderSpec spec) {
    super(model, ThinkingSupport.BEST_EFFORT, spec.apiKey(), spec.timeout());
  }

  private static StreamingChatModel build(ProviderSpec spec) {
    return ((OllamaStreamingChatModelBuilder)
            ((OllamaStreamingChatModelBuilder)
                    ((OllamaStreamingChatModelBuilder)
                            ((OllamaStreamingChatModelBuilder)
                                    ((OllamaStreamingChatModelBuilder)
                                            ((OllamaStreamingChatModelBuilder)
                                                    ((OllamaStreamingChatModelBuilder)
                                                            OllamaStreamingChatModel.builder()
                                                                .baseUrl(
                                                                    ((URI)
                                                                            spec.baseUrl()
                                                                                .orElseThrow())
                                                                        .toString()))
                                                        .modelName(spec.model()))
                                                .temperature(spec.temperature()))
                                        .numPredict(spec.maxOutputTokens()))
                                .think(spec.thinking()))
                        .returnThinking(true))
                .timeout(spec.timeout()))
        .build();
  }

  private static ProviderSpec requireType(ProviderSpec spec) {
    if (spec != null && spec.type() == Type.OLLAMA) {
      return spec;
    } else {
      throw new IllegalArgumentException("OllamaModelClient requires an OLLAMA spec");
    }
  }
}
