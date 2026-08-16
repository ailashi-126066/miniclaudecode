package dev.miniclaudecode.cli;

import java.util.Objects;

/** Stable turn-to-UI event protocol shared by TUI and delegated agents. */
public sealed interface TurnEvent
    permits TurnEvent.Thinking,
        TurnEvent.Progress,
        TurnEvent.Text,
        TurnEvent.Error,
        TurnEvent.Completed {
  record Thinking(String text) implements TurnEvent {
    public Thinking {
      text = requireText(text);
    }
  }

  record Progress(String text) implements TurnEvent {
    public Progress {
      text = requireText(text);
    }
  }

  record Text(String text) implements TurnEvent {
    public Text {
      text = Objects.requireNonNull(text, "text must not be null");
    }
  }

  record Error(String text) implements TurnEvent {
    public Error {
      text = requireText(text);
    }
  }

  record Completed() implements TurnEvent {}

  private static String requireText(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("turn event text must not be blank");
    }
    return value;
  }
}
