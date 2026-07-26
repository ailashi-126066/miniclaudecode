package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.cli.StreamingRenderer.RenderEvent;
import dev.miniclaudecode.cli.StreamingRenderer.RenderEvent.Text;
import dev.miniclaudecode.cli.StreamingRenderer.RenderEvent.Thinking;
import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.model.ModelStreamEvent.Failed;
import dev.miniclaudecode.domain.model.ModelStreamEvent.TextDelta;
import dev.miniclaudecode.domain.model.ModelStreamEvent.ThinkingDelta;
import dev.miniclaudecode.domain.model.ModelStreamEvent.UsageReported;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.function.Consumer;

final class AuditedModelClient implements ModelClient {
  private final ModelClient delegate;
  private final SessionId sessionId;
  private final TurnId turnId;
  private final EventSink audit;
  private final Consumer<RenderEvent> renderer;
  private final Clock clock;
  private final Consumer<UsageReported> usageObserver;

  AuditedModelClient(
      ModelClient delegate,
      SessionId sessionId,
      TurnId turnId,
      EventSink audit,
      Consumer<RenderEvent> renderer,
      Clock clock) {
    this(delegate, sessionId, turnId, audit, renderer, clock, ignored -> {});
  }

  AuditedModelClient(
      ModelClient delegate,
      SessionId sessionId,
      TurnId turnId,
      EventSink audit,
      Consumer<RenderEvent> renderer,
      Clock clock,
      Consumer<UsageReported> usageObserver) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
    this.turnId = Objects.requireNonNull(turnId, "turnId must not be null");
    this.audit = Objects.requireNonNull(audit, "audit must not be null");
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.usageObserver = Objects.requireNonNull(usageObserver, "usageObserver must not be null");
  }

  public Publisher<ModelStreamEvent> stream(ModelRequest request) {
    return subscriber ->
        this.delegate.stream(request)
            .subscribe(new AuditedModelClient.Observer(subscriber, request.thinkingEnabled()));
  }

  private void emit(AgentEventType type, Map<String, Object> payload) {
    this.audit.emit(AgentEvent.create(this.sessionId, this.turnId, type, payload, this.clock));
  }

  /**
   * Audits the stream while coalescing per-token deltas.
   *
   * <p>Every {@code TextDelta} used to become its own audit event, and every audit event costs an
   * open + lock + fsync in the JSONL store — 2000 fsyncs for a 2000-token answer. Deltas are now
   * buffered and flushed as one merged event when the buffer reaches {@link #FLUSH_BYTES}, when
   * {@link #FLUSH_INTERVAL_MILLIS} has passed since the first buffered delta, when the delta kind
   * switches (text vs thinking), and always before any non-delta audit event, on completion, and on
   * error — so the audit log keeps the exact event order, just with fewer, larger entries.
   *
   * <p>Only the audit path is batched: the renderer still receives every delta immediately, so the
   * visible stream is untouched.
   */
  private final class Observer implements Subscriber<ModelStreamEvent> {
    private static final int FLUSH_BYTES = 4096;
    private static final long FLUSH_INTERVAL_MILLIS = 250L;

    private final Subscriber<? super ModelStreamEvent> downstream;
    private final boolean thinkingEnabled;
    private final StringBuilder buffered = new StringBuilder();
    private AgentEventType bufferedType;
    private long bufferedSinceMillis;

    private Observer(Subscriber<? super ModelStreamEvent> downstream, boolean thinkingEnabled) {
      this.downstream = downstream;
      this.thinkingEnabled = thinkingEnabled;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      this.downstream.onSubscribe(subscription);
    }

    public void onNext(ModelStreamEvent item) {
      Objects.requireNonNull(item);
      switch (item) {
        case ThinkingDelta thinking:
          this.buffer(AgentEventType.PROVIDER_THINKING, thinking.text());
          if (this.thinkingEnabled) {
            AuditedModelClient.this.renderer.accept(new Thinking(thinking.text()));
          }
          break;
        case TextDelta text:
          this.buffer(AgentEventType.ASSISTANT_MESSAGE, text.text());
          AuditedModelClient.this.renderer.accept(new Text(text.text()));
          break;
        case UsageReported usage:
          this.flush();
          AuditedModelClient.this.usageObserver.accept(usage);
          AuditedModelClient.this.emit(
              AgentEventType.MODEL_USAGE,
              Map.of(
                  "inputTokens",
                  usage.inputTokens(),
                  "outputTokens",
                  usage.outputTokens(),
                  "cacheReadTokens",
                  usage.cacheReadTokens(),
                  "cacheWriteTokens",
                  usage.cacheWriteTokens()));
          break;
        case Failed failed:
          this.flush();
          AuditedModelClient.this.emit(
              AgentEventType.ERROR,
              Map.of("type", failed.errorType(), "message", failed.message()));
          break;
        default:
          this.flush();
          break;
      }

      this.downstream.onNext(item);
    }

    @Override
    public void onError(Throwable throwable) {
      this.flush();
      this.downstream.onError(throwable);
    }

    @Override
    public void onComplete() {
      this.flush();
      this.downstream.onComplete();
    }

    /**
     * A single buffer with a kind marker: switching kind flushes first, so merged events can never
     * reorder thinking against text.
     */
    private void buffer(AgentEventType type, String delta) {
      if (this.bufferedType != null && this.bufferedType != type) {
        this.flush();
      }
      if (this.bufferedType == null) {
        this.bufferedType = type;
        this.bufferedSinceMillis = AuditedModelClient.this.clock.millis();
      }
      this.buffered.append(delta);
      long waitedMillis = AuditedModelClient.this.clock.millis() - this.bufferedSinceMillis;
      if (this.buffered.length() >= FLUSH_BYTES || waitedMillis >= FLUSH_INTERVAL_MILLIS) {
        this.flush();
      }
    }

    private void flush() {
      if (this.bufferedType == null || this.buffered.isEmpty()) {
        this.bufferedType = null;
        this.buffered.setLength(0);
        return;
      }
      String merged = this.buffered.toString();
      AgentEventType type = this.bufferedType;
      this.buffered.setLength(0);
      this.bufferedType = null;
      AuditedModelClient.this.emit(
          type,
          type == AgentEventType.PROVIDER_THINKING
              ? Map.of("text", merged)
              : Map.of("delta", merged));
    }
  }
}
