package dev.miniclaudecode.runtime.output;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates provenance, not truth: citations must name spans actually returned by code search. */
public final class RagCitationValidator {
  public static final String ATTRIBUTE = "requireRagCitations";
  private static final Pattern CITATION = Pattern.compile("【([^】]+)】");

  public Evaluation evaluate(
      ModelRequest request, Iterable<AgentMessage> messages, String finalText) {
    if (!Boolean.TRUE.equals(request.attributes().get(ATTRIBUTE))) {
      return Evaluation.passed();
    }
    Set<String> allowed = new LinkedHashSet<>();
    for (AgentMessage message : messages) {
      if (message instanceof ToolMessage tool
          && "workspace:code_search".equals(tool.qualifiedToolName())
          && !tool.error()) {
        allowed.addAll(citations(tool.text()));
      }
    }
    if (allowed.isEmpty()) {
      return Evaluation.passed();
    }
    Set<String> used = citations(finalText);
    if (used.isEmpty()) {
      return Evaluation.invalid(
          "Your final answer relies on workspace:code_search evidence. Add at least one exact "
              + "citation in the form 【path:start-end】 from the tool results, or explicitly say "
              + "you need to inspect more evidence.");
    }
    if (!allowed.containsAll(used)) {
      return Evaluation.invalid(
          "Replace every citation with an exact span returned by workspace:code_search. Do not "
              + "invent paths or line ranges; valid citations use 【path:start-end】.");
    }
    return Evaluation.passed();
  }

  private static Set<String> citations(String text) {
    Set<String> values = new LinkedHashSet<>();
    Matcher matcher = CITATION.matcher(text == null ? "" : text);
    while (matcher.find()) {
      values.add(matcher.group(1));
    }
    return values;
  }

  public record Evaluation(boolean valid, String repairInstruction) {
    static Evaluation passed() {
      return new Evaluation(true, "");
    }

    static Evaluation invalid(String repairInstruction) {
      return new Evaluation(false, repairInstruction);
    }
  }
}
