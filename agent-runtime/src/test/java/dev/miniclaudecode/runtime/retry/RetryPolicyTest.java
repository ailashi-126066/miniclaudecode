package dev.miniclaudecode.runtime.retry;

import java.time.Duration;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {
  private final RetryPolicy policy =
      new RetryPolicy(3, Duration.ofMillis(100L), Duration.ofSeconds(1L), () -> 0.0);

  @Test
  void retriesTransientFailuresWithBoundedExponentialBackoff() {
    Assertions.assertThat(this.policy.decide("http_503", true, 0, Optional.empty()).delay())
        .isEqualTo(Duration.ofMillis(100L));
    Assertions.assertThat(this.policy.decide("http_503", true, 1, Optional.empty()).delay())
        .isEqualTo(Duration.ofMillis(200L));
    Assertions.assertThat(this.policy.decide("http_503", true, 3, Optional.empty()).retry())
        .isFalse();
  }

  @Test
  void neverRetriesAuthenticationOrConfigurationFailures() {
    Assertions.assertThat(
            this.policy.decide("authentication_error", true, 0, Optional.empty()).retry())
        .isFalse();
    Assertions.assertThat(this.policy.decide("invalid_config", true, 0, Optional.empty()).retry())
        .isFalse();
  }

  @Test
  void honorsRetryAfterForRateLimits() {
    Assertions.assertThat(
            this.policy.decide("http_429", true, 0, Optional.of(Duration.ofSeconds(2L))).delay())
        .isEqualTo(Duration.ofSeconds(2L));
  }

  @Test
  void honorsThePerRequestMaximumRetryOverride() {
    Assertions.assertThat(this.policy.decide("http_503", true, 0, Optional.empty(), 0).retry())
        .isFalse();
    Assertions.assertThat(this.policy.decide("http_503", true, 4, Optional.empty(), 5).retry())
        .isTrue();
    Assertions.assertThat(this.policy.decide("http_503", true, 5, Optional.empty(), 5).retry())
        .isFalse();
  }
}
