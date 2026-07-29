package dev.miniclaudecode.providers;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

record ProviderErrorDetails(String type, String message, boolean retryable) {

  /**
   * Classifies a provider failure into a retry decision.
   *
   * <p>Message text alone is not enough. Anthropic's {@code overloaded_error} carries no status
   * code, so a pure substring scan marked the single most retryable failure a provider emits as
   * permanent; conversely a schema complaint naming the {@code timeoutSeconds} tool property was
   * read as a timeout and retried three times. This walks the cause chain for typed transport
   * exceptions first, then falls back to word-boundary matching so a field name embedded in a
   * larger identifier can no longer trip a keyword.
   */
  static ProviderErrorDetails from(Throwable error, Optional<String> secret) {
    String raw = Optional.ofNullable(error.getMessage()).orElse(error.getClass().getSimpleName());
    String lower = raw.toLowerCase(Locale.ROOT);

    boolean typedTimeout = hasCause(error, java.net.http.HttpTimeoutException.class);
    boolean typedTransport =
        typedTimeout
            || hasCause(error, java.net.ConnectException.class)
            || hasCause(error, java.net.SocketTimeoutException.class)
            || hasCause(error, java.net.UnknownHostException.class)
            || hasCause(error, javax.net.ssl.SSLHandshakeException.class)
            || hasCause(error, java.io.EOFException.class);

    // Non-retryable first: retrying a bad key or a malformed request only burns quota and hides
    // the real cause behind three backoff delays.
    boolean forbidden =
        matchesWord(lower, "401")
            || matchesWord(lower, "403")
            || lower.contains("unauthorized")
            || lower.contains("authentication")
            || lower.contains("invalid api key")
            || lower.contains("invalid_api_key")
            || lower.contains("permission_error")
            || lower.contains("invalid_request");

    boolean rateLimited =
        !forbidden
            && (matchesWord(lower, "429")
                || lower.contains("rate limit")
                || lower.contains("rate_limit"));
    boolean timeout =
        !forbidden
            && (typedTimeout || matchesWord(lower, "timeout") || lower.contains("timed out"));
    boolean overloaded =
        !forbidden
            && (lower.contains("overloaded")
                || lower.contains("capacity")
                || lower.contains("service unavailable")
                || lower.contains("try again later"));
    boolean server =
        !forbidden
            && (lower.matches("(?s).*\\b5\\d\\d\\b.*")
                || lower.contains("internal server error")
                || lower.contains("bad gateway"));
    boolean transportFailure =
        !forbidden
            && (typedTransport
                || lower.contains("connection reset")
                || lower.contains("unexpected end of stream")
                || lower.contains("premature")
                || lower.contains("goaway")
                || lower.contains("stream closed"));

    String type;
    if (forbidden) {
      type = "invalid_request";
    } else if (rateLimited) {
      type = "rate_limited";
    } else if (timeout) {
      type = "timeout";
    } else if (overloaded || server) {
      type = "503";
    } else if (transportFailure) {
      type = "transport_error";
    } else {
      type = "provider_error";
    }

    String safe =
        secret
            .filter(value -> !value.isEmpty())
            .map(value -> raw.replace(value, "***"))
            .orElse(raw);
    safe = safe.replaceAll("(?i)bearer\\s+[A-Za-z0-9._-]+", "Bearer ***");
    safe = safe.replaceAll("(?i)(sk-|sk-ant-)[A-Za-z0-9._-]{8,}", "$1***");
    if (safe.length() > 500) {
      safe = safe.substring(0, 500);
    }
    boolean retryable =
        !forbidden && (rateLimited || timeout || overloaded || server || transportFailure);
    return new ProviderErrorDetails(type, safe, retryable);
  }

  /** Word-boundary match, so {@code timeoutSeconds} does not look like a timeout. */
  private static boolean matchesWord(String text, String word) {
    return text.matches("(?s).*\\b" + Pattern.quote(word) + "\\b.*");
  }

  private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (type.isInstance(current)) {
        return true;
      }
      if (current.getCause() == current) {
        return false;
      }
    }
    return false;
  }
}
