package dev.miniclaudecode.domain.session;

import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class SessionStateTest {
  @Test
  void allowsOnlyMeaningfulStatusTransitions() {
    Assertions.assertThat(AgentStatus.RUNNING.canTransitionTo(AgentStatus.WAITING_APPROVAL))
        .isTrue();
    Assertions.assertThat(AgentStatus.WAITING_APPROVAL.canTransitionTo(AgentStatus.RUNNING))
        .isTrue();
    Assertions.assertThat(AgentStatus.RUNNING.canTransitionTo(AgentStatus.COMPLETED)).isTrue();
    Assertions.assertThat(AgentStatus.COMPLETED.canTransitionTo(AgentStatus.RUNNING)).isFalse();
    Assertions.assertThat(AgentStatus.FAILED.canTransitionTo(AgentStatus.RUNNING)).isFalse();
    Assertions.assertThat(AgentStatus.CANCELLED.isTerminal()).isTrue();
  }

  @Test
  void validatesSessionAndTurnIdentifiers() {
    Assertions.assertThat(SessionId.of(" session-1 ").value()).isEqualTo("session-1");
    Assertions.assertThat(TurnId.of(1L).value()).isEqualTo(1L);
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(() -> SessionId.of(" "))
                .isInstanceOf(IllegalArgumentException.class))
        .hasMessageContaining("sessionId");
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(() -> TurnId.of(0L))
                .isInstanceOf(IllegalArgumentException.class))
        .hasMessageContaining("turnId");
  }
}
