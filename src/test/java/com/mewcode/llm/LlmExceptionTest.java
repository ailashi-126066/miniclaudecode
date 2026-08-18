package com.mewcode.llm;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class LlmExceptionTest {

    @Test
    void classifiesAuthenticationStatus() {
        LlmException error = LlmException.classify(
                new HttpException(401, "invalid api key"));

        assertInstanceOf(LlmException.AuthenticationException.class, error);
        assertTrue(error.getMessage().contains("Authentication"));
    }

    @Test
    void classifiesForbiddenAsAuthentication() {
        LlmException error = LlmException.classify(
                new HttpException(403, "forbidden"));

        assertInstanceOf(LlmException.AuthenticationException.class, error);
    }

    @Test
    void classifiesRateLimitAndExtractsRetryAfter() {
        LlmException error = LlmException.classify(
                new HttpException(429, "too many requests; Retry-After: 7"));

        assertInstanceOf(LlmException.RateLimitException.class, error);
        assertEquals("7", ((LlmException.RateLimitException) error).getRetryAfter());
    }

    @Test
    void preservesLangChainRateLimitType() {
        LlmException error = LlmException.classify(
                new RateLimitException("provider rate limit"));

        assertInstanceOf(LlmException.RateLimitException.class, error);
    }

    @Test
    void classifiesExplicit413AsContextTooLong() {
        LlmException error = LlmException.classify(
                new HttpException(413, "request entity too large"));

        assertInstanceOf(LlmException.ContextTooLongException.class, error);
    }

    @Test
    void classifiesPromptTooLongBodyEvenWhenStatusIs400() {
        LlmException error = LlmException.classify(
                new HttpException(400, "prompt is too long for this model"));

        assertInstanceOf(LlmException.ContextTooLongException.class, error);
    }

    @Test
    void classifiesIoFailureAsNetwork() {
        LlmException error = LlmException.classify(
                new IOException("connection reset"));

        assertInstanceOf(LlmException.NetworkException.class, error);
        assertTrue(error.getCause() instanceof IOException);
    }

    @Test
    void passesThroughExistingLlmException() {
        LlmException original = new LlmException.ContextTooLongException("already typed");

        assertSame(original, LlmException.classify(original));
    }

    @Test
    void fallsBackToGenericLlmException() {
        LlmException error = LlmException.classify(
                new IllegalStateException("unexpected provider failure"));

        assertEquals(LlmException.class, error.getClass());
        assertTrue(error.getMessage().contains("unexpected provider failure"));
    }

    @Test
    void errorEventKeepsTypedExceptionAndCompatibilityMessage() {
        var exception = new LlmException.RateLimitException("limited", "3");
        var event = new StreamEvent.Error(exception);

        assertSame(exception, event.exception());
        assertEquals("limited", event.message());
    }

    @Test
    void classifyHttpErrorCanBeTestedWithoutTransport() {
        LlmException error = LlmException.classifyHttpError(
                429, "rate limited", "11", null);

        assertInstanceOf(LlmException.RateLimitException.class, error);
        assertEquals("11", ((LlmException.RateLimitException) error).getRetryAfter());
    }
}
