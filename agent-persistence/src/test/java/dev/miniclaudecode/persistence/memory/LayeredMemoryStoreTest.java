package dev.miniclaudecode.persistence.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.session.AgentStatus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LayeredMemoryStoreTest {

  @TempDir Path temporaryDirectory;

  @Test
  void persistsUniqueUserPreferences() {
    UserProfileStore profile = new UserProfileStore(temporaryDirectory.resolve("profile.md"));

    assertThat(profile.add("Use Java 21")).isTrue();
    assertThat(profile.add(" use   java 21 ")).isFalse();
    assertThat(profile.list()).containsExactly("Use Java 21");
  }

  @Test
  void refusesCredentialsInUserPreferences() {
    UserProfileStore profile = new UserProfileStore(temporaryDirectory.resolve("profile.md"));

    assertThatThrownBy(() -> profile.add("api_key=sk-this-must-not-be-stored"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("credentials");
  }

  @Test
  void candidateMemoryRequiresExplicitApprovalBeforeRetrieval() {
    AceBulletStore bullets = new AceBulletStore(temporaryDirectory.resolve("workspace"));
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
    AceBulletStore bullets = new AceBulletStore(temporaryDirectory.resolve("workspace"));
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
    AceBulletStore bullets = new AceBulletStore(temporaryDirectory.resolve("workspace"));
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
    AceBulletStore bullets = new AceBulletStore(temporaryDirectory.resolve("workspace"));
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

    assertThat(candidate.trigger()).contains("Maven build");
    assertThat(candidate.evidence()).contains("failure: compile error");
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

    assertThat(candidate.evidence()).contains("recovered failure: shell:run: tests failed");
  }
}
