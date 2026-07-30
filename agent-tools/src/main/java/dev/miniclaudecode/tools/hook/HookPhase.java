package dev.miniclaudecode.tools.hook;

/** Stable lifecycle points at which deterministic policy can run outside the model prompt. */
public enum HookPhase {
  BEFORE_TOOL,
  AFTER_TOOL,
  BEFORE_FINISH
}
