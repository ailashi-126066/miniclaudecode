package dev.miniclaudecode.persistence.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.session.AgentStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LayeredMemoryStoreTest {

  @TempDir Path temporaryDirectory;

  @Test
  void candidateMemoryRequiresExplicitApprovalBeforeRetrieval() {
    MemoryStore bullets = memory("workspace");
    Instant now = Instant.parse("2026-07-30T00:00:00Z");
    AceBullet candidate =
        bullets.propose(
            new AceBullet(
                null,
                "Maven timeout",
                "Inspect the narrow test before retrying.",
                java.util.List.of("test timed out"),
                1,
                now,
                now));

    assertThat(bullets.pending()).containsExactly(candidate);
    assertThat(bullets.search("Maven timeout", 3)).isEmpty();
    assertThat(bullets.approve(candidate.id())).isTrue();
    assertThat(bullets.search("Maven timeout", 3))
        .singleElement()
        .extracting(AceBullet::id)
        .isEqualTo(candidate.id());
  }

  @Test
  void refusesToAutoApproveAConflictingProjectLesson() {
    MemoryStore bullets = memory("workspace");
    AceBullet active =
        new AceBullet(
            null,
            "database migration",
            "Every migration needs an explicit rollback script.",
            List.of("migration policy"),
            1,
            Instant.now(),
            Instant.now());
    AceBullet conflicting =
        new AceBullet(
            null,
            "database migration",
            "Rollback scripts are optional for small migrations.",
            List.of("unverified suggestion"),
            1,
            Instant.now(),
            Instant.now());

    bullets.curate(active);
    AceBullet pending = bullets.propose(conflicting);

    assertThat(bullets.approve(pending.id())).isFalse();
    assertThat(bullets.pending()).extracting(AceBullet::id).contains(pending.id());
  }

  @Test
  void curatorMergesRepeatedBulletsAndSearchesProjectLocalLessons() {
    MemoryStore bullets = memory("workspace");
    Instant now = Instant.parse("2026-07-30T00:00:00Z");
    AceBullet first =
        new AceBullet(
            null,
            "Maven formatting failure",
            "Run Spotless before tests.",
            java.util.List.of("spotless reported a violation"),
            1,
            now,
            now);

    bullets.curate(first);
    AceBullet merged =
        bullets.curate(
            new AceBullet(
                null,
                first.trigger(),
                first.lesson(),
                java.util.List.of("second occurrence"),
                1,
                now,
                now));

    assertThat(merged.occurrences()).isEqualTo(2);
    assertThat(merged.confidence()).isGreaterThan(0.55);
    assertThat(bullets.search("Maven formatting", 3))
        .singleElement()
        .extracting(AceBullet::lesson)
        .isEqualTo("Run Spotless before tests.");
  }

  @Test
  void archivesBulletsWithoutDeletingTheirHistory() {
    MemoryStore bullets = memory("workspace");
    Instant now = Instant.parse("2026-07-30T00:00:00Z");
    AceBullet stored =
        bullets.curate(
            new AceBullet(
                null,
                "old build layout",
                "Do not use this after the build migration.",
                java.util.List.of(),
                1,
                now,
                now,
                0.8,
                java.util.List.of("legacy/**"),
                AceBullet.State.ACTIVE));

    assertThat(bullets.archive(stored.id())).isTrue();
    assertThat(bullets.search("build layout", 3)).isEmpty();
    assertThat(bullets.list())
        .singleElement()
        .extracting(AceBullet::state)
        .isEqualTo(AceBullet.State.ARCHIVED);
  }

  @Test
  void failureProducesAReflexionCandidateForCuration() {
    ReflexionExtractor reflexion = new ReflexionExtractor(Clock.systemUTC());

    AceBullet candidate =
        reflexion
            .extract(
                java.util.List.of(new UserMessage("Fix the failed Maven build")),
                AgentStatus.FAILED,
                "compile error")
            .orElseThrow();

    assertThat(candidate.trigger()).isEqualTo("failed turn");
    assertThat(candidate.evidence()).containsExactly("turn ended without verified completion");
  }

  @Test
  void repairedToolFailureAlsoProducesAReflexionCandidate() {
    ReflexionExtractor reflexion = new ReflexionExtractor(Clock.systemUTC());

    AceBullet candidate =
        reflexion
            .extract(
                java.util.List.of(
                    new UserMessage("Fix the failed Maven build"),
                    new ToolMessage("call", "shell:run", "tests failed", true)),
                AgentStatus.COMPLETED,
                "fixed")
            .orElseThrow();

    assertThat(candidate.evidence()).containsExactly("tool failure observed: shell:run");
  }

  @Test
  void verifiedSuccessfulChangeAlsoProducesACandidate() {
    ReflexionExtractor reflexion = new ReflexionExtractor(Clock.systemUTC());

    AceBullet candidate =
        reflexion
            .extract(
                java.util.List.of(
                    new UserMessage("Improve the build"),
                    new ToolMessage(
                        "verify", "shell:run", "[verification-command-succeeded] mvn test", false)),
                AgentStatus.COMPLETED,
                "")
            .orElseThrow();

    assertThat(candidate.lesson()).contains("verification command succeeded");
  }

  @Test
  void isolatesWorkspacesAndSearchesChineseWithTrigrams() {
    MemoryStore first = memory("first");
    MemoryStore second = memory("second");
    Instant now = Instant.parse("2026-08-10T00:00:00Z");
    first.curate(
        new AceBullet(null, "数据库迁移失败", "先检查迁移脚本，再执行回滚。", List.of("测试发现字段缺失"), 1, now, now));

    assertThat(first.search("迁移脚本", 3)).hasSize(1);
    assertThat(second.search("迁移脚本", 3)).isEmpty();
  }

  @Test
  void importsLegacyJsonlOnceWithoutDeletingIt() throws Exception {
    Path workspace = temporaryDirectory.resolve("legacy-workspace");
    Path legacy = workspace.resolve(".miniclaudecode/bullets/ace.jsonl");
    Files.createDirectories(legacy.getParent());
    Files.writeString(
        legacy,
        "{\"id\":\"legacy\",\"trigger\":\"old build\",\"lesson\":\"run tests\",\"evidence\":[],\"occurrences\":1,\"createdAt\":\"2026-08-10T00:00:00Z\",\"updatedAt\":\"2026-08-10T00:00:00Z\",\"confidence\":0.7,\"applicablePaths\":[],\"state\":\"ACTIVE\"}\n",
        StandardCharsets.UTF_8);

    MemoryStore first =
        new SqliteMemoryStore(
            temporaryDirectory.resolve("legacy-memory.db"), "legacy-workspace", legacy);
    MemoryStore reopened =
        new SqliteMemoryStore(
            temporaryDirectory.resolve("legacy-memory.db"), "legacy-workspace", legacy);

    assertThat(first.list()).hasSize(1);
    assertThat(reopened.list()).hasSize(1);
    assertThat(legacy).exists();
  }

  @Test
  void rejectsKnownSecretsFromLongTermMemory() {
    MemoryStore store =
        new SqliteMemoryStore(
            temporaryDirectory.resolve("secret-memory.db"),
            "workspace",
            temporaryDirectory.resolve("missing.jsonl"),
            java.util.Set.of("super-secret-value"));
    Instant now = Instant.parse("2026-08-10T00:00:00Z");

    assertThatThrownBy(
            () ->
                store.propose(
                    new AceBullet(
                        null,
                        "build failure",
                        "Never print super-secret-value in logs",
                        List.of("sanitized evidence"),
                        1,
                        now,
                        now)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("secret-like");
  }

  private MemoryStore memory(String workspaceId) {
    return new SqliteMemoryStore(
        temporaryDirectory.resolve("memory.db"),
        workspaceId,
        temporaryDirectory.resolve(workspaceId).resolve("missing.jsonl"));
  }
}
