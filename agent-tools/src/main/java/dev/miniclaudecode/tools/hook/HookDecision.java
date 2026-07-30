package dev.miniclaudecode.tools.hook;

import java.util.Objects;

/** A deterministic hook outcome. Denial stops the action before the model can execute it. */
public record HookDecision(Kind kind, String reason) {
  public HookDecision {
    Objects.requireNonNull(kind, "kind must not be null");
    reason = Objects.requireNonNullElse(reason, "").strip();
    if (kind != Kind.ALLOW && reason.isBlank()) {
      throw new IllegalArgumentException("non-allow hook decisions require a reason");
    }
  }

  public static HookDecision allow() {
    return new HookDecision(Kind.ALLOW, "");
  }

  public static HookDecision deny(String reason) {
    return new HookDecision(Kind.DENY, reason);
  }

  public enum Kind {
    ALLOW,
    DENY
  }
}
