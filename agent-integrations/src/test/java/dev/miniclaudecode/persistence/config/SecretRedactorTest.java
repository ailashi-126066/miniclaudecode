package dev.miniclaudecode.persistence.config;

import java.util.Set;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class SecretRedactorTest {
  private final SecretRedactor redactor = new SecretRedactor();

  @Test
  void redactsHeadersConfigValuesAndKnownSecrets() {
    String input = "Authorization: Bearer sk-header api-key=sk-config payload=sk-known-secret";
    String redacted = this.redactor.redact(input, Set.of("sk-known-secret"));
    ((AbstractStringAssert)
            Assertions.assertThat(redacted)
                .doesNotContain(new CharSequence[] {"sk-header", "sk-config", "sk-known-secret"}))
        .contains(new CharSequence[] {"Authorization: Bearer ***", "api-key=***"});
  }

  @Test
  void redactsSensitiveUrlQueryParametersWithoutDamagingOtherParameters() {
    String input = "https://example.test/v1?model=x&api_key=sk-query&trace=true";
    Assertions.assertThat(this.redactor.redact(input, Set.of()))
        .isEqualTo("https://example.test/v1?model=x&api_key=***&trace=true");
  }

  @Test
  void handlesNullAndEmptyInput() {
    Assertions.assertThat(this.redactor.redact(null, Set.of())).isNull();
    Assertions.assertThat(this.redactor.redact("", Set.of())).isEmpty();
  }
}
