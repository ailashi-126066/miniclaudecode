package dev.miniclaudecode.persistence.memory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.session.AgentStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReflexionExtractorTest {
  private final ReflexionExtractor extractor =
      new ReflexionExtractor(Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC));

  @Test
  void treatsPassingVerificationCommandAsVerifiedCompletion() {
    List<AgentMessage> messages =
        List.of(
            new UserMessage("build the feature"),
            new ToolMessage(
                "call-1", "shell:run", "[verification-command-succeeded] BUILD SUCCESS", false));

    Optional<AceBullet> bullet = this.extractor.extract(messages, AgentStatus.COMPLETED, "");

    assertThat(bullet).isPresent();
    assertThat(bullet.get().evidence()).containsExactly("verification command succeeded");
  }

  @Test
  void doesNotTreatFailedVerificationCommandAsVerified() {
    // Exit code != 0 / timeout means RunCommandTool never attaches the success prefix, so the
    // turn has no verified change and no lesson is extracted for an otherwise clean completion.
    List<AgentMessage> messages =
        List.of(
            new UserMessage("build the feature"),
            new ToolMessage("call-1", "shell:run", "BUILD FAILURE: exit code 1", false));

    Optional<AceBullet> bullet = this.extractor.extract(messages, AgentStatus.COMPLETED, "");

    assertThat(bullet).isEmpty();
  }
}
