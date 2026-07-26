package dev.miniclaudecode.tools.process;

import dev.miniclaudecode.domain.approval.RiskLevel;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies a shell command string into a {@link RiskLevel}.
 *
 * <p>{@link RiskLevel#LOW} is the only class that {@code RunCommandTool} executes without asking
 * the user, so the LOW decision is a security boundary rather than a UI hint. A command is LOW only
 * when every one of the following holds:
 *
 * <ol>
 *   <li>it contains no shell metacharacter that could start a second command,
 *   <li>its first token is an exact match (not a prefix) for a known read-only program,
 *   <li>none of its arguments can make that program spawn a process, write a file, or read outside
 *       the workspace.
 * </ol>
 *
 * <p>Rule 3 exists because several standard read-only tools are general-purpose execution engines
 * when given the right flag: {@code find -exec}, {@code find -fprintf}, {@code rg --pre} and {@code
 * git difftool --extcmd} all run arbitrary programs. Anything that is not provably read-only falls
 * through to {@link RiskLevel#MEDIUM}, which requires approval.
 */
public final class CommandRiskClassifier {

  private static final Set<Character> SHELL_OPERATORS =
      Set.of(';', '&', '|', '>', '<', '\n', '\r', '`', '$', '(', ')', '{', '}');

  /**
   * First tokens that are read-only when their arguments are safe. Matched as whole tokens, so
   * {@code lsof}, {@code pwdx} and {@code dircolors} no longer inherit {@code ls}/{@code
   * pwd}/{@code dir}.
   */
  private static final Set<String> READ_ONLY_COMMANDS =
      Set.of(
          "pwd",
          "ls",
          "dir",
          "rg",
          "grep",
          "find",
          "cat",
          "head",
          "tail",
          "wc",
          "get-childitem",
          "get-content",
          "get-location",
          "select-string");

  /** {@code git} subcommands that only read repository state. */
  private static final Set<String> READ_ONLY_GIT_SUBCOMMANDS =
      Set.of("status", "diff", "log", "show", "branch", "blame", "describe", "rev-parse");

  /**
   * Arguments that turn an otherwise read-only program into an execution or write primitive.
   * Matched case-insensitively against the whole token and against the {@code --flag=value} prefix.
   */
  private static final Set<String> EXECUTION_ARGUMENTS =
      Set.of(
          "-exec",
          "-execdir",
          "-ok",
          "-okdir",
          "-delete",
          "-fprintf",
          "-fprint",
          "-fprint0",
          "-fls",
          "--pre",
          "--hostname-bin",
          "--extcmd",
          "--output",
          "-o",
          "--open-files-in-pager",
          "--pager",
          "--ext-diff",
          "--textconv",
          "-c",
          "--config",
          "--upload-pack",
          "--receive-pack",
          "--exec-path");

  /**
   * Markers for irrecoverable commands. Each entry keeps a trailing delimiter so that {@code rm -rf
   * /tmp/build} stays HIGH instead of being escalated to CRITICAL alongside {@code rm -rf /} —
   * over-escalation causes alarm fatigue, which is itself a security problem.
   */
  private static final List<String> CRITICAL_MARKERS =
      List.of(
          "rm -rf / ",
          "rm -fr / ",
          "rm -r -f / ",
          "rm -rf /* ",
          "rm -rf ~ ",
          "rm -fr ~ ",
          "rm -rf * ",
          "format ",
          "mkfs",
          "diskpart",
          "shutdown",
          "reboot",
          "stop-computer",
          "clear-disk",
          ":(){",
          "dd if=");

  private static final List<String> HIGH_RISK_MARKERS =
      List.of(
          "rm ",
          "rmdir ",
          "remove-item",
          " del ",
          "erase ",
          "git reset --hard",
          "git clean -f",
          "git push",
          "sudo ",
          "doas ",
          "runas ",
          "invoke-expression",
          "iex ",
          "iex(",
          "curl ",
          "wget ",
          "invoke-webrequest",
          "iwr ",
          "invoke-restmethod",
          "irm ",
          "start-bitstransfer",
          "downloadstring",
          "downloadfile",
          "chmod ",
          "chown ",
          "icacls ",
          "takeown ",
          "attrib ",
          "set-executionpolicy",
          "nc ",
          "ncat ",
          "socat ",
          "ssh ",
          "scp ",
          "base64 -d",
          "base64 --decode",
          "certutil ");

  public RiskLevel classify(String command) {
    if (command == null || command.isBlank()) {
      throw new IllegalArgumentException("command must not be blank");
    }

    String normalized = " " + normalizeForMarkers(command) + " ";
    if (containsAny(normalized, CRITICAL_MARKERS)) {
      return RiskLevel.CRITICAL;
    }
    if (containsAny(normalized, HIGH_RISK_MARKERS)) {
      return RiskLevel.HIGH;
    }
    if (hasOperator(command)) {
      return RiskLevel.MEDIUM;
    }
    return isProvablyReadOnly(command) ? RiskLevel.LOW : RiskLevel.MEDIUM;
  }

  /**
   * Lowercases, collapses all whitespace (so a tab cannot hide {@code rm\t-rf}) and strips quote
   * characters (so {@code "rm" -rf ~} still matches the {@code rm } marker).
   */
  private static String normalizeForMarkers(String command) {
    return command
        .trim()
        .toLowerCase(Locale.ROOT)
        .replace('\t', ' ')
        .replace("\"", "")
        .replace("'", "")
        .replaceAll("\\s+", " ");
  }

  private static boolean isProvablyReadOnly(String command) {
    List<String> tokens = tokenize(command);
    if (tokens.isEmpty()) {
      return false;
    }

    String program = tokens.getFirst();
    int argumentStart = 1;
    if ("git".equals(program)) {
      if (tokens.size() < 2 || !READ_ONLY_GIT_SUBCOMMANDS.contains(tokens.get(1))) {
        return false;
      }
      argumentStart = 2;
    } else if (!READ_ONLY_COMMANDS.contains(program)) {
      return false;
    }

    for (int index = argumentStart; index < tokens.size(); index++) {
      if (!isSafeArgument(tokens.get(index))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isSafeArgument(String token) {
    String flag = token.indexOf('=') >= 0 ? token.substring(0, token.indexOf('=')) : token;
    if (EXECUTION_ARGUMENTS.contains(flag)) {
      return false;
    }
    // A bare `-` prefixed token that we do not recognise is allowed, but any token that looks like
    // a path must stay inside the workspace: absolute paths, `~` and `..` all escape it.
    if (token.startsWith("-")) {
      return true;
    }
    return !token.startsWith("/")
        && !token.startsWith("~")
        && !token.startsWith("\\")
        && !token.contains("..")
        && !hasWindowsDriveLetter(token);
  }

  private static boolean hasWindowsDriveLetter(String token) {
    return token.length() >= 2 && Character.isLetter(token.charAt(0)) && token.charAt(1) == ':';
  }

  /** Splits on whitespace, honouring single and double quotes, and unquotes each token. */
  private static List<String> tokenize(String command) {
    List<String> tokens = new java.util.ArrayList<>();
    StringBuilder current = new StringBuilder();
    char quote = 0;
    boolean started = false;

    for (int index = 0; index < command.length(); index++) {
      char character = command.charAt(index);
      if (quote != 0) {
        if (character == quote) {
          quote = 0;
        } else {
          current.append(character);
        }
        continue;
      }
      if (character == '\'' || character == '"') {
        quote = character;
        started = true;
        continue;
      }
      if (Character.isWhitespace(character)) {
        if (started) {
          tokens.add(current.toString().toLowerCase(Locale.ROOT));
          current.setLength(0);
          started = false;
        }
        continue;
      }
      current.append(character);
      started = true;
    }
    if (started) {
      tokens.add(current.toString().toLowerCase(Locale.ROOT));
    }
    return List.copyOf(tokens);
  }

  private static boolean containsAny(String command, List<String> markers) {
    return markers.stream().anyMatch(command::contains);
  }

  private static boolean hasOperator(String command) {
    return command.chars().mapToObj(value -> (char) value).anyMatch(SHELL_OPERATORS::contains);
  }
}
