package dev.miniclaudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

class StreamingRendererTest {

  @Test
  void drainsQueuedEventsOnOneRendererAndUsesDistinctStyles() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (var terminal =
        TerminalBuilder.builder()
            .system(false)
            .streams(new ByteArrayInputStream(new byte[0]), output)
            .encoding(StandardCharsets.UTF_8)
            .type("xterm")
            .build()) {
      StreamingRenderer renderer = new StreamingRenderer(terminal);
      var thinking = new StreamingRenderer.RenderEvent.Thinking("checking files");
      var progress = new StreamingRenderer.RenderEvent.Progress("reading App.java");
      var text = new StreamingRenderer.RenderEvent.Text("done");
      renderer.submit(thinking);
      renderer.submit(progress);
      renderer.submit(text);

      renderer.renderUntil(CompletableFuture.completedFuture(null), Duration.ofMillis(5));

      String rendered = output.toString(StandardCharsets.UTF_8);
      assertThat(rendered).contains("checking files", "reading App.java", "done");
      assertThat(renderer.styled(thinking).styleAt(0))
          .isNotEqualTo(renderer.styled(progress).styleAt(0));
      assertThat(renderer.styled(progress).styleAt(0))
          .isNotEqualTo(renderer.styled(text).styleAt(0));
    }
  }
}
