package dev.miniclaudecode.tools.hook;

import java.util.List;
import java.util.Objects;

/** Ordered hook registry; the first denial wins and is auditable by the caller. */
public final class HookRegistry {
  public static final HookRegistry NONE = new HookRegistry(List.of());
  private final List<AgentHook> hooks;

  public HookRegistry(List<? extends AgentHook> hooks) {
    this.hooks = List.copyOf(Objects.requireNonNull(hooks, "hooks must not be null"));
  }

  public HookDecision evaluate(HookContext context) {
    for (AgentHook hook : this.hooks) {
      HookDecision decision = Objects.requireNonNull(hook.evaluate(context), "hook returned null");
      if (decision.kind() == HookDecision.Kind.DENY) {
        return decision;
      }
    }
    return HookDecision.allow();
  }
}
