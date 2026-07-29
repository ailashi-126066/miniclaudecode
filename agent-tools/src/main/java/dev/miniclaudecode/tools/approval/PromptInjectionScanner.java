package dev.miniclaudecode.tools.approval;

import dev.miniclaudecode.domain.approval.RiskLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Deterministic first-pass screening for instructions embedded in untrusted tool content. */
public final class PromptInjectionScanner {
  private static final List<Signal> SIGNALS =
      List.of(
          signal(
              "instruction-override",
              RiskLevel.HIGH,
              "(ignore|disregard|forget)\\s+(all\\s+)?"
                  + "((previous|prior)(\\s+(system|developer))?|system|developer)"
                  + "\\s+(instructions?|messages?|prompts?)|"
                  + "\u5ffd\u7565(\u4e4b\u524d|\u4e0a\u8ff0|\u7cfb\u7edf|\u5f00\u53d1\u8005)"
                  + ".*(\u6307\u4ee4|\u63d0\u793a\u8bcd)"),
          signal(
              "secret-exfiltration",
              RiskLevel.HIGH,
              "(reveal|print|send|upload|exfiltrat(e|ion)).{0,40}"
                  + "(secret|token|api.?key|credential|password|environment)|"
                  + "(\u6cc4\u9732|\u8f93\u51fa|\u4e0a\u4f20|\u53d1\u9001).{0,30}"
                  + "(\u5bc6\u94a5|\u4ee4\u724c|\u5bc6\u7801|\u73af\u5883\u53d8\u91cf)"),
          signal(
              "prompt-disclosure",
              RiskLevel.MEDIUM,
              "(system|developer)\\s+(prompt|message|instructions?)|"
                  + "(\u7cfb\u7edf|\u5f00\u53d1\u8005)(\u63d0\u793a\u8bcd|\u6d88\u606f|\u6307\u4ee4)"),
          signal(
              "tool-coercion",
              RiskLevel.MEDIUM,
              "(must|immediately|silently)\\s+(call|run|execute|use)\\s+.{0,30}(tool|command)|"
                  + "(\u5fc5\u987b|\u7acb\u5373|\u6084\u6084\u5730?).{0,20}"
                  + "(\u8c03\u7528\u5de5\u5177|\u6267\u884c\u547d\u4ee4)"));

  public Finding scan(String content) {
    String normalized = Objects.requireNonNullElse(content, "").toLowerCase(Locale.ROOT);
    List<String> matches = new ArrayList<>();
    RiskLevel risk = RiskLevel.LOW;
    for (Signal signal : SIGNALS) {
      if (signal.pattern().matcher(normalized).find()) {
        matches.add(signal.name());
        if (signal.risk().ordinal() > risk.ordinal()) {
          risk = signal.risk();
        }
      }
    }
    if (matches.size() >= 2) {
      risk = RiskLevel.HIGH;
    }
    return new Finding(risk, matches);
  }

  private static Signal signal(String name, RiskLevel risk, String regex) {
    return new Signal(
        name, risk, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
  }

  private record Signal(String name, RiskLevel risk, Pattern pattern) {}

  public record Finding(RiskLevel risk, List<String> signals) {
    public Finding {
      Objects.requireNonNull(risk);
      signals = List.copyOf(signals);
    }

    public boolean suspicious() {
      return risk != RiskLevel.LOW;
    }
  }
}
