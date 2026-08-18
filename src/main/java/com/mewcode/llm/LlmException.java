package com.mewcode.llm;

import dev.langchain4j.exception.HttpException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic failures raised while communicating with an LLM provider.
 *
 * <p>The transport layer converts provider/library exceptions into this small
 * taxonomy before publishing them as {@link StreamEvent.Error} events. The
 * base type remains usable as a safe fallback for errors that cannot be
 * classified more precisely.</p>
 */
public class LlmException extends RuntimeException {

    private static final Pattern STATUS_PATTERN = Pattern.compile("\\b([45]\\d{2})\\b");
    private static final Pattern RETRY_AFTER_PATTERN = Pattern.compile(
            "(?i)retry[- ]after\\s*[:=]\\s*(\\d+)(?:\\s*seconds?)?");

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }

    public static class AuthenticationException extends LlmException {
        public AuthenticationException(String message) {
            super(message);
        }

        public AuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class RateLimitException extends LlmException {
        private final String retryAfter;

        public RateLimitException(String message, String retryAfter) {
            super(message);
            this.retryAfter = retryAfter == null ? "" : retryAfter;
        }

        public RateLimitException(String message, String retryAfter, Throwable cause) {
            super(message, cause);
            this.retryAfter = retryAfter == null ? "" : retryAfter;
        }

        public String getRetryAfter() {
            return retryAfter;
        }
    }

    public static class ContextTooLongException extends LlmException {
        public ContextTooLongException(String message) {
            super(message);
        }

        public ContextTooLongException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class NetworkException extends LlmException {
        public NetworkException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Convert a provider or LangChain4j failure into a semantic LLM error.
     * This method never throws; the caller decides whether to publish or throw
     * the returned exception.
     */
    public static LlmException classify(Throwable failure) {
        if (failure == null) {
            return new LlmException("Unknown LLM error");
        }

        LlmException existing = findCause(failure, LlmException.class);
        if (existing != null) {
            return existing;
        }

        Throwable root = unwrap(failure);
        String message = messageOf(root);
        String lower = message.toLowerCase(Locale.ROOT);

        // LangChain4j exposes these semantic exception types directly.
        if (hasCause(failure, dev.langchain4j.exception.AuthenticationException.class)) {
            return new AuthenticationException("Authentication failed: " + message, failure);
        }
        if (hasCause(failure, dev.langchain4j.exception.RateLimitException.class)) {
            return new RateLimitException(
                    "Rate limited: " + message,
                    extractRetryAfter(message),
                    failure
            );
        }
        if (hasCause(failure, dev.langchain4j.exception.TimeoutException.class)
                || hasCause(failure, IOException.class)
                || hasCause(failure, ConnectException.class)
                || hasCause(failure, SocketTimeoutException.class)
                || hasCause(failure, HttpTimeoutException.class)) {
            return new NetworkException("Network error: " + message, failure);
        }

        HttpException http = findCause(failure, HttpException.class);
        int status = http != null ? http.statusCode() : extractStatus(message);
        if (status > 0) {
            return classifyHttpError(status, message, extractRetryAfter(message), failure);
        }

        if (isContextTooLong(lower)) {
            return new ContextTooLongException("Context too long: " + message, failure);
        }

        return new LlmException("Unexpected LLM error: " + message, failure);
    }

    /**
     * Map an HTTP response status/body to a semantic exception.
     * Kept separate so it can be unit tested without a provider connection.
     */
    static LlmException classifyHttpError(
            int status,
            String body,
            String retryAfter,
            Throwable cause
    ) {
        String safeBody = truncate(body == null ? "" : body);
        String lower = safeBody.toLowerCase(Locale.ROOT);

        if (status == 413 || isContextTooLong(lower)) {
            return new ContextTooLongException(
                    "Context too long: " + safeBody,
                    cause
            );
        }

        return switch (status) {
            case 401, 403 -> new AuthenticationException(
                    "Authentication failed (HTTP " + status + "): " + safeBody,
                    cause
            );
            case 429 -> new RateLimitException(
                    "Rate limited (HTTP 429): " + safeBody,
                    retryAfter,
                    cause
            );
            default -> new LlmException(
                    "API error (HTTP " + status + "): " + safeBody,
                    cause
            );
        };
    }

    static LlmException classifyHttpError(int status, String body) {
        return classifyHttpError(status, body, extractRetryAfter(body), null);
    }

    private static boolean isContextTooLong(String lower) {
        return lower.contains("prompt is too long")
                || lower.contains("context length")
                || lower.contains("context window")
                || lower.contains("maximum context")
                || lower.contains("input is too long")
                || lower.contains("too many tokens")
                || lower.contains("max tokens");
    }

    private static int extractStatus(String message) {
        Matcher matcher = STATUS_PATTERN.matcher(message == null ? "" : message);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    static String extractRetryAfter(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        Matcher matcher = RETRY_AFTER_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        return truncate(message);
    }

    private static String truncate(String value) {
        return value.length() <= 500
                ? value
                : value.substring(0, 500) + "...";
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static <T extends Throwable> boolean hasCause(
            Throwable failure,
            Class<T> type
    ) {
        return findCause(failure, type) != null;
    }

    private static <T extends Throwable> T findCause(
            Throwable failure,
            Class<T> type
    ) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
