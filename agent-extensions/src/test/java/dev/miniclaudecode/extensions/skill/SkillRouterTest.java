package dev.miniclaudecode.extensions.skill;

import dev.miniclaudecode.extensions.skill.SkillDescriptor.Source;
import java.nio.file.Path;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillRouterTest {
  @Test
  void metadataAccessorsReturnDefensiveCopies() {
    Path root = Path.of("skills").toAbsolutePath();
    SkillDescriptor descriptor =
        new SkillDescriptor(
            "testing",
            "Run focused verification",
            root.resolve("testing/SKILL.md"),
            root,
            Source.PROJECT,
            100,
            List.of("junit"),
            List.of("test failure"),
            List.of("Do not deploy"),
            List.of("Verify a Java change"));

    descriptor.tags().clear();
    descriptor.triggers().clear();
    descriptor.boundaries().clear();
    descriptor.examples().clear();

    Assertions.assertThat(descriptor.tags()).containsExactly("junit");
    Assertions.assertThat(descriptor.triggers()).containsExactly("test failure");
    Assertions.assertThat(descriptor.boundaries()).containsExactly("Do not deploy");
    Assertions.assertThat(descriptor.examples()).containsExactly("Verify a Java change");
  }

  @Test
  void recallsMetadataThenReranksTagsAndTriggersWithoutLoadingBodies() {
    Path root = Path.of("skills").toAbsolutePath();
    SkillDescriptor testing =
        new SkillDescriptor(
            "testing",
            "Run focused verification",
            root.resolve("testing/SKILL.md"),
            root,
            Source.PROJECT,
            100,
            List.of("测试", "junit"),
            List.of("修复失败测试"),
            List.of("Do not deploy"),
            List.of("验证 Java 修改"));
    SkillDescriptor deployment =
        new SkillDescriptor(
            "deploy",
            "Publish a release",
            root.resolve("deploy/SKILL.md"),
            root,
            Source.PROJECT,
            100);

    List<SkillRouter.RouteMatch> matches =
        new SkillRouter().route("修复 Java 测试并运行 JUnit", List.of(deployment, testing), 2);

    Assertions.assertThat(matches).singleElement();
    Assertions.assertThat(matches.getFirst().skill().name()).isEqualTo("testing");
    Assertions.assertThat(matches.getFirst().reasons()).contains("tags=2");
  }
}
