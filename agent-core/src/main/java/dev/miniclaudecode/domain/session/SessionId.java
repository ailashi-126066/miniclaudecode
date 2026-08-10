package dev.miniclaudecode.domain.session;

import java.util.UUID;

public record SessionId(String value) {
  public SessionId(String value) {
    if (value != null && !value.isBlank()) {
      value = value.trim();
      this.value = value;
    } else {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
  }

  public static SessionId of(String value) {
    return new SessionId(value);
  }

  public static SessionId random() {
    return new SessionId(UUID.randomUUID().toString());
  }

  @Override
  public String toString() {
    return this.value;
  }
}
