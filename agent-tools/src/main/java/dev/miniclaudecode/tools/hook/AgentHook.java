package dev.miniclaudecode.tools.hook;

/** A deterministic lifecycle rule. It deliberately cannot grant permissions or execute tools. */
@FunctionalInterface
public interface AgentHook {
  HookDecision evaluate(HookContext context);
}
