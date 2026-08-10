package dev.miniclaudecode.domain.tool;

/** Describes the externally observable effect of invoking a tool. */
public enum ToolEffect {
  READ_ONLY_LOCAL,
  READ_ONLY_EXTERNAL,
  USER_INTERACTION,
  MUTATION,
  PROCESS,
  EXTERNAL_EFFECT;

  public boolean requiresPlan() {
    return this == MUTATION || this == PROCESS || this == EXTERNAL_EFFECT;
  }
}
