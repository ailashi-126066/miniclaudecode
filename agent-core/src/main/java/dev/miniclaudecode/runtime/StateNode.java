package dev.miniclaudecode.runtime;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Minimal asynchronous state transformation used by the explicit AgentLoop. */
@FunctionalInterface
public interface StateNode<S> {
  CompletableFuture<Map<String, Object>> apply(S state);
}
