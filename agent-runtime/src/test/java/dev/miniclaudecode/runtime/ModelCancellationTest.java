package dev.miniclaudecode.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.runtime.node.CallModelNode;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ModelCancellationTest {

  @Test
  void cancellationCancelsTheProviderSubscriptionAndCompletesTheNode() {
    AtomicBoolean subscriptionCancelled = new AtomicBoolean();
    ModelClient waitingModel =
        ignored ->
            subscriber ->
                subscriber.onSubscribe(
                    new Flow.Subscription() {
                      @Override
                      public void request(long value) {}

                      @Override
                      public void cancel() {
                        subscriptionCancelled.set(true);
                      }
                    });
    CancellationToken token = new CancellationToken();
    List<AgentMessage> messages = List.of(new AgentMessage.UserMessage("Wait"));
    ModelRequest request =
        new ModelRequest("test", "fake", messages, List.of(), false, 100, Map.of());
    MiniClaudeState state =
        new MiniClaudeState(
            Map.of(MiniClaudeState.REQUEST, request, MiniClaudeState.MESSAGES, messages));

    var future = new CallModelNode(waitingModel, new TurnLimits(4, 4), token).apply(state);
    token.cancel();

    assertThat(subscriptionCancelled).isTrue();
    assertThat(future.join().get(MiniClaudeState.STATUS)).isEqualTo(AgentStatus.CANCELLED);
  }
}
