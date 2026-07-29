package dev.miniclaudecode.persistence.memory;

import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.persistence.config.SecretRedactor;
import dev.miniclaudecode.persistence.memory.MemoryRecord.Category;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonlMemoryStoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void redactsDeduplicatesAndRetrievesCrossSessionMemory() throws Exception {
    Path file = this.temporaryDirectory.resolve("memory.jsonl");
    JsonlMemoryStore store = new JsonlMemoryStore(file, new SecretRedactor(), Set.of("sk-private"));
    MemoryRecord memory =
        new MemoryRecord(
            null,
            Category.ERROR_REPAIR,
            "修复 Maven 编译失败",
            "清理旧缓存后重新编译 sk-private",
            List.of("verified: tests passed"),
            SessionId.of("session-a"),
            TurnId.of(2),
            Instant.parse("2026-07-28T12:00:00Z"));

    Assertions.assertThat(store.save(memory)).isTrue();
    Assertions.assertThat(store.save(memory)).isFalse();
    memory.evidence().clear();
    Assertions.assertThat(memory.evidence()).containsExactly("verified: tests passed");
    Assertions.assertThat(store.search("Maven 编译错误", 3))
        .singleElement()
        .extracting(hit -> hit.memory().category())
        .isEqualTo(Category.ERROR_REPAIR);
    Assertions.assertThat(Files.readString(file)).doesNotContain("sk-private").contains("***");
  }
}
