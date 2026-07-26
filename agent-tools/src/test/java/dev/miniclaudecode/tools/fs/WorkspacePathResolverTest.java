package dev.miniclaudecode.tools.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePathResolverTest {
  @TempDir Path tempDirectory;

  @Test
  void resolvesExistingWorkspaceRelativePath() throws IOException {
    Path workspace = Files.createDirectory(this.tempDirectory.resolve("workspace"));
    Path file = Files.writeString(workspace.resolve("pom.xml"), "<project/>");
    WorkspacePathResolver resolver = new WorkspacePathResolver(workspace);
    Assertions.assertThat(resolver.resolveExisting("pom.xml")).isEqualTo(file.toRealPath());
    Assertions.assertThat(resolver.relativeDisplay(file)).isEqualTo("pom.xml");
  }

  @Test
  void rejectsAbsoluteAndParentTraversalPaths() throws IOException {
    Path workspace = Files.createDirectory(this.tempDirectory.resolve("workspace"));
    Path outside = Files.writeString(this.tempDirectory.resolve("secret.txt"), "secret");
    WorkspacePathResolver resolver = new WorkspacePathResolver(workspace);
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(() -> resolver.resolveExisting(outside.toString()))
                .isInstanceOf(WorkspacePathException.class))
        .hasMessageContaining("relative");
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(() -> resolver.resolveExisting("../secret.txt"))
                .isInstanceOf(WorkspacePathException.class))
        .hasMessageContaining("outside");
  }

  @Test
  void rejectsSymbolicLinkEscapeWhenPlatformAllowsLinks() throws IOException {
    Path workspace = Files.createDirectory(this.tempDirectory.resolve("workspace"));
    Path outside = Files.writeString(this.tempDirectory.resolve("secret.txt"), "secret");
    Path link = workspace.resolve("secret-link.txt");

    try {
      Files.createSymbolicLink(link, outside);
    } catch (UnsupportedOperationException | SecurityException | IOException var5) {
      Assumptions.assumeTrue(false, "symbolic links are not available: " + var5.getMessage());
    }

    WorkspacePathResolver resolver = new WorkspacePathResolver(workspace);
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(() -> resolver.resolveExisting("secret-link.txt"))
                .isInstanceOf(WorkspacePathException.class))
        .hasMessageContaining("symbolic link");
  }
}
