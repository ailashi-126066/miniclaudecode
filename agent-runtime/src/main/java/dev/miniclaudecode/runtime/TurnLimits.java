package dev.miniclaudecode.runtime;

public record TurnLimits(int maxModelSteps, int maxToolSteps) {

  public TurnLimits {
    if (maxModelSteps < 1) {
      throw new IllegalArgumentException("maxModelSteps must be greater than zero");
    }
    if (maxToolSteps < 1) {
      throw new IllegalArgumentException("maxToolSteps must be greater than zero");
    }
  }
}
