package dev.miniclaudecode.persistence.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.session.AgentStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LayeredMemoryStoreTest {

  @TempDir Path temporaryDirectory;

  @Test
  void acceptedCandidateIsActiveImmediately() {
    MemoryStore bullets = memory("workspace");
    MemoryRecord candidate =
        bullets
            .remember(
                new MemoryCandidate(
                    MemoryType.VERIFIED_LESSON,
                    MemoryScope.PROJECT,
                    "Inspect the narrow test before retrying.",
                    "maven-timeout",
                    MemoryAuthority.VERIFIED_RESULT,
                    MemoryDurability.DURABLE,
                    List.of("test timed out"),
                    "session",
                    "1"))
            .orElseThrow();

    assertThat(candidate.state()).isEqualTo(MemoryState.ACTIVE);
    assertThat(bullets.search("narrow test", 3))
        .singleElement()
        .extracting(AceBullet::id)
        .isEqualTo(candidate.id());
  }

  @Test
  void aNewFactSupersedesAnActiveFactWithTheSameNormalizedKey() {
    MemoryStore bullets = memory("workspace");
    MemoryCandidate first =
        candidate("migration-policy", "Every migration needs an explicit rollback script.");
    MemoryCandidate replacement =
        candidate("migration-policy", "Use transactional rollback for small migrations.");

    String firstId = bullets.remember(first).orElseThrow().id();
    String replacementId = bullets.remember(replacement).orElseThrow().id();

    assertThat(replacementId).isNotEqualTo(firstId);
    assertThat(bullets.list())
        .filteredOn(memory -> memory.id().equals(firstId))
        .singleElement()
        .extracting(AceBullet::state)
        .isEqualTo(AceBullet.State.SUPERSEDED);
    assertThat(bullets.search("transactional rollback", 3))
        .singleElement()
        .extracting(AceBullet::id)
        .isEqualTo(replacementId);
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
  void editsAndClearsMemoriesWithoutDeletingHistory() {
    MemoryStore bullets = memory("workspace");
    MemoryRecord stored = bullets.remember(candidate("build-tool", "Use Gradle.")).orElseThrow();

    assertThat(bullets.edit(stored.id(), "Use the Maven wrapper.")).isTrue();
    assertThat(bullets.search("Maven wrapper", 3))
        .singleElement()
        .extracting(AceBullet::lesson)
        .isEqualTo("Use the Maven wrapper.");
    assertThat(bullets.clear()).isEqualTo(1);
    assertThat(bullets.search("Maven wrapper", 3)).isEmpty();
    assertThat(bullets.list())
        .singleElement()
        .extracting(AceBullet::state)
        .isEqualTo(AceBullet.State.ARCHIVED);
  }

  @Test
  void failedTurnDoesNotBecomeLongTermMemory() {
    ReflexionExtractor reflexion = new ReflexionExtractor(Clock.systemUTC());

    assertThat(
            reflexion.extract(
                java.util.List.of(new UserMessage("Fix the failed Maven build")),
                AgentStatus.FAILED,
                "compile error"))
        .isEmpty();
  }

  @Test
  void repairedFailureWithoutVerificationDoesNotBecomeLongTermMemory() {
    ReflexionExtractor reflexion = new ReflexionExtractor(Clock.systemUTC());

    assertThat(
            reflexion.extract(
                java.util.List.of(
                    new UserMessage("Fix the failed Maven build"),
                    new ToolMessage("call", "shell:run", "tests failed", true)),
                AgentStatus.COMPLETED,
                "fixed"))
        .isEmpty();
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
  void userPreferencesCrossProjectsButProjectMemoryDoesNot() {
    MemoryStore first = memory("first");
    MemoryStore second = memory("second");
    first.remember(
        new MemoryCandidate(
            MemoryType.PREFERENCE,
            MemoryScope.USER,
            "Prefer Maven wrapper commands.",
            "preference:build-tool",
            MemoryAuthority.USER_STATED,
            MemoryDurability.DURABLE,
            List.of("explicit user request"),
            "session",
            "1"));
    first.remember(candidate("project-database", "This project uses SQLite."));

    assertThat(second.search("Maven wrapper", 3))
        .singleElement()
        .extracting(AceBullet::lesson)
        .isEqualTo("Prefer Maven wrapper commands.");
    assertThat(second.search("project SQLite", 3)).isEmpty();
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
  void migratesPendingRowsToActiveWithoutLosingContent() throws Exception {
    Path database = temporaryDirectory.resolve("pending-v1.db");
    try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        java.sql.Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          """
          CREATE TABLE memory_entry(
            row_id INTEGER PRIMARY KEY AUTOINCREMENT,
            workspace_id TEXT NOT NULL,memory_id TEXT NOT NULL,trigger TEXT NOT NULL,
            lesson TEXT NOT NULL,evidence_json TEXT NOT NULL,evidence_text TEXT NOT NULL,
            applicable_paths_json TEXT NOT NULL,paths_text TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('PENDING_REVIEW','ACTIVE','ARCHIVED')),
            confidence REAL NOT NULL,occurrences INTEGER NOT NULL,
            created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
            UNIQUE(workspace_id,memory_id))
          """);
      statement.executeUpdate(
          """
          INSERT INTO memory_entry(
            workspace_id,memory_id,trigger,lesson,evidence_json,evidence_text,
            applicable_paths_json,paths_text,state,confidence,occurrences,created_at,updated_at)
          VALUES('workspace','legacy-pending','build','Use Maven','[]','','[]','',
            'PENDING_REVIEW',0.8,1,'2026-08-10T00:00:00Z','2026-08-10T00:00:00Z')
          """);
    }

    try (MemoryStore store =
        new SqliteMemoryStore(database, "workspace", temporaryDirectory.resolve("missing.jsonl"))) {
      assertThat(store.list())
          .singleElement()
          .satisfies(
              memory -> {
                assertThat(memory.lesson()).isEqualTo("Use Maven");
                assertThat(memory.state()).isEqualTo(AceBullet.State.ACTIVE);
              });
    }
  }

  @Test
  void configuredThresholdQueuesAndFlushesConsolidation() {
    Path database = temporaryDirectory.resolve("consolidation.db");
    Instant now = Instant.parse("2026-08-10T00:00:00Z");
    try (MemoryStore store =
        new SqliteMemoryStore(
            database,
            "workspace",
            temporaryDirectory.resolve("missing.jsonl"),
            java.util.Set.of(),
            3)) {
      store.curate(
          new AceBullet("duplicate-a", "same trigger", "same lesson", List.of(), 1, now, now));
      store.curate(
          new AceBullet("duplicate-b", "same trigger", "same lesson", List.of(), 1, now, now));
      store.remember(candidate("third-memory", "A third durable project decision."));
    }

    try (MemoryStore reopened =
        new SqliteMemoryStore(database, "workspace", temporaryDirectory.resolve("missing.jsonl"))) {
      assertThat(reopened.list())
          .filteredOn(memory -> memory.lesson().equals("same lesson"))
          .extracting(AceBullet::state)
          .containsExactlyInAnyOrder(AceBullet.State.ACTIVE, AceBullet.State.SUPERSEDED);
    }
  }

  @Test
  void rejectsKnownSecretsFromLongTermMemory() {
    MemoryStore store =
        new SqliteMemoryStore(
            temporaryDirectory.resolve("secret-memory.db"),
            "workspace",
            temporaryDirectory.resolve("missing.jsonl"),
            java.util.Set.of("super-secret-value"));
    assertThatThrownBy(
            () ->
                store.remember(
                    new MemoryCandidate(
                        MemoryType.VERIFIED_LESSON,
                        MemoryScope.PROJECT,
                        "Never print super-secret-value in logs",
                        "secret-lesson",
                        MemoryAuthority.VERIFIED_RESULT,
                        MemoryDurability.DURABLE,
                        List.of("sanitized evidence"),
                        "session",
                        "1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("secret-like");
  }

  @Test
  void deterministicGateRejectsTemporaryAndAssistantInferredMemory() {
    MemoryStore store = memory("gate");
    MemoryCandidate inferred =
        new MemoryCandidate(
            MemoryType.PROJECT_DECISION,
            MemoryScope.PROJECT,
            "Use Maven",
            "build-tool",
            MemoryAuthority.ASSISTANT_INFERENCE,
            MemoryDurability.DURABLE,
            List.of(),
            "session",
            "1");
    MemoryCandidate temporary =
        new MemoryCandidate(
            MemoryType.PREFERENCE,
            MemoryScope.USER,
            "Skip tests this time",
            "temporary-test-choice",
            MemoryAuthority.USER_STATED,
            MemoryDurability.TEMPORARY,
            List.of(),
            "session",
            "1");

    assertThat(store.remember(inferred)).isEmpty();
    assertThat(store.remember(temporary)).isEmpty();
    assertThat(store.list()).isEmpty();
  }

  private static MemoryCandidate candidate(String key, String content) {
    return new MemoryCandidate(
        MemoryType.PROJECT_DECISION,
        MemoryScope.PROJECT,
        content,
        key,
        MemoryAuthority.USER_STATED,
        MemoryDurability.DURABLE,
        List.of("explicit project decision"),
        "session",
        "1");
  }

  private MemoryStore memory(String workspaceId) {
    return new SqliteMemoryStore(
        temporaryDirectory.resolve("memory.db"),
        workspaceId,
        temporaryDirectory.resolve(workspaceId).resolve("missing.jsonl"));
  }
}
