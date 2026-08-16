package dev.miniclaudecode.runtime;

import java.util.concurrent.CompletableFuture;

/** Asynchronous route decision without a graph-runtime dependency. */
@FunctionalInterface
public interface StateRoute<S> {
  CompletableFuture<String> apply(S state);
}
