package dev.miniclaudecode.tools.process;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandPolicyTest {
  @Test
  void denyWinsOverABroadAllowPrefix() {
    CommandPolicy policy =
        new CommandPolicy(java.util.List.of("git"), java.util.List.of("git reset --hard"), false);

    Assertions.assertThat(policy.evaluate("git reset --hard HEAD"))
        .isEqualTo(CommandPolicy.Decision.DENY);
  }

  @Test
  void strictAllowlistRejectsUnknownCommands() {
    CommandPolicy policy = new CommandPolicy(java.util.List.of("rg"), java.util.List.of(), true);

    Assertions.assertThat(policy.evaluate("rg TODO")).isEqualTo(CommandPolicy.Decision.ALLOW);
    Assertions.assertThat(policy.evaluate("mvn test")).isEqualTo(CommandPolicy.Decision.DENY);
  }

  @Test
  void singleWordDenyEntryMatchesACommandNotAnUnrelatedSubstring() {
    CommandPolicy policy =
        new CommandPolicy(java.util.List.of(), java.util.List.of("format"), false);

    Assertions.assertThat(policy.evaluate("format C:")).isEqualTo(CommandPolicy.Decision.DENY);
    Assertions.assertThat(policy.evaluate("mvn formatter:format"))
        .isEqualTo(CommandPolicy.Decision.REVIEW);
  }
}
