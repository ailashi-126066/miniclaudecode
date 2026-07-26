package dev.miniclaudecode.providers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * Recording subscriber for the synchronous provider publishers. Requests unbounded demand on
 * subscription so every queued event is delivered on the subscribing thread before {@code
 * subscribe} returns; with {@code cancelOnSubscribe} it cancels inside {@code onSubscribe} instead,
 * which is how tests prove the provider is never invoked for an already-cancelled consumer.
 */
public final class FlowTestSubscriber<T> implements Flow.Subscriber<T> {

  private final boolean cancelOnSubscribe;
  private final List<T> events = new ArrayList<>();
  private Throwable error;
  private boolean completed;

  public FlowTestSubscriber() {
    this(false);
  }

  public FlowTestSubscriber(boolean cancelOnSubscribe) {
    this.cancelOnSubscribe = cancelOnSubscribe;
  }

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    if (cancelOnSubscribe) {
      subscription.cancel();
      return;
    }
    subscription.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(T item) {
    events.add(item);
  }

  @Override
  public void onError(Throwable throwable) {
    error = throwable;
  }

  @Override
  public void onComplete() {
    completed = true;
  }

  public List<T> events() {
    return List.copyOf(events);
  }

  public Throwable error() {
    return error;
  }

  public boolean completed() {
    return completed;
  }
}
