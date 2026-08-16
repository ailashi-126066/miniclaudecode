package dev.miniclaudecode.cli.tui;

import dev.miniclaudecode.cli.TurnOutcome;
import java.util.Objects;

/** UI-only events; Provider and tool events are adapted before they reach the reducer. */
public sealed interface TuiEvent
    permits TuiEvent.Resize,
        TuiEvent.InputChanged,
        TuiEvent.PromptSubmitted,
        TuiEvent.TextDelta,
        TuiEvent.ThinkingDelta,
        TuiEvent.Progress,
        TuiEvent.DashboardUpdated,
        TuiEvent.CommandResult,
        TuiEvent.Failure,
        TuiEvent.TurnFinished,
        TuiEvent.ApprovalStarted {

  record Resize(int width, int height) implements TuiEvent {}

  record InputChanged(String value) implements TuiEvent {
    public InputChanged {
      value = Objects.requireNonNull(value, "value must not be null");
    }
  }

  record PromptSubmitted(String prompt) implements TuiEvent {
    public PromptSubmitted {
      prompt = requireText(prompt);
    }
  }

  record TextDelta(String text) implements TuiEvent {
    public TextDelta {
      text = Objects.requireNonNull(text, "text must not be null");
    }
  }

  record ThinkingDelta(String text) implements TuiEvent {
    public ThinkingDelta {
      text = Objects.requireNonNull(text, "text must not be null");
    }
  }

  record Progress(String text) implements TuiEvent {
    public Progress {
      text = requireText(text);
    }
  }

  record DashboardUpdated(TuiDashboard dashboard) implements TuiEvent {
    public DashboardUpdated {
      dashboard = Objects.requireNonNull(dashboard, "dashboard must not be null");
    }
  }

  record CommandResult(String text) implements TuiEvent {
    public CommandResult {
      text = requireText(text);
    }
  }

  record Failure(String text) implements TuiEvent {
    public Failure {
      text = requireText(text);
    }
  }

  record TurnFinished(TurnOutcome outcome) implements TuiEvent {
    public TurnFinished {
      outcome = Objects.requireNonNull(outcome, "outcome must not be null");
    }
  }

  record ApprovalStarted() implements TuiEvent {}

  private static String requireText(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("text must not be blank");
    }
    return value;
  }
}
