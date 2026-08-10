package dev.miniclaudecode.persistence.path;

import java.nio.file.Path;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class UserDataLayoutTest {
  @Test
  void laysOutAllRuntimeDataUnderUserHome() {
    UserDataLayout layout = UserDataLayout.forHome(Path.of("C:/Users/alice"));
    Assertions.assertThat(layout.root().getFileName().toString()).isEqualTo(".mini-claude-code");
    Assertions.assertThat(layout.configFile()).isEqualTo(layout.root().resolve("config.yaml"));
    Assertions.assertThat(layout.permissionsFile())
        .isEqualTo(layout.root().resolve("permissions.json"));
    Assertions.assertThat(layout.sessionsRoot()).isEqualTo(layout.root().resolve("sessions"));
    Assertions.assertThat(layout.checkpointsRoot()).isEqualTo(layout.root().resolve("checkpoints"));
    Assertions.assertThat(layout.toolResultsRoot())
        .isEqualTo(layout.root().resolve("tool-results"));
    Assertions.assertThat(layout.indexesRoot()).isEqualTo(layout.root().resolve("indexes"));
    Assertions.assertThat(layout.skillsRoot()).isEqualTo(layout.root().resolve("skills"));
  }

  @Test
  void createsStableWorkspaceScopedPathsWithoutLeakingWorkspaceName() {
    UserDataLayout layout = UserDataLayout.forHome(Path.of("/home/alice"));
    Path first = layout.sessionWorkspaceRoot(Path.of("/work/client-secret-project"));
    Path second = layout.sessionWorkspaceRoot(Path.of("/work/client-secret-project"));
    Assertions.assertThat(first).isEqualTo(second);
    Assertions.assertThat(first.normalize().toString())
        .startsWith(layout.sessionsRoot().normalize().toString());
    ((AbstractStringAssert) Assertions.assertThat(first.getFileName().toString()).hasSize(64))
        .doesNotContain(new CharSequence[] {"client-secret-project"});
  }
}
