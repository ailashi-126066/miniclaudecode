package dev.miniclaudecode.domain.approval;

public enum RiskLevel {
  LOW,
  MEDIUM,
  HIGH,
  CRITICAL;

  public boolean requiresApproval() {
    return this != LOW;
  }
}
