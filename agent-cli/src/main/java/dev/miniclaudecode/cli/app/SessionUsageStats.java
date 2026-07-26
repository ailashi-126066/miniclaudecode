package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.model.ModelStreamEvent.UsageReported;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class SessionUsageStats {
  private long requests;
  private long inputTokens;
  private long outputTokens;
  private long cacheReadTokens;
  private long cacheWriteTokens;

  synchronized void record(UsageReported usage) {
    Objects.requireNonNull(usage, "usage must not be null");
    this.requests = saturatedAdd(this.requests, 1L);
    this.inputTokens = saturatedAdd(this.inputTokens, usage.inputTokens());
    this.outputTokens = saturatedAdd(this.outputTokens, usage.outputTokens());
    this.cacheReadTokens = saturatedAdd(this.cacheReadTokens, usage.cacheReadTokens());
    this.cacheWriteTokens = saturatedAdd(this.cacheWriteTokens, usage.cacheWriteTokens());
  }

  synchronized void restore(List<AgentEvent> events) {
    Objects.requireNonNull(events, "events must not be null");
    this.reset();
    events.stream()
        .filter(event -> event.type() == AgentEventType.MODEL_USAGE)
        .<Map<String, Object>>map(AgentEvent::payload)
        .map(SessionUsageStats::fromPayload)
        .forEach(this::record);
  }

  synchronized SessionUsageStats.Snapshot snapshot() {
    return new SessionUsageStats.Snapshot(
        this.requests,
        this.inputTokens,
        this.outputTokens,
        this.cacheReadTokens,
        this.cacheWriteTokens);
  }

  synchronized String summary() {
    SessionUsageStats.Snapshot value = this.snapshot();
    String hitRate =
        value.inputTokens() == 0L
            ? "n/a"
            : String.format(Locale.ROOT, "%.1f%%", value.promptCacheHitRate() * 100.0);
    return String.join(
        System.lineSeparator(),
        "Model requests: " + value.requests(),
        "Input tokens: " + value.inputTokens(),
        "Output tokens: " + value.outputTokens(),
        "Cache read tokens: " + value.cacheReadTokens(),
        "Cache write tokens: " + value.cacheWriteTokens(),
        "Prompt cache hit: " + hitRate);
  }

  private synchronized void reset() {
    this.requests = 0L;
    this.inputTokens = 0L;
    this.outputTokens = 0L;
    this.cacheReadTokens = 0L;
    this.cacheWriteTokens = 0L;
  }

  private static UsageReported fromPayload(Map<String, Object> payload) {
    return new UsageReported(
        number(payload, "inputTokens"),
        number(payload, "outputTokens"),
        number(payload, "cacheReadTokens"),
        number(payload, "cacheWriteTokens"));
  }

  private static long number(Map<String, Object> payload, String field) {
    return payload.get(field) instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
  }

  private static long saturatedAdd(long left, long right) {
    return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
  }

  static record Snapshot(
      long requests,
      long inputTokens,
      long outputTokens,
      long cacheReadTokens,
      long cacheWriteTokens) {
    double promptCacheHitRate() {
      return this.inputTokens == 0L
          ? 0.0
          : (double) this.cacheReadTokens / (double) this.inputTokens;
    }
  }
}
