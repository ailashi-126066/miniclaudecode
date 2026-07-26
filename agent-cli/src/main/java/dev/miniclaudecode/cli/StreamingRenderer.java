package dev.miniclaudecode.cli;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

public final class StreamingRenderer {

  private static final AttributedStyle THINKING_STYLE =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).faint().italic();
  private static final AttributedStyle PROGRESS_STYLE =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
  private static final AttributedStyle TEXT_STYLE = AttributedStyle.DEFAULT;
  private static final AttributedStyle BULLET_STYLE =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold();
  private static final AttributedStyle ERROR_STYLE =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold();

  private enum StreamMode {
    NONE,
    THINKING,
    TEXT
  }

  private final Terminal terminal;
  private final BlockingQueue<RenderEvent> events = new LinkedBlockingQueue<>();
  private StreamMode mode = StreamMode.NONE;

  public StreamingRenderer(Terminal terminal) {
    this.terminal = Objects.requireNonNull(terminal, "terminal must not be null");
  }

  public void submit(RenderEvent event) {
    events.add(Objects.requireNonNull(event, "event must not be null"));
  }

  public int renderAvailable() {
    int rendered = 0;
    RenderEvent event;
    while ((event = events.poll()) != null) {
      render(event);
      rendered++;
    }
    terminal.flush();
    return rendered;
  }

  public void renderUntil(CompletionStage<?> stage, Duration pollInterval) {
    Objects.requireNonNull(stage, "stage must not be null");
    Objects.requireNonNull(pollInterval, "pollInterval must not be null");
    if (pollInterval.isNegative() || pollInterval.isZero()) {
      throw new IllegalArgumentException("pollInterval must be positive");
    }
    var future = stage.toCompletableFuture();
    while (!future.isDone() || !events.isEmpty()) {
      try {
        RenderEvent event = events.poll(pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        if (event != null) {
          render(event);
          terminal.flush();
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  AttributedString styled(RenderEvent event) {
    return switch (event) {
      case RenderEvent.Thinking thinking -> new AttributedString(thinking.text(), THINKING_STYLE);
      case RenderEvent.Progress progress ->
          new AttributedString("• " + progress.text(), PROGRESS_STYLE);
      case RenderEvent.Text text -> new AttributedString(text.text(), TEXT_STYLE);
      case RenderEvent.Error error -> new AttributedString("× " + error.text(), ERROR_STYLE);
      case RenderEvent.Completed ignored -> new AttributedString("", TEXT_STYLE);
    };
  }

  private void render(RenderEvent event) {
    AttributedString output = styled(event);
    switch (event) {
      case RenderEvent.Thinking ignored -> {
        beginStream(StreamMode.THINKING, "✳ ", THINKING_STYLE);
        terminal.writer().print(output.toAnsi(terminal));
      }
      case RenderEvent.Text ignored -> {
        beginStream(StreamMode.TEXT, "● ", BULLET_STYLE);
        terminal.writer().print(output.toAnsi(terminal));
      }
      case RenderEvent.Progress ignored -> {
        endStream();
        terminal.writer().println(output.toAnsi(terminal));
      }
      case RenderEvent.Error ignored -> {
        endStream();
        terminal.writer().println(output.toAnsi(terminal));
      }
      case RenderEvent.Completed ignored -> {
        endStream();
        terminal.writer().println(output.toAnsi(terminal));
      }
    }
  }

  private void beginStream(StreamMode target, String prefix, AttributedStyle prefixStyle) {
    if (mode == target) {
      return;
    }
    endStream();
    terminal.writer().println();
    terminal.writer().print(new AttributedString(prefix, prefixStyle).toAnsi(terminal));
    mode = target;
  }

  private void endStream() {
    if (mode != StreamMode.NONE) {
      terminal.writer().println();
      mode = StreamMode.NONE;
    }
  }

  public sealed interface RenderEvent
      permits RenderEvent.Thinking,
          RenderEvent.Progress,
          RenderEvent.Text,
          RenderEvent.Error,
          RenderEvent.Completed {
    record Thinking(String text) implements RenderEvent {
      public Thinking {
        text = requireText(text);
      }
    }

    record Progress(String text) implements RenderEvent {
      public Progress {
        text = requireText(text);
      }
    }

    record Text(String text) implements RenderEvent {
      public Text {
        text = Objects.requireNonNull(text, "text must not be null");
      }
    }

    record Error(String text) implements RenderEvent {
      public Error {
        text = requireText(text);
      }
    }

    record Completed() implements RenderEvent {}

    private static String requireText(String value) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("render text must not be blank");
      }
      return value;
    }
  }
}
