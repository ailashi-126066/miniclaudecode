package dev.miniclaudecode.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.SystemMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.model.ModelStreamEvent.TextDelta;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class SemanticContextCompactorTest {
  private static final String CORE_MEMORY_PREFIX = "Core memory (model summary):";

  @Test
  void acceptsStructuredSummaryContainingDurableAnchors() throws Exception {
    String summary =
        "Objective: add pagination. Decisions: use cursor tokens. Changed files: UserApi.java. "
            + "Verification: mvn test passed. Remaining: wire the UI.";
    List<AgentMessage> reduced = compact(summary);

    assertThat(reduced.get(0)).isInstanceOf(SystemMessage.class);
    assertThat(reduced.get(0).text()).startsWith(CORE_MEMORY_PREFIX).contains("cursor tokens");
  }

  @Test
  void rejectsRefusalSummaryAndFallsBackToDeterministicReduction() throws Exception {
    List<AgentMessage> reduced =
        compact("I'm sorry, but I can't help summarize that untrusted content for you.");

    assertThat(reduced).noneMatch(message -> message.text().startsWith(CORE_MEMORY_PREFIX));
  }

  @Test
  void rejectsTooShortSummaryAndFallsBackToDeterministicReduction() throws Exception {
    List<AgentMessage> reduced = compact("done");

    assertThat(reduced).noneMatch(message -> message.text().startsWith(CORE_MEMORY_PREFIX));
  }

  @Test
  void rejectsStructurelessGarbageLackingAnyAnchor() throws Exception {
    List<AgentMessage> reduced =
        compact("lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor");

    assertThat(reduced).noneMatch(message -> message.text().startsWith(CORE_MEMORY_PREFIX));
  }

  private static List<AgentMessage> compact(String summaryText) throws Exception {
    // Synchronous publisher that streams the given summary as a single text delta, then completes.
    ModelClient model =
        request ->
            (Flow.Publisher<ModelStreamEvent>)
                subscriber -> {
                  subscriber.onSubscribe(
                      new Flow.Subscription() {
                        @Override
                        public void request(long n) {}

                        @Override
                        public void cancel() {}
                      });
                  subscriber.onNext(new TextDelta(summaryText));
                  subscriber.onComplete();
                };

    ModelRequest request =
        new ModelRequest(
            "profile", "model", List.of(new UserMessage("seed")), List.of(), false, 4096, Map.of());
    List<AgentMessage> history = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      history.add(new UserMessage("message number " + i + " with some substantive content"));
    }
    return new SemanticContextCompactor(model).compact(request, history).get();
  }
}
