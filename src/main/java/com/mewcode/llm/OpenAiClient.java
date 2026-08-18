package com.mewcode.llm;
import com.mewcode.config.ProviderConfig;
/** Compatibility name retained for the MewCode tutorials; transport is LangChain4j. */
public final class OpenAiClient extends LangChainClient {public OpenAiClient(ProviderConfig config,String systemPrompt){super(config,systemPrompt);}}
