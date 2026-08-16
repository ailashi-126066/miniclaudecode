package dev.miniclaudecode.tools.process;

import dev.miniclaudecode.domain.approval.RiskLevel;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies a shell command string into a {@link RiskLevel}.
 *
 * <p>{@link RiskLevel#LOW} is the only class that {@code RunCommandTool} executes without asking
 * the user, so the LOW decision is a security boundary rather than a UI hint. Two disjoint routes
 * reach LOW, and both first require that the command contain no shell metacharacter — without that,
 * nothing below can stop a second command from being chained on.
 *
 * <p><strong>Route 1, provably read-only.</strong> The first token is an exact match (not a prefix)
 * for a known read-only program, and none of its arguments can make that program spawn a process,
 * write a file, or read outside the workspace. The argument check exists because several standard
 * read-only tools are general-purpose execution engines when given the right flag: {@code find
 * -exec}, {@code find -fprintf}, {@code rg --pre} and {@code git difftool --extcmd} all run
 * arbitrary programs.
 *
 * <p><strong>Route 2, verification.</strong> The command is a build, test, lint or format
 * invocation whose every argument is a recognised verification goal or a workspace-relative target.
 * This is a deliberate widening: running the project's own test suite is the inner loop of a coding
 * agent, and demanding a keystroke for every {@code mvn test} produced exactly the alarm fatigue
 * that makes users approve everything unread. The blast radius is the project's own build
 * definition, which the user already opted into by pointing the agent at the repository, and the
 * denylist, the destructive markers below and the OS sandbox all still apply. What does
 * <em>not</em> qualify is anything that turns a build driver into an arbitrary-code launcher:
 * {@code mvn exec:exec}, {@code npm install some-package} and {@code go run payload.go} all stay
 * MEDIUM.
 *
 * <p>Anything that satisfies neither route falls through to {@link RiskLevel#MEDIUM}, which
 * requires approval.
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
   * Programs that wreck the machine rather than the repository, matched as the command's own
   * program name. Matching them as substrings made {@code npm run format} and {@code gradle
   * reformat} CRITICAL, which is the false positive {@link CommandPolicy} had already avoided by
   * treating single-word deny entries as prefixes.
   */
  private static final Set<String> DESTRUCTIVE_PROGRAMS =
      Set.of("format", "mkfs", "diskpart", "shutdown", "reboot", "stop-computer", "clear-disk");

  /** Wrappers skipped when reading the program name, so {@code sudo shutdown} stays CRITICAL. */
  private static final Set<String> PRIVILEGE_WRAPPERS = Set.of("sudo", "doas", "runas");

  /**
   * Destructive constructs that only ever appear literally, so a substring match is exact enough.
   */
  private static final List<String> SYSTEM_DESTRUCTIVE_MARKERS = List.of(":(){", "dd if=");

  /**
   * Markers for irrecoverable deletions. Each entry keeps a trailing delimiter so that {@code rm
   * -rf /tmp/build} stays HIGH instead of being escalated to CRITICAL alongside {@code rm -rf /} —
   * over-escalation causes alarm fatigue, which is itself a security problem.
   */
  private static final List<String> ROOT_DELETION_MARKERS =
      List.of(
          "rm -rf / ",
          "rm -fr / ",
          "rm -r -f / ",
          "rm -rf /* ",
          "rm -rf ~ ",
          "rm -fr ~ ",
          "rm -rf * ");

  private static final List<String> CRITICAL_MARKERS =
      java.util.stream.Stream.concat(
              ROOT_DELETION_MARKERS.stream(), SYSTEM_DESTRUCTIVE_MARKERS.stream())
          .toList();

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

  /**
   * Build drivers that reach LOW only once a recognised verification goal is present. The goal
   * requirement is what separates {@code npm test} from {@code npm install some-package} and {@code
   * go test ./...} from {@code go run payload.go}.
   */
  private static final Set<String> BUILD_DRIVERS =
      Set.of("mvn", "mvnw", "gradle", "gradlew", "npm", "pnpm", "yarn", "cargo", "go", "dotnet");

  /** Drivers where {@code run} names the goal in the following token rather than being one. */
  private static final Set<String> NODE_PACKAGE_MANAGERS = Set.of("npm", "pnpm", "yarn");

  /** Runners that are themselves the verification goal, so a bare invocation qualifies. */
  private static final Set<String> VERIFICATION_RUNNERS =
      Set.of("pytest", "tox", "jest", "vitest", "ruff", "eslint", "prettier", "black", "gofmt");

  private static final Set<String> VERIFICATION_GOALS =
      Set.of(
          "test",
          "tests",
          "check",
          "verify",
          "build",
          "compile",
          "lint",
          "format",
          "fmt",
          "clean",
          "spotless",
          "typecheck");

  /**
   * Non-flag argument shapes accepted as targets rather than goals: {@code ./...} is Go's recursive
   * package wildcard and {@code .} is how most linters say "this directory". Anything else without
   * a path separator has to be a recognised goal, which is why a bare package or script name fails.
   */
  private static final Set<String> VERIFICATION_TARGETS = Set.of(".", "./...");

  public RiskLevel classify(String command) {
    if (command == null || command.isBlank()) {
      throw new IllegalArgumentException("command must not be blank");
    }

    String normalized = " " + normalizeForMarkers(command) + " ";
    if (containsAny(normalized, CRITICAL_MARKERS) || isDestructiveProgram(command)) {
      return RiskLevel.CRITICAL;
    }
    if (containsAny(normalized, HIGH_RISK_MARKERS)) {
      return RiskLevel.HIGH;
    }
    if (hasOperator(command)) {
      return RiskLevel.MEDIUM;
    }
    return isProvablyReadOnly(command) || isVerificationCommand(command)
        ? RiskLevel.LOW
        : RiskLevel.MEDIUM;
  }

  /**
   * Whether the command is a build, test, lint or format invocation.
   *
   * <p>Reached from {@link #classify} only after the destructive-marker and shell-operator checks,
   * so by this point the string is a single program with no way to chain a second one. Every
   * argument must then pass {@link #isSafeArgument} and be either a recognised verification goal or
   * one of the accepted targets, which keeps arbitrary Maven plugin goals, npm packages and script
   * paths out of the LOW class.
   *
   * <p>{@code RunCommandTool} also uses this to decide what an isolated worktree may run, so the
   * two questions — "safe enough to skip approval" and "verification only" — share one definition
   * instead of drifting apart as two separate lists.
   */
  static boolean isVerificationCommand(String command) {
    if (command == null || command.isBlank()) {
      return false;
    }
    List<String> tokens = tokenize(command);
    if (tokens.isEmpty()) {
      return false;
    }
    String program = verificationProgram(tokens.getFirst());
    boolean sawGoal = VERIFICATION_RUNNERS.contains(program);
    if (!sawGoal && !BUILD_DRIVERS.contains(program)) {
      return false;
    }

    int index = 1;
    // `npm run lint` spends two tokens on one goal; the goal itself still has to be recognised.
    if (tokens.size() > 2
        && NODE_PACKAGE_MANAGERS.contains(program)
        && "run".equals(tokens.get(1))) {
      index = 2;
    }
    for (; index < tokens.size(); index++) {
      String token = tokens.get(index);
      if (VERIFICATION_TARGETS.contains(token)) {
        continue;
      }
      if (!isSafeArgument(token)) {
        return false;
      }
      if (token.startsWith("-")) {
        continue;
      }
      if (isVerificationGoal(token)) {
        sawGoal = true;
      } else if (token.indexOf('/') < 0) {
        return false;
      }
    }
    return sawGoal;
  }

  /** Accepts Maven-style {@code plugin:goal} tokens when the goal half is a verification goal. */
  private static boolean isVerificationGoal(String token) {
    int separator = token.lastIndexOf(':');
    return VERIFICATION_GOALS.contains(separator < 0 ? token : token.substring(separator + 1));
  }

  /** Normalises wrapper spellings so {@code ./mvnw} and {@code .\mvnw.cmd} match {@code mvnw}. */
  private static String verificationProgram(String token) {
    String program = token.startsWith("./") || token.startsWith(".\\") ? token.substring(2) : token;
    for (String suffix : List.of(".cmd", ".bat", ".exe", ".ps1")) {
      if (program.endsWith(suffix)) {
        return program.substring(0, program.length() - suffix.length());
      }
    }
    return program;
  }

  private static boolean isDestructiveProgram(String command) {
    List<String> tokens = tokenize(command);
    int index = tokens.isEmpty() || !PRIVILEGE_WRAPPERS.contains(tokens.getFirst()) ? 0 : 1;
    return tokens.size() > index && DESTRUCTIVE_PROGRAMS.contains(tokens.get(index));
  }

  /**
   * The vocabulary {@link CommandPolicy} hard-refuses by default, so the denylist and the CRITICAL
   * class stay one list instead of two hand-copied ones. Single-word entries are program names; the
   * policy matches those as prefixes and the rest as substrings, which mirrors the split above.
   */
  public static List<String> systemDestructiveMarkers() {
    return java.util.stream.Stream.concat(
            DESTRUCTIVE_PROGRAMS.stream().sorted(), SYSTEM_DESTRUCTIVE_MARKERS.stream())
        .toList();
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
