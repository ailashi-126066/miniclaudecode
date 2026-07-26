package dev.miniclaudecode.extensions.skill;

import dev.miniclaudecode.extensions.skill.SkillDescriptor.Source;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillScannerTest {
  @TempDir Path temporaryDirectory;

  @Test
  void parsesFrontmatterAndHeadingFallbackWithoutFollowingUnrelatedFiles() throws Exception {
    Path root = Files.createDirectory(this.temporaryDirectory.resolve("skills"));
    Path review = Files.createDirectories(root.resolve("review"));
    Files.writeString(
        review.resolve("SKILL.md"),
        "---\n"
            + "name: java-review\n"
            + "description: Review Java patches safely\n"
            + "---\n"
            + "# Secret body\n"
            + "Do the full review.\n");
    Path tests = Files.createDirectories(root.resolve("tests"));
    Files.writeString(
        tests.resolve("SKILL.md"),
        "# Testing Expert\n\nRun focused tests before the full build.\n");
    Files.writeString(root.resolve("README.md"), "ignored");
    List<SkillDescriptor> descriptors = new SkillScanner().scan(root, Source.USER);
    Assertions.assertThat(descriptors)
        .extracting(SkillDescriptor::name)
        .containsExactly(new String[] {"java-review", "testing-expert"});
    Assertions.assertThat(descriptors)
        .filteredOn(value -> value.name().equals("java-review"))
        .singleElement()
        .satisfies(
            value ->
                Assertions.assertThat(value.description()).isEqualTo("Review Java patches safely"));
    Assertions.assertThat(descriptors)
        .filteredOn(value -> value.name().equals("testing-expert"))
        .singleElement()
        .satisfies(
            value ->
                Assertions.assertThat(value.description())
                    .isEqualTo("Run focused tests before the full build."));
  }

  @Test
  void ignoresMalformedUtf8SkillFiles() throws Exception {
    Path root = Files.createDirectory(this.temporaryDirectory.resolve("malformed"));
    Path skill = Files.createDirectories(root.resolve("bad"));
    Files.write(skill.resolve("SKILL.md"), new byte[] {-61, 40});
    Assertions.assertThat(new SkillScanner().scan(root, Source.USER)).isEmpty();
  }
}
