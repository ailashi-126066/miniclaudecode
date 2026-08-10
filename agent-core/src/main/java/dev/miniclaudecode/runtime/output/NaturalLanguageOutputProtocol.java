package dev.miniclaudecode.runtime.output;

import java.util.Objects;

/** Natural language is terminal when it contains a non-blank answer and no tool call. */
public final class NaturalLanguageOutputProtocol implements OutputProtocol {
  @Override
  public Evaluation evaluate(String response) {
    String normalized = Objects.requireNonNullElse(response, "").strip();
    return normalized.isEmpty()
        ? Evaluation.repair(
            "Your previous response was empty. Return a clear final answer, or call a tool if"
                + " more work is required.")
        : Evaluation.valid(normalized);
  }
}
