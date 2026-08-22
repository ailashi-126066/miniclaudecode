package com.mewcode.agent;

import com.mewcode.compact.ContextCompactor;
import com.mewcode.compact.RecoveryState;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.LlmException;
import com.mewcode.toolresult.ContentReplacementState;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Mutable state that survives iterations of one Agent run. */
final class AgentLoopState {
    ConversationManager conversation;

    final ContextCompactor.AutoCompactTrackingState compactTracking =
            new ContextCompactor.AutoCompactTrackingState();
    ContextCompactor.UsageAnchor usageAnchor;
    ContentReplacementState replacementState = new ContentReplacementState();
    final RecoveryState recoveryState = new RecoveryState();

    CompletableFuture<String> memoryRecallFuture;
    boolean memoryRecallConsumed;
    CompletableFuture<String> knowledgeRecallFuture;
    boolean knowledgeRecallConsumed;

    int iteration;
    int totalInputTokens;
    int totalOutputTokens;
    int outputRecoveries;
    int contextRetries;
    int rateLimitRetries;
    boolean maxTokensEscalated;
    boolean loopCompleted;
    LlmException lastStreamException;

    void beginRun(ConversationManager conversation) {
        this.conversation = Objects.requireNonNull(conversation, "conversation");
        iteration = 0;
        totalInputTokens = 0;
        totalOutputTokens = 0;
        outputRecoveries = 0;
        contextRetries = 0;
        rateLimitRetries = 0;
        maxTokensEscalated = false;
        loopCompleted = false;
        lastStreamException = null;
    }

    void setMemoryRecallFuture(CompletableFuture<String> future) {
        memoryRecallFuture = future;
        memoryRecallConsumed = false;
    }

    void setKnowledgeRecallFuture(CompletableFuture<String> future) {
        knowledgeRecallFuture = future;
        knowledgeRecallConsumed = false;
    }
}
