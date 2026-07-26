package dev.miniclaudecode.tools.process;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Best-effort OS-level sandbox for {@code shell:run}.
 *
 * <p>Classification + approval decide <em>whether</em> a command runs; this class limits the blast
 * radius <em>when</em> it runs. Backends: bubblewrap ({@code bwrap}) on Linux, {@code sandbox-exec}
 * on macOS (deprecated by Apple but shipped with the OS). Windows has no pure-Java isolation
 * primitive (Job Objects need JNI), so it degrades explicitly.
 *
 * <p>Two deliberate decisions. Network stays allowed: {@code mvn}/{@code npm install} need it, and
 * a sandbox people switch off is worth less than a weaker one they keep on — filesystem isolation
 * is the main win. And a missing backend degrades instead of refusing (the tool staying usable
 * beats a hard guarantee nobody opted into), but the degradation is visible: {@link #describe()}
 * feeds the approval prompt, and the {@code required} policy turns degradation into a hard error
 * for those who want the guarantee.
 */
public final class CommandSandbox {

  /**
   * Directories offered read-only to the Linux sandbox when they exist on the host. {@code var} is
   * included because resolvconf-style hosts route {@code /etc/resolv.conf} through {@code
   * /var/run}; without it DNS dangles inside the sandbox despite "network allowed".
   */
  private static final List<String> LINUX_SYSTEM_PATHS =
      List.of("usr", "bin", "sbin", "lib", "lib64", "lib32", "etc", "opt", "run", "var");

  /** Toolchain caches offered read-write so builds inside the sandbox stay incremental. */
  private static final List<String> LINUX_WRITABLE_HOME_CACHES = List.of(".m2", ".npm", ".gradle");

  private final Policy policy;
  private final Backend backend;
  private final Path workspace;
  private final List<String> systemBinds;

  CommandSandbox(Policy policy, Backend backend, Path workspace, List<String> systemBinds) {
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
    this.systemBinds = List.copyOf(systemBinds);
  }

  /** No sandboxing, silently. For contexts that opted out or never configured one. */
  public static CommandSandbox none() {
    return new CommandSandbox(Policy.OFF, Backend.NONE, Path.of("."), List.of());
  }

  public static CommandSandbox detect(Policy policy, Path workspace) {
    return detect(policy, workspace, System.getProperty("os.name", ""), System.getenv("PATH"));
  }

  static CommandSandbox detect(Policy policy, Path workspace, String osName, String pathVariable) {
    Objects.requireNonNull(policy, "policy must not be null");
    Path root = workspace.toAbsolutePath().normalize();
    if (policy == Policy.OFF) {
      return new CommandSandbox(policy, Backend.NONE, root, List.of());
    }
    String os = osName.toLowerCase(Locale.ROOT);
    if (os.contains("linux") && executableOnPath("bwrap", pathVariable)) {
      List<String> binds = new ArrayList<>();
      binds.addAll(linuxSystemBinds(Path.of("/"), LINUX_SYSTEM_PATHS));
      binds.addAll(linuxHomeBinds(Path.of(System.getProperty("user.home", ""))));
      return new CommandSandbox(policy, Backend.BUBBLEWRAP, root, binds);
    }
    if (os.contains("mac") && Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))) {
      return new CommandSandbox(policy, Backend.SANDBOX_EXEC, root, List.of());
    }
    return new CommandSandbox(policy, Backend.NONE, root, List.of());
  }

  /**
   * Wraps the shell argv in the sandbox launcher, or returns it unchanged when no backend exists
   * and the policy tolerates that. {@code required} without a backend fails here — per command,
   * with a message that says how to proceed — instead of poisoning CLI startup.
   */
  public List<String> wrap(List<String> command, Path workingDirectory) {
    Objects.requireNonNull(command, "command must not be null");
    Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
    return switch (this.backend) {
      case NONE -> {
        if (this.policy == Policy.REQUIRED) {
          throw new IllegalStateException(
              "sandbox policy is 'required' but no backend is available on this platform"
                  + " (bwrap on Linux, sandbox-exec on macOS);"
                  + " set MINICLAUDE_SANDBOX=auto to allow unsandboxed execution");
        }
        yield command;
      }
      case BUBBLEWRAP -> {
        List<String> argv = new ArrayList<>();
        argv.add("bwrap");
        // --new-session is not optional: without it the child keeps the controlling terminal and
        // can inject keystrokes into the parent shell via TIOCSTI.
        argv.addAll(
            List.of(
                "--die-with-parent",
                "--unshare-pid",
                "--unshare-uts",
                "--unshare-ipc",
                "--new-session"));
        argv.addAll(this.systemBinds);
        argv.addAll(List.of("--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp"));
        argv.addAll(List.of("--bind", this.workspace.toString(), this.workspace.toString()));
        argv.addAll(List.of("--chdir", workingDirectory.toString()));
        argv.add("--");
        argv.addAll(command);
        yield List.copyOf(argv);
      }
      case SANDBOX_EXEC -> {
        List<String> argv = new ArrayList<>();
        argv.addAll(List.of("/usr/bin/sandbox-exec", "-p", this.seatbeltProfile()));
        argv.addAll(command);
        yield List.copyOf(argv);
      }
    };
  }

  public String describe() {
    return switch (this.backend) {
      case BUBBLEWRAP ->
          "bubblewrap: writes confined to workspace and toolchain caches, network allowed";
      case SANDBOX_EXEC ->
          "sandbox-exec: writes confined to workspace, temp and toolchain caches, network allowed";
      case NONE -> {
        if (this.policy == Policy.OFF) {
          yield "off";
        }
        // With REQUIRED the wrap() call refuses instead of degrading; the prompt must not claim
        // commands "run unsandboxed" when they will in fact be rejected.
        yield this.policy == Policy.REQUIRED
            ? "required but unavailable on this platform - commands will be refused"
            : "unavailable on this platform - commands run unsandboxed";
      }
    };
  }

  public Backend backend() {
    return this.backend;
  }

  /**
   * Seatbelt policy: allow everything, then deny writes, then re-allow writes inside the workspace
   * and scratch areas. Read access stays open on purpose — builds read toolchains from all over the
   * disk, and a read-blocking profile is exactly the kind users disable.
   */
  private String seatbeltProfile() {
    return seatbeltProfile(
        this.workspace, System.getenv("TMPDIR"), System.getProperty("user.home", ""));
  }

  /**
   * The write allowlist must cover what the tools this sandbox exists for actually touch: on macOS
   * {@code java.io.tmpdir}/{@code $TMPDIR} is a per-user directory under {@code
   * /private/var/folders} (NOT {@code /private/var/tmp}), and mvn/npm write their caches under the
   * home directory — omitting either made the very builds the network stays open for fail.
   */
  static String seatbeltProfile(Path workspace, String tmpdirVariable, String userHome) {
    StringBuilder writable =
        new StringBuilder()
            .append("(subpath \"")
            .append(sbplEscape(workspace.toString()))
            .append("\") (subpath \"/private/tmp\") (subpath \"/private/var/tmp\")")
            .append(" (subpath \"/dev\")");
    if (tmpdirVariable != null && !tmpdirVariable.isBlank()) {
      writable.append(" (subpath \"").append(sbplEscape(realOrRaw(tmpdirVariable))).append("\")");
    }
    if (userHome != null && !userHome.isBlank()) {
      for (String cache : List.of(".m2", ".npm")) {
        writable
            .append(" (subpath \"")
            .append(sbplEscape(Path.of(userHome, cache).toString()))
            .append("\")");
      }
    }
    return "(version 1)\n"
        + "(allow default)\n"
        + "(deny file-write*)\n"
        + "(allow file-write* "
        + writable
        + ")";
  }

  /** TMPDIR is a symlink into /private on macOS; seatbelt subpaths match the resolved form. */
  private static String realOrRaw(String path) {
    try {
      return Path.of(path).toRealPath().toString();
    } catch (IOException | RuntimeException ignored) {
      return path;
    }
  }

  static String sbplEscape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /**
   * Read-only binds for the Linux system directories that actually exist. On merged-usr systems
   * {@code /bin} and {@code /lib*} are symlinks into {@code /usr}; bwrap must recreate those as
   * symlinks ({@code --ro-bind} on the link would fail or bind the wrong node), so each candidate
   * is probed: symlink → {@code --symlink target /name}, real directory → {@code --ro-bind}. The
   * root prefix is a parameter purely so tests can model a fake system layout.
   */
  static List<String> linuxSystemBinds(Path root, List<String> names) {
    List<String> arguments = new ArrayList<>();
    for (String name : names) {
      Path candidate = root.resolve(name);
      if (Files.isSymbolicLink(candidate)) {
        try {
          arguments.add("--symlink");
          arguments.add(Files.readSymbolicLink(candidate).toString());
          arguments.add("/" + name);
        } catch (IOException exception) {
          throw new UncheckedIOException("cannot resolve system symlink: " + candidate, exception);
        }
      } else if (Files.isDirectory(candidate)) {
        arguments.add("--ro-bind");
        arguments.add("/" + name);
        arguments.add("/" + name);
      }
    }
    return List.copyOf(arguments);
  }

  /**
   * Home-directory binds for the Linux sandbox. Without them {@code $HOME} does not exist inside
   * bwrap's fresh tmpfs root: Maven re-downloads its whole repository per command, git loses its
   * identity, and home-installed toolchains (sdkman, nvm) vanish. The home is bound read-only —
   * writes stay confined — and only well-known build caches are re-bound writable, an explicit,
   * documented widening. Bind order matters: the workspace rw-bind comes later in the argv, so a
   * workspace living under the home directory still overlays writable.
   */
  static List<String> linuxHomeBinds(Path home) {
    List<String> arguments = new ArrayList<>();
    if (home.toString().isEmpty() || !Files.isDirectory(home)) {
      return List.of();
    }
    arguments.add("--ro-bind");
    arguments.add(home.toString());
    arguments.add(home.toString());
    for (String cache : LINUX_WRITABLE_HOME_CACHES) {
      Path candidate = home.resolve(cache);
      if (Files.isDirectory(candidate)) {
        arguments.add("--bind");
        arguments.add(candidate.toString());
        arguments.add(candidate.toString());
      }
    }
    return List.copyOf(arguments);
  }

  private static boolean executableOnPath(String name, String pathVariable) {
    if (pathVariable == null || pathVariable.isBlank()) {
      return false;
    }
    for (String entry : pathVariable.split(java.io.File.pathSeparator)) {
      if (!entry.isBlank() && Files.isExecutable(Path.of(entry.trim(), name))) {
        return true;
      }
    }
    return false;
  }

  public enum Backend {
    BUBBLEWRAP,
    SANDBOX_EXEC,
    NONE
  }

  public enum Policy {
    AUTO,
    REQUIRED,
    OFF;

    public static Policy parse(String value) {
      if (value == null || value.isBlank()) {
        return AUTO;
      }
      return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "auto" -> AUTO;
        case "required" -> REQUIRED;
        case "off" -> OFF;
        default ->
            throw new IllegalArgumentException(
                "unsupported sandbox policy: " + value + " (expected auto, required, or off)");
      };
    }
  }
}
