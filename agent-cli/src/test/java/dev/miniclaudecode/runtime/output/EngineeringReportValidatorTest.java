package dev.miniclaudecode.runtime.output;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.message.AgentMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineeringReportValidatorTest {
  private final EngineeringReportValidator validator = new EngineeringReportValidator();

  @Test
  void permitsNormalAnswersWhenNoWorkspaceMutationOccurred() {
    assertThat(
            validator
                .evaluate(List.of(new AgentMessage.UserMessage("Explain this")), "Answer")
                .valid())
        .isTrue();
  }

  @Test
  void requiresAuditableSectionsAfterAWorkspaceMutation() {
    List<AgentMessage> messages =
        List.of(new AgentMessage.ToolMessage("edit", "workspace:edit", "changed", false));

    assertThat(validator.evaluate(messages, "Done").valid()).isFalse();
    assertThat(
            validator
                .evaluate(
                    messages,
                    "Changed Files\n- src/App.java\n\nVerification\n- mvn test: passed\n\n"
                        + "Unverified Scope\n- full integration suite not run")
                .valid())
        .isTrue();
  }
}
