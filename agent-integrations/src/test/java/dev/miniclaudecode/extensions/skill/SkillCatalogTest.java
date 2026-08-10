package dev.miniclaudecode.extensions.skill;

import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.extensions.skill.SkillDescriptor.Source;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.MapAssert;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillCatalogTest {
  @TempDir Path temporaryDirectory;

  @Test
  void projectOverridesCompatibilityAndUserSkillsWhilePromptContainsOnlyIndex() throws Exception {
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace"));
    Path user = Files.createDirectories(this.temporaryDirectory.resolve("user-skills/review"));
    this.writeSkill(user, "review", "User review", "USER-SECRET-INSTRUCTIONS");
    Path claude = Files.createDirectories(workspace.resolve(".claude/skills/review"));
    this.writeSkill(claude, "review", "Claude review", "CLAUDE-SECRET-INSTRUCTIONS");
    Path project = Files.createDirectories(workspace.resolve(".mini-claude-code/skills/review"));
    this.writeSkill(project, "review", "Project review", "PROJECT-SECRET-INSTRUCTIONS");
    SkillCatalog catalog = SkillCatalog.discover(workspace, user.getParent());
    Assertions.assertThat(catalog.list()).singleElement();
    Assertions.assertThat(((SkillDescriptor) catalog.list().getFirst()).source())
        .isEqualTo(Source.PROJECT);
    ((AbstractStringAssert)
            Assertions.assertThat(catalog.promptIndex())
                .contains(new CharSequence[] {"review", "Project review", "descriptions only"}))
        .doesNotContain(
            new CharSequence[] {"PROJECT-SECRET-INSTRUCTIONS", "USER-SECRET-INSTRUCTIONS"});
    Assertions.assertThat(catalog.load("review").content())
        .contains(new CharSequence[] {"PROJECT-SECRET-INSTRUCTIONS"});
  }

  @Test
  void truncatesLargeSkillAndLoadToolCannotElevatePermissions() throws Exception {
    Path root = Files.createDirectory(this.temporaryDirectory.resolve("large-root"));
    Path skill = Files.createDirectories(root.resolve("large"));
    this.writeSkill(skill, "large", "Large instructions", "x".repeat(1000));
    SkillDescriptor descriptor =
        (SkillDescriptor) new SkillScanner().scan(root, Source.USER).getFirst();
    SkillCatalog catalog = new SkillCatalog(List.of(descriptor), 120);
    LoadSkillTool tool = new LoadSkillTool(catalog);
    ToolResult result =
        (ToolResult)
            tool.execute(
                    new ToolCall("call-1", "skills:load_skill", "{\"name\":\"large\"}"),
                    this.context())
                .toCompletableFuture()
                .get();
    Assertions.assertThat(result.status()).isEqualTo(Status.COMPLETED);
    Assertions.assertThat(result.summary())
        .contains(new CharSequence[] {"Skill truncated at 120 bytes"});
    ((MapAssert) Assertions.assertThat(result.metadata()).containsEntry("truncated", true))
        .containsEntry("permissionsUnchanged", true);
    Assertions.assertThat(tool.descriptor().description())
        .contains(new CharSequence[] {"never change permissions"});
  }

  @Test
  void rejectsSkillReplacedBySymlinkEscapingItsRoot() throws Exception {
    Path root = Files.createDirectory(this.temporaryDirectory.resolve("safe-root"));
    Path directory = Files.createDirectories(root.resolve("safe"));
    Path file = directory.resolve("SKILL.md");
    this.writeSkill(directory, "safe", "Safe skill", "safe body");
    SkillDescriptor descriptor =
        (SkillDescriptor) new SkillScanner().scan(root, Source.USER).getFirst();
    Path outside = this.temporaryDirectory.resolve("outside.md");
    Files.writeString(outside, "outside");

    try {
      Files.delete(file);
      Files.createSymbolicLink(file, outside);
    } catch (UnsupportedOperationException | SecurityException | IOException var7) {
      Assumptions.assumeTrue(false, "symbolic links are not available: " + var7.getMessage());
    }

    SkillCatalog catalog = new SkillCatalog(List.of(descriptor));
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(() -> catalog.load("safe"))
                .isInstanceOf(SecurityException.class))
        .hasMessageContaining("escapes");
  }

  private void writeSkill(Path directory, String name, String description, String body)
      throws IOException {
    Files.writeString(
        directory.resolve("SKILL.md"),
        "---\nname: "
            + name
            + "\ndescription: "
            + description
            + "\n---\n# "
            + name
            + "\n"
            + body
            + "\n");
  }

  private ToolContext context() {
    return new ToolContext(
        new SessionId("session-1"),
        new TurnId(1L),
        this.temporaryDirectory,
        EventSink.NOOP,
        Map.of());
  }
}
