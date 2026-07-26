package dev.miniclaudecode.domain.runtime;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken {
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();

  public boolean cancel() {
    if (!this.cancelled.compareAndSet(false, true)) {
      return false;
    } else {
      this.callbacks.forEach(CancellationToken::runSafely);
      this.callbacks.clear();
      return true;
    }
  }

  public boolean isCancellationRequested() {
    return this.cancelled.get();
  }

  public CancellationToken.Registration onCancel(Runnable callback) {
    Objects.requireNonNull(callback, "callback must not be null");
    if (this.cancelled.get()) {
      runSafely(callback);
      return () -> {};
    } else {
      this.callbacks.add(callback);
      if (this.cancelled.get() && this.callbacks.remove(callback)) {
        runSafely(callback);
      }

      return () -> this.callbacks.remove(callback);
    }
  }

  private static void runSafely(Runnable callback) {
    try {
      callback.run();
    } catch (RuntimeException var2) {
    }
  }

  @FunctionalInterface
  public interface Registration extends AutoCloseable {
    @Override
    void close();
  }
}
