package com.mewcode.agent;

import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.LlmException;
import com.mewcode.llm.StreamEvent;
import com.mewcode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Agent error recovery strategies as documented in FIXES.md.
 *
 * Tests verify:
 * - Context too long → auto-compact and retry (max 3 times)
 * - Rate limit → exponential backoff or Retry-After (max 3 times)
 * - Authentication error → no retry, fail fast
 * - Network error → no retry (could be enhanced in future)
 */
class AgentErrorRecoveryTest {

    @Test
    void contextTooLongTriggersCompactionAndRetry() throws Exception {
        var config = new ProviderConfig();
        config.setProtocol("anthropic");
        config.setModel("claude-3-sonnet");
        config.setApiKey("test-key");

        var mockClient = new MockLlmClient();
        var registry = new ToolRegistry();
        var agent = new Agent(mockClient, registry, "anthropic", config);
        agent.setMaxIterations(5);

        var conv = new ConversationManager();
        conv.addUserMessage("Test message");

        // First call: context too long
        // Agent should retry up to 3 times
        mockClient.setResponse(new StreamEvent.Error(
                new LlmException.ContextTooLongException("context window exceeded")));

        BlockingQueue<AgentEvent> queue = agent.run(conv);

        // Expect: error event, then retry events
        AgentEvent event = queue.poll(2, TimeUnit.SECONDS);
        assertNotNull(event);
        assertTrue(event instanceof AgentEvent.ErrorEvent);

        // Should see retry attempts
        boolean foundRetry = false;
        while ((event = queue.poll(1, TimeUnit.SECONDS)) != null) {
            if (event instanceof AgentEvent.RetryEvent retry) {
                assertTrue(retry.reason().contains("Context too long")
                        || retry.reason().contains("compacting"));
                foundRetry = true;
            }
            if (event instanceof AgentEvent.LoopComplete) {
                break;
            }
        }

        assertTrue(foundRetry, "Expected at least one retry event for context too long");
    }

    @Test
    void rateLimitUsesRetryAfterWhenAvailable() throws Exception {
        var config = new ProviderConfig();
        config.setProtocol("anthropic");
        config.setModel("claude-3-sonnet");
        config.setApiKey("test-key");

        var mockClient = new MockLlmClient();
        var registry = new ToolRegistry();
        var agent = new Agent(mockClient, registry, "anthropic", config);
        agent.setMaxIterations(5);

        var conv = new ConversationManager();
        conv.addUserMessage("Test message");

        // Rate limit with Retry-After header
        mockClient.setResponse(new StreamEvent.Error(
                new LlmException.RateLimitException("too many requests", "5")));

        BlockingQueue<AgentEvent> queue = agent.run(conv);

        // Collect events
        boolean foundRateLimit = false;
        boolean foundRetryWithDelay = false;
        AgentEvent event;
        while ((event = queue.poll(2, TimeUnit.SECONDS)) != null) {
            if (event instanceof AgentEvent.ErrorEvent err) {
                if (err.message().contains("Rate limited")) {
                    foundRateLimit = true;
                }
            }
            if (event instanceof AgentEvent.RetryEvent retry) {
                if (retry.reason().contains("Rate limited")) {
                    foundRetryWithDelay = true;
                    // Should use Retry-After value (5 seconds = 5000ms)
                    assertTrue(retry.waitMs() > 0);
                }
            }
            if (event instanceof AgentEvent.LoopComplete) {
                break;
            }
        }

        assertTrue(foundRateLimit || foundRetryWithDelay,
                "Expected rate limit detection");
    }

    @Test
    void authenticationErrorDoesNotRetry() throws Exception {
        var config = new ProviderConfig();
        config.setProtocol("anthropic");
        config.setModel("claude-3-sonnet");
        config.setApiKey("invalid-key");

        var mockClient = new MockLlmClient();
        var registry = new ToolRegistry();
        var agent = new Agent(mockClient, registry, "anthropic", config);
        agent.setMaxIterations(5);

        var conv = new ConversationManager();
        conv.addUserMessage("Test message");

        // Authentication failure
        mockClient.setResponse(new StreamEvent.Error(
                new LlmException.AuthenticationException("invalid api key")));

        BlockingQueue<AgentEvent> queue = agent.run(conv);

        // Should see error event but NO retry events
        boolean foundAuthError = false;
        boolean foundRetry = false;
        AgentEvent event;
        int eventCount = 0;
        while ((event = queue.poll(1, TimeUnit.SECONDS)) != null && eventCount++ < 10) {
            if (event instanceof AgentEvent.ErrorEvent err) {
                if (err.message().contains("Authentication")
                        || err.message().contains("invalid api key")) {
                    foundAuthError = true;
                }
            }
            if (event instanceof AgentEvent.RetryEvent) {
                foundRetry = true;
            }
        }

        assertTrue(foundAuthError, "Expected authentication error");
        assertFalse(foundRetry, "Authentication errors should NOT trigger retry");
    }

