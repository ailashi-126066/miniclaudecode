package dev.miniclaudecode.runtime.output;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class RagCitationValidatorTest {
  private final RagCitationValidator validator = new RagCitationValidator();
  private final ModelRequest request =
      new ModelRequest(
          "test", "fake", List.of(), List.of(), false, 100, Map.of("requireRagCitations", true));
  private final List<AgentMessage> evidence =
      List.of(
          new AgentMessage.ToolMessage(
              "search-1", "workspace:code_search", "【src/App.java:12-20】 App", false));

  @Test
  void acceptsOnlyCitationsFromTheSearchResult() {
    Assertions.assertThat(
            this.validator
                .evaluate(this.request, this.evidence, "Found it 【src/App.java:12-20】")
                .valid())
        .isTrue();
    Assertions.assertThat(
            this.validator
                .evaluate(this.request, this.evidence, "Found it 【src/App.java:1-2】")
                .valid())
        .isFalse();
    Assertions.assertThat(this.validator.evaluate(this.request, this.evidence, "Found it").valid())
        .isFalse();
  }
}
