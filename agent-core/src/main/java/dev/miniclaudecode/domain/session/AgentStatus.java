package dev.miniclaudecode.domain.session;

import java.util.EnumSet;
import java.util.Set;

public enum AgentStatus {
  RUNNING,
  WAITING_APPROVAL,
  COMPLETED,
  FAILED,
  CANCELLED;

  public boolean isTerminal() {
    return this == COMPLETED || this == FAILED || this == CANCELLED;
  }

  public boolean canTransitionTo(AgentStatus next) {
    if (next == null) {
      return false;
    } else {
      return this == next ? true : this.allowedTargets().contains(next);
    }
  }

  private Set<AgentStatus> allowedTargets() {
    return switch (this) {
      case RUNNING -> EnumSet.of(WAITING_APPROVAL, COMPLETED, FAILED, CANCELLED);
      case WAITING_APPROVAL -> EnumSet.of(RUNNING, FAILED, CANCELLED);
      case COMPLETED, FAILED, CANCELLED -> EnumSet.noneOf(AgentStatus.class);
    };
  }
}
