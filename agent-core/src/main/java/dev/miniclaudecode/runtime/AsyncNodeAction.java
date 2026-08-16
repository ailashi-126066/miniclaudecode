package dev.miniclaudecode.runtime;

/** Compatibility name for an explicit-loop state step; unrelated to any graph runtime. */
@FunctionalInterface
public interface AsyncNodeAction<S> extends StateNode<S> {}
