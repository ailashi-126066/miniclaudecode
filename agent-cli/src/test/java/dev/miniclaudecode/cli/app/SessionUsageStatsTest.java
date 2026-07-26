package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.cli.app.SessionUsageStats.Snapshot;
import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.model.ModelStreamEvent.UsageReported;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class SessionUsageStatsTest {
  @Test
  void aggregatesProviderUsageAndCalculatesPromptCacheHitRate() {
    SessionUsageStats usage = new SessionUsageStats();
    usage.record(new UsageReported(100L, 20L, 60L, 10L));
    usage.record(new UsageReported(50L, 8L, 30L, 0L));
    Assertions.assertThat(usage.snapshot()).isEqualTo(new Snapshot(2L, 150L, 28L, 90L, 10L));
    Assertions.assertThat(usage.snapshot().promptCacheHitRate()).isEqualTo(0.6);
    Assertions.assertThat(usage.summary())
        .contains(
            new CharSequence[] {
              "Model requests: 2",
              "Input tokens: 150",
              "Cache read tokens: 90",
              "Cache write tokens: 10",
              "Prompt cache hit: 60.0%"
            });
  }

  @Test
  void restoresUsageFromAuditedSessionEventsIncludingLegacyEvents() {
    SessionUsageStats usage = new SessionUsageStats();
    SessionId session = new SessionId("session-1");
    TurnId turn = new TurnId(1L);
    usage.restore(
        List.of(
            new AgentEvent(
                UUID.randomUUID(),
                1,
                session,
                turn,
                Instant.parse("2026-07-21T00:00:00Z"),
                AgentEventType.MODEL_USAGE,
                Map.of(
                    "inputTokens",
                    80,
                    "outputTokens",
                    9,
                    "cacheReadTokens",
                    40,
                    "cacheWriteTokens",
                    5)),
            new AgentEvent(
                UUID.randomUUID(),
                1,
                session,
                turn,
                Instant.parse("2026-07-21T00:00:01Z"),
                AgentEventType.MODEL_USAGE,
                Map.of("inputTokens", 20, "outputTokens", 3))));
    Assertions.assertThat(usage.snapshot()).isEqualTo(new Snapshot(2L, 100L, 12L, 40L, 5L));
    Assertions.assertThat(usage.summary()).contains(new CharSequence[] {"Prompt cache hit: 40.0%"});
  }
}
