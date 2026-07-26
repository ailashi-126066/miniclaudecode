package dev.miniclaudecode.runtime.retry;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.DoubleSupplier;

public final class RetryPolicy {
  private final int maximumRetries;
  private final Duration baseDelay;
  private final Duration maximumDelay;
  private final DoubleSupplier jitter;

  public RetryPolicy() {
    this(3, Duration.ofMillis(100L), Duration.ofSeconds(2L), Math::random);
  }

  public RetryPolicy(
      int maximumRetries, Duration baseDelay, Duration maximumDelay, DoubleSupplier jitter) {
    if (maximumRetries < 0) {
      throw new IllegalArgumentException("maximumRetries must not be negative");
    } else {
      this.maximumRetries = maximumRetries;
      this.baseDelay = requirePositive(baseDelay, "baseDelay");
      this.maximumDelay = requirePositive(maximumDelay, "maximumDelay");
      this.jitter = Objects.requireNonNull(jitter, "jitter must not be null");
    }
  }

  public RetryPolicy.Decision decide(
      String failureType,
      boolean providerRetryable,
      int retriesAlreadyAttempted,
      Optional<Duration> retryAfter) {
    Objects.requireNonNull(retryAfter, "retryAfter must not be null");
    String normalized = failureType == null ? "" : failureType.toLowerCase(Locale.ROOT);
    boolean forbidden =
        normalized.contains("auth")
            || normalized.contains("api_key")
            || normalized.contains("config")
            || normalized.contains("invalid_request");
    boolean transientFailure =
        providerRetryable
            || normalized.contains("429")
            || normalized.contains("502")
            || normalized.contains("503")
            || normalized.contains("rate_limit")
            || normalized.contains("timeout");
    if (!forbidden && transientFailure && retriesAlreadyAttempted < this.maximumRetries) {
      Duration exponential =
          this.baseDelay.multipliedBy(1L << Math.min(retriesAlreadyAttempted, 20));
      Duration bounded =
          exponential.compareTo(this.maximumDelay) > 0 ? this.maximumDelay : exponential;
      Duration selected = retryAfter.filter(delay -> !delay.isNegative()).orElse(bounded);
      double jitterValue = Math.max(0.0, Math.min(1.0, this.jitter.getAsDouble()));
      long jitterMillis = Math.round((double) selected.toMillis() * 0.25 * jitterValue);
      return new RetryPolicy.Decision(true, selected.plusMillis(jitterMillis));
    } else {
      return new RetryPolicy.Decision(false, Duration.ZERO);
    }
  }

  private static Duration requirePositive(Duration duration, String field) {
    Objects.requireNonNull(duration, field + " must not be null");
    if (!duration.isZero() && !duration.isNegative()) {
      return duration;
    } else {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }

  public static record Decision(boolean retry, Duration delay) {}
}
