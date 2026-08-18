package com.mewcode.llm;
import com.mewcode.config.ProviderConfig;
/** Compatibility name retained for the MewCode tutorials; transport is LangChain4j. */
public final class AnthropicClient extends LangChainClient {private final ProviderConfig config;public AnthropicClient(ProviderConfig config,String systemPrompt){super(config,systemPrompt);this.config=config;}public int fetchModelContextWindow(){return 0;}}
