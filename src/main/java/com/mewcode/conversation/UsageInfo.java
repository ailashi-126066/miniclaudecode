package com.mewcode.conversation;

/** Provider-reported token usage for one completed assistant response. */
public record UsageInfo(
        int inputTokens,
        int outputTokens,
        int cacheReadTokens,
        int cacheCreationTokens) {

    public UsageInfo {
        if (inputTokens < 0 || outputTokens < 0 || cacheReadTokens < 0 || cacheCreationTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }

    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
