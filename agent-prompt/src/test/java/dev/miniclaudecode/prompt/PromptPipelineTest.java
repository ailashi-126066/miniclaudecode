package dev.miniclaudecode.prompt;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class PromptPipelineTest {
  @Test
  void contributorsAreOrderedAndEmptySectionsAreSkipped() {
    PromptPipeline pipeline =
        new PromptPipeline(
            List.of(
                PromptContributor.of("last", 20, context -> "last"),
                PromptContributor.of("empty", 15, context -> ""),
                PromptContributor.of("first", 10, context -> "first")));

    String prompt =
        pipeline.build(new PromptBuildContext(Path.of("."), List.of(), "", "", Map.of()));

    Assertions.assertThat(prompt).isEqualTo("first" + System.lineSeparator() + "last");
    Assertions.assertThat(pipeline.contributorIds()).containsExactly("first", "empty", "last");
  }
}
