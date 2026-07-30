package dev.miniclaudecode.tools.approval;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Extracts suspicious instruction signals from untrusted tool content.
 *
 * <p>This deliberately does not classify content as benign or malicious. It records the source and
 * observable signals for the central agent to assess in task context. Strong signals only request
 * elevated approval for later consequential actions in the same turn.
 */
public final class PromptInjectionScanner {
  private static final List<Signal> SIGNALS =
      List.of(
          signal(
              "instruction-override",
              true,
              "(ignore|disregard|forget)\\s+(all\\s+)?"
                  + "((previous|prior)(\\s+(system|developer))?|system|developer)"
                  + "\\s+(instructions?|messages?|prompts?)|"
                  + "忽略(之前|上述|系统|开发者).*(指令|提示词)"),
          signal(
              "secret-exfiltration",
              true,
              "(reveal|print|send|upload|exfiltrat(e|ion)).{0,40}"
                  + "(secret|token|api.?key|credential|password|environment)|"
                  + "(泄露|输出|上传|发送).{0,30}(密钥|令牌|密码|环境变量)"),
          signal(
              "prompt-disclosure",
              false,
              "(system|developer)\\s+(prompt|message|instructions?)|(系统|开发者)(提示词|消息|指令)"),
          signal(
              "tool-coercion",
              false,
              "(must|immediately|silently)\\s+(call|run|execute|use)\\s+.{0,30}(tool|command)|"
                  + "(必须|立即|悄悄地?).{0,20}(调用工具|执行命令)"),
          signal(
              "role-impersonation",
              true,
              "(you are now|act as|switch to|new)\\s+.{0,30}"
                  + "(system|developer|administrator|root)|(你现在是|切换为|扮演).{0,20}"
                  + "(系统|开发者|管理员)"),
          signal(
              "spaced-instruction-override",
              true,
              "i\\s*g\\s*n\\s*o\\s*r\\s*e\\s+(all\\s+)?"
                  + "((previous|prior)(\\s+(system|developer))?|system|developer)"
                  + "\\s+(instructions?|messages?|prompts?)"));

  public Finding scan(String source, String content) {
    String normalizedSource = Objects.requireNonNullElse(source, "unknown").strip();
    if (normalizedSource.isEmpty()) {
      normalizedSource = "unknown";
    }
    String normalized =
        Normalizer.normalize(Objects.requireNonNullElse(content, ""), Normalizer.Form.NFKC)
            .replaceAll("[\\u200B-\\u200D\\uFEFF]", "")
            .toLowerCase(Locale.ROOT);
    List<String> matches = new ArrayList<>();
    boolean elevatedApproval = false;
    for (Signal signal : SIGNALS) {
      if (signal.pattern().matcher(normalized).find()) {
        matches.add(signal.name());
        elevatedApproval |= signal.requiresElevatedApproval();
      }
    }
    if (matches.size() >= 2) {
      elevatedApproval = true;
    }
    return new Finding(normalizedSource, matches, elevatedApproval);
  }

  private static Signal signal(String name, boolean requiresElevatedApproval, String regex) {
    return new Signal(
        name,
        requiresElevatedApproval,
        Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
  }

  private record Signal(String name, boolean requiresElevatedApproval, Pattern pattern) {}

  public record Finding(String source, List<String> signals, boolean requiresElevatedApproval) {
    public Finding {
      source = Objects.requireNonNullElse(source, "unknown").strip();
      if (source.isEmpty()) {
        source = "unknown";
      }
      signals = List.copyOf(signals);
    }

    public boolean suspicious() {
      return !signals.isEmpty();
    }
  }
}
