package dev.miniclaudecode.runtime.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;

/**
 * Tolerant parser for a strict terminal JSON envelope.
 *
 * <p>It accepts a fenced or prefixed object so unstable model output can still be recovered, but
 * requires the semantic contract {@code status=completed} and a non-blank {@code final} field.
 */
public final class JsonOutputProtocol implements OutputProtocol {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String REPAIR =
      "Reformat only your final response as valid JSON with exactly this shape:"
          + " {\"status\":\"completed\",\"final\":\"human-readable result\"}."
          + " Do not use Markdown fences or add surrounding text.";

  @Override
  public Evaluation evaluate(String response) {
    String candidate = jsonCandidate(Objects.requireNonNullElse(response, ""));
    if (candidate.isEmpty()) {
      return Evaluation.repair(REPAIR);
    }
    try {
      JsonNode root = JSON.readTree(candidate);
      if (!root.isObject()
          || !"completed".equalsIgnoreCase(root.path("status").asText())
          || root.path("final").asText("").isBlank()) {
        return Evaluation.repair(REPAIR);
      }
      return Evaluation.valid(root.path("final").asText().strip());
    } catch (IOException error) {
      return Evaluation.repair(REPAIR);
    }
  }

  private static String jsonCandidate(String response) {
    String value = response.strip();
    if (value.startsWith("```") && value.endsWith("```")) {
      int firstLine = value.indexOf('\n');
      value = firstLine >= 0 ? value.substring(firstLine + 1, value.length() - 3).strip() : "";
    }
    int start = value.indexOf('{');
    int end = value.lastIndexOf('}');
    return start >= 0 && end >= start ? value.substring(start, end + 1) : "";
  }
}
