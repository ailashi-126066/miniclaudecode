package dev.miniclaudecode.persistence.permission;

import dev.miniclaudecode.domain.approval.PermissionRule;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionRuleStoreTest {
  @TempDir Path tempDirectory;

  @Test
  void persistsExactScopedRulesAndDeduplicatesMatches() {
    Path file = this.tempDirectory.resolve("permissions.json");
    JsonPermissionRuleStore store = new JsonPermissionRuleStore(file);
    PermissionRule first =
        new PermissionRule(
            UUID.randomUUID(),
            "C:/workspace",
            "workspace:edit",
            "src/App.java",
            Instant.parse("2026-07-21T00:00:00Z"));
    PermissionRule duplicate =
        new PermissionRule(
            UUID.randomUUID(),
            "C:/workspace",
            "workspace:edit",
            "src/App.java",
            Instant.parse("2026-07-21T01:00:00Z"));
    store.save(first);
    store.save(duplicate);
    Assertions.assertThat(new JsonPermissionRuleStore(file).list())
        .containsExactly(new PermissionRule[] {first});
  }
}
