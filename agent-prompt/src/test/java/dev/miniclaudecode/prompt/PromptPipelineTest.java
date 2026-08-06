package dev.miniclaudecode.prompt;

import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

  @Test
  void contributorsWithTheSameOrderAreSortedByIdAndDuplicateSectionsAreRenderedOnce() {
    PromptPipeline pipeline =
        new PromptPipeline(
            List.of(
                PromptContributor.of("zeta", 10, context -> "same"),
                PromptContributor.of("alpha", 10, context -> "same"),
                PromptContributor.of("middle", 10, context -> "different")));

    String prompt = pipeline.build(context());

    Assertions.assertThat(pipeline.contributorIds()).containsExactly("alpha", "middle", "zeta");
    Assertions.assertThat(prompt).isEqualTo("same" + System.lineSeparator() + "different");
  }

  @Test
  void aFailingContributorDoesNotSuppressHealthySections() {
    PromptContributor failing =
        new PromptContributor() {
          @Override
          public String id() {
            return "failing";
          }

          @Override
          public int order() {
            return 20;
          }

          @Override
          public java.util.Optional<String> contribute(PromptBuildContext context) {
            throw new IllegalStateException("broken optional contributor");
          }
        };
    PromptPipeline pipeline =
        new PromptPipeline(
            List.of(
                PromptContributor.of("before", 10, context -> "before"),
                failing,
                PromptContributor.of("after", 30, context -> "after")));

    Assertions.assertThat(pipeline.build(context()))
        .isEqualTo("before" + System.lineSeparator() + "after");
  }

  @Test
  void buildContextNormalizesValuesAndDefensivelyCopiesCollections() {
    List<ToolDescriptor> tools = new ArrayList<>();
    tools.add(new ToolDescriptor("workspace", "read", "read", "{}", RiskLevel.LOW));
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("mode", "test");

    PromptBuildContext context =
        new PromptBuildContext(
            Path.of("folder", "..", "workspace"), tools, "  skills  ", "  protocol  ", attributes);
    tools.clear();
    attributes.clear();

    Assertions.assertThat(context.workspace()).isEqualTo(Path.of("workspace"));
    Assertions.assertThat(context.tools()).hasSize(1);
    Assertions.assertThat(context.skillIndex()).isEqualTo("skills");
    Assertions.assertThat(context.outputProtocolInstruction()).isEqualTo("protocol");
    Assertions.assertThat(context.attributes()).containsEntry("mode", "test");
    Assertions.assertThatThrownBy(() -> context.tools().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    Assertions.assertThatThrownBy(() -> context.attributes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void contextUsesEmptyTextForOptionalSectionsAndRejectsNullCollections() {
    PromptBuildContext context =
        new PromptBuildContext(Path.of("."), List.of(), null, null, Map.of());

    Assertions.assertThat(context.skillIndex()).isEmpty();
    Assertions.assertThat(context.outputProtocolInstruction()).isEmpty();
    Assertions.assertThatThrownBy(
            () -> new PromptBuildContext(Path.of("."), null, "", "", Map.of()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("tools");
    Assertions.assertThatThrownBy(
            () -> new PromptBuildContext(Path.of("."), List.of(), "", "", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("attributes");
  }

  @Test
  void contributorFactoryNormalizesIdentifiersAndSections() {
    PromptContributor contributor = PromptContributor.of("  sample  ", 7, context -> "  section  ");

    Assertions.assertThat(contributor.id()).isEqualTo("sample");
    Assertions.assertThat(contributor.order()).isEqualTo(7);
    Assertions.assertThat(contributor.contribute(context())).contains("section");
    Assertions.assertThatThrownBy(() -> PromptContributor.of("  ", 1, context -> "x"))
        .isInstanceOf(IllegalArgumentException.class);
    Assertions.assertThatThrownBy(() -> PromptContributor.of("x", 1, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void defaultContributorsRenderWorkspaceProtocolAndSkillsInStableOrder() {
    PromptPipeline pipeline = new PromptPipeline(DefaultCodingPromptContributors.create());
    PromptBuildContext context =
        new PromptBuildContext(
            Path.of("workspace"), List.of(), "skill-index", "json-only", Map.of());

    String prompt = pipeline.build(context);

    Assertions.assertThat(pipeline.contributorIds())
        .containsExactly(
            "identity",
            "discovery",
            "context",
            "delegation",
            "security",
            "loop",
            "engineering-report",
            "durable-facts",
            "output-protocol",
            "workspace",
            "skills");
    Assertions.assertThat(prompt)
        .contains("MiniClaudeCode")
        .contains("json-only")
        .contains("Workspace: workspace")
        .endsWith("skill-index");
  }

  @Test
  void pipelineRejectsNullInputs() {
    Assertions.assertThatThrownBy(() -> new PromptPipeline(null))
        .isInstanceOf(NullPointerException.class);
    PromptPipeline pipeline = new PromptPipeline(List.of());
    Assertions.assertThatThrownBy(() -> pipeline.build(null))
        .isInstanceOf(NullPointerException.class);
    Assertions.assertThat(pipeline.build(context())).isEmpty();
  }

  private static PromptBuildContext context() {
    return new PromptBuildContext(Path.of("."), List.of(), "", "", Map.of());
  }
}
