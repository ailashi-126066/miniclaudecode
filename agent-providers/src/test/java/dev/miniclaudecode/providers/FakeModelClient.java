package dev.miniclaudecode.providers;

import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

public final class FakeModelClient implements ModelClient {
  private final Queue<List<ModelStreamEvent>> scripts;
  private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();
  private final AtomicInteger cancellationCount = new AtomicInteger();

  private FakeModelClient(Collection<List<ModelStreamEvent>> scripts) {
    this.scripts = new ConcurrentLinkedQueue<>();
    scripts.forEach(script -> this.scripts.add(List.copyOf(script)));
  }

  public static FakeModelClient respondingWith(List<ModelStreamEvent> events) {
    return new FakeModelClient(List.of(events));
  }

  public static FakeModelClient scripted(List<List<ModelStreamEvent>> scripts) {
    return new FakeModelClient(scripts);
  }

  @Override
  public Flow.Publisher<ModelStreamEvent> stream(ModelRequest request) {
    requests.add(Objects.requireNonNull(request, "request must not be null"));
    List<ModelStreamEvent> script = scripts.poll();
    if (script == null) {
      script =
          List.of(
              new ModelStreamEvent.Failed(
                  "fake_script_exhausted", "no scripted model response remains", false));
    }
    List<ModelStreamEvent> selectedScript = script;
    return subscriber -> {
      Objects.requireNonNull(subscriber, "subscriber must not be null");
      subscriber.onSubscribe(
          new ScriptedSubscription(subscriber, selectedScript, cancellationCount::incrementAndGet));
    };
  }

  public List<ModelRequest> requests() {
    return List.copyOf(requests);
  }

  public int cancellationCount() {
    return cancellationCount.get();
  }

  private static final class ScriptedSubscription implements Flow.Subscription {
    private final Flow.Subscriber<? super ModelStreamEvent> subscriber;
    private final List<ModelStreamEvent> script;
    private final Runnable onCancellation;
    private int index;
    private long demand;
    private boolean draining;
    private boolean cancelled;
    private boolean terminated;

    private ScriptedSubscription(
        Flow.Subscriber<? super ModelStreamEvent> subscriber,
        List<ModelStreamEvent> script,
        Runnable onCancellation) {
      this.subscriber = subscriber;
      this.script = script;
      this.onCancellation = onCancellation;
    }

    @Override
    public synchronized void request(long requested) {
      if (cancelled || terminated) return;
      if (requested <= 0) {
        terminated = true;
        subscriber.onError(new IllegalArgumentException("demand must be greater than zero"));
        return;
      }
      demand = addWithSaturation(demand, requested);
      drain();
    }

    @Override
    public synchronized void cancel() {
      if (!cancelled && !terminated) {
        cancelled = true;
        onCancellation.run();
      }
    }

    private void drain() {
      if (draining) return;
      draining = true;
      try {
        while (!cancelled && !terminated && demand > 0 && index < script.size()) {
          ModelStreamEvent event = script.get(index++);
          demand--;
          subscriber.onNext(event);
        }
        if (!cancelled && !terminated && index == script.size()) {
          terminated = true;
          subscriber.onComplete();
        }
      } finally {
        draining = false;
      }
    }

    private static long addWithSaturation(long left, long right) {
      long result = left + right;
      return result < 0 ? Long.MAX_VALUE : result;
    }
  }
}