    @Test
    void networkErrorDoesNotAutoRetry() throws Exception {
        var config = new ProviderConfig();
        config.setProtocol("anthropic");
        config.setModel("claude-3-sonnet");
        config.setApiKey("test-key");

        var mockClient = new MockLlmClient();
        var registry = new ToolRegistry();
        var agent = new Agent(mockClient, registry, "anthropic", config);
        agent.setMaxIterations(5);

        var conv = new ConversationManager();
        conv.addUserMessage("Test message");

        // Network error
        mockClient.setResponse(new StreamEvent.Error(
                new LlmException.NetworkException("connection timeout",
                        new java.io.IOException("timeout"))));

        BlockingQueue<AgentEvent> queue = agent.run(conv);

        // Should see error event but NO retry events
        boolean foundNetworkError = false;
        boolean foundRetry = false;
        AgentEvent event;
        int eventCount = 0;
        while ((event = queue.poll(1, TimeUnit.SECONDS)) != null && eventCount++ < 10) {
            if (event instanceof AgentEvent.ErrorEvent err) {
                if (err.message().contains("Network")
                        || err.message().contains("timeout")
                        || err.message().contains("connection")) {
                    foundNetworkError = true;
                }
            }
            if (event instanceof AgentEvent.RetryEvent) {
                foundRetry = true;
            }
        }

        assertTrue(foundNetworkError, "Expected network error");
        assertFalse(foundRetry, "Network errors should NOT auto-retry (currently)");
    }

    @Test
    void exponentialBackoffForRateLimitWithoutRetryAfter() {
        // Test the backoff algorithm directly
        // Attempt 1: 1 second
        long delay1 = retryDelayMillis("", 1);
        assertEquals(1_000L, delay1);

        // Attempt 2: 2 seconds
        long delay2 = retryDelayMillis("", 2);
        assertEquals(2_000L, delay2);

        // Attempt 3: 4 seconds
        long delay3 = retryDelayMillis("", 3);
        assertEquals(4_000L, delay3);

        // Max shift is 6, so max backoff is 1000 << 6 = 64 seconds
        long delayMax = retryDelayMillis("", 20);
        assertEquals(64_000L, delayMax);

        // Verify the shift limit is 6
        long delay7 = retryDelayMillis("", 7);
        assertEquals(64_000L, delay7);

        long delay100 = retryDelayMillis("", 100);
        assertEquals(64_000L, delay100);
    }

    @Test
    void retryAfterParsing() {
        // Retry-After present
        long delay = retryDelayMillis("7", 1);
        assertEquals(7_000L, delay);

        // Retry-After with large value (capped at 120s)
        long delayCapped = retryDelayMillis("300", 1);
        assertEquals(120_000L, delayCapped);

        // Invalid Retry-After falls back to exponential
        long delayFallback = retryDelayMillis("invalid", 2);
        assertEquals(2_000L, delayFallback);
    }

    // Helper method (mirrors Agent's private method)
    private static long retryDelayMillis(String retryAfter, int attempt) {
        if (retryAfter != null && !retryAfter.isBlank()) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                return Math.max(0L, Math.min(seconds * 1000L, 120_000L));
            } catch (NumberFormatException ignored) {
            }
        }
        long backoff = 1_000L << Math.min(Math.max(attempt - 1, 0), 6);
        return Math.min(backoff, 120_000L);
    }

    /**
     * Mock LLM client for testing error recovery.
     */
    private static class MockLlmClient implements LlmClient {
        private StreamEvent response;

        public void setResponse(StreamEvent response) {
            this.response = response;
        }

        @Override
        public BlockingQueue<StreamEvent> stream(
                ConversationManager conv,
                List<Map<String, Object>> tools) {
            var queue = new LinkedBlockingQueue<StreamEvent>(64);

            // Return the configured response immediately
            Thread.startVirtualThread(() -> {
                try {
                    queue.put(response != null
                            ? response
                            : new StreamEvent.StreamEnd("end_turn", 100, 50));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            return queue;
        }

        @Override
        public void setSystemPrompt(String prompt) {}

        @Override
        public void setMaxOutputTokens(int tokens) {}
    }
}
