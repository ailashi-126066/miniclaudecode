package com.mewcode.llm;
import com.fasterxml.jackson.databind.JsonNode;import com.mewcode.config.ProviderConfig;
/** Compatibility name retained for the MewCode tutorials; transport is LangChain4j. */
public final class OpenAiCompatClient extends LangChainClient {public OpenAiCompatClient(ProviderConfig config,String systemPrompt){super(config,systemPrompt);}static int[] extractUsage(JsonNode root){JsonNode usage=root.path("usage");if(usage.isMissingNode())return new int[]{0,0,0};int prompt=usage.path("prompt_tokens").asInt(0);int output=usage.path("completion_tokens").asInt(0);int cached=usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);return new int[]{Math.max(0,prompt-cached),output,cached};}}
