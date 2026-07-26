package dev.miniclaudecode.tools.process;

import dev.miniclaudecode.tools.process.CommandSandbox.Backend;
import dev.miniclaudecode.tools.process.CommandSandbox.Policy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandSandboxTest {
  @TempDir Path temporaryDirectory;

  private static final List<String> SHELL = List.of("/bin/sh", "-lc", "mvn -q verify");

  @Test
  void offPolicyPassesTheCommandThroughUnchanged() {
    CommandSandbox sandbox = CommandSandbox.detect(Policy.OFF, Path.of("."), "Linux", "/usr/bin");
    Assertions.assertThat(sandbox.backend()).isEqualTo(Backend.NONE);
    Assertions.assertThat(sandbox.wrap(SHELL, Path.of("."))).isEqualTo(SHELL);
    Assertions.assertThat(sandbox.describe()).isEqualTo("off");
  }

  @Test
  void missingBackendDegradesVisiblyUnderAutoPolicy() {
    CommandSandbox sandbox = CommandSandbox.detect(Policy.AUTO, Path.of("."), "Windows 11", null);
    Assertions.assertThat(sandbox.backend()).isEqualTo(Backend.NONE);
    Assertions.assertThat(sandbox.wrap(SHELL, Path.of("."))).isEqualTo(SHELL);
    Assertions.assertThat(sandbox.describe()).contains("unsandboxed");
  }

  @Test
  void requiredPolicyWithoutBackendRefusesToExecuteWithGuidance() {
    CommandSandbox sandbox =
        CommandSandbox.detect(Policy.REQUIRED, Path.of("."), "Windows 11", null);
    Assertions.assertThatThrownBy(() -> sandbox.wrap(SHELL, Path.of(".")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("required")
        .hasMessageContaining("MINICLAUDE_SANDBOX=auto");
    // The approval prompt must not promise "run unsandboxed" when wrap() will refuse.
    Assertions.assertThat(sandbox.describe()).contains("refused");
  }

  @Test
  void requiredPolicyRefusalSurfacesThroughProcessRunner() throws Exception {
    // Guards the one line that makes the feature real: ProcessRunner must route the argv through
    // sandbox.wrap. If that call is ever dropped, this test fails while everything else stays
    // green.
    CommandSandbox sandbox =
        CommandSandbox.detect(Policy.REQUIRED, this.temporaryDirectory, "Windows 11", null);
    ProcessRunner runner = new ProcessRunner(ShellSelector.system(), sandbox);
    ProcessRunner.ProcessRequest request =
        new ProcessRunner.ProcessRequest(
            "echo hello", this.temporaryDirectory, java.time.Duration.ofSeconds(5L), 65536, true);
    Assertions.assertThatThrownBy(
            () -> runner.run(request, new dev.miniclaudecode.domain.runtime.CancellationToken()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("required");
  }

  @Test
  void linuxHomeBindsMountHomeReadOnlyAndOnlyExistingCachesWritable() throws Exception {
    Path home = Files.createDirectory(this.temporaryDirectory.resolve("home"));
    Files.createDirectory(home.resolve(".m2"));
    // .npm and .gradle deliberately absent
    Assertions.assertThat(CommandSandbox.linuxHomeBinds(home))
        .containsExactly(
            "--ro-bind",
            home.toString(),
            home.toString(),
            "--bind",
            home.resolve(".m2").toString(),
            home.resolve(".m2").toString());
    Assertions.assertThat(CommandSandbox.linuxHomeBinds(this.temporaryDirectory.resolve("missing")))
        .isEmpty();
  }

  @Test
  void seatbeltProfileAllowsTempDirAndToolchainCaches() {
    Path workspace = this.temporaryDirectory.toAbsolutePath().normalize();
    String profile = CommandSandbox.seatbeltProfile(workspace, "/var/folders/ab/T", "/Users/dev");
    Assertions.assertThat(profile)
        .contains("(subpath \"" + CommandSandbox.sbplEscape(workspace.toString()) + "\")")
        .contains("/var/folders/ab/T")
        .contains(CommandSandbox.sbplEscape(Path.of("/Users/dev", ".m2").toString()))
        .contains(CommandSandbox.sbplEscape(Path.of("/Users/dev", ".npm").toString()));
    // Absent TMPDIR/home degrade to the fixed allowlist instead of emitting broken subpaths.
    Assertions.assertThat(CommandSandbox.seatbeltProfile(workspace, null, ""))
        .doesNotContain("(subpath \"\")");
  }

  @Test
  void bubblewrapArgvIsolatesEverythingExceptTheWorkspace() {
    Path workspace = this.temporaryDirectory.toAbsolutePath().normalize();
    Path workingDirectory = workspace.resolve("module");
    CommandSandbox sandbox =
        new CommandSandbox(
            Policy.AUTO, Backend.BUBBLEWRAP, workspace, List.of("--ro-bind", "/usr", "/usr"));
    List<String> argv = sandbox.wrap(SHELL, workingDirectory);
    Assertions.assertThat(argv)
        .startsWith(
            "bwrap",
            "--die-with-parent",
            "--unshare-pid",
            "--unshare-uts",
            "--unshare-ipc",
            "--new-session")
        .containsSequence("--ro-bind", "/usr", "/usr")
        .containsSequence("--proc", "/proc")
        .containsSequence("--tmpfs", "/tmp")
        .containsSequence("--bind", workspace.toString(), workspace.toString())
        .containsSequence("--chdir", workingDirectory.toString())
        .containsSequence("--", "/bin/sh", "-lc", "mvn -q verify");
    // Network deliberately allowed: no --unshare-net, or mvn/npm installs would fail and the
    // sandbox would get switched off wholesale.
    Assertions.assertThat(argv).doesNotContain("--unshare-net");
  }

  @Test
  void linuxSystemBindsSkipMissingDirectories() throws Exception {
    Path root = Files.createDirectory(this.temporaryDirectory.resolve("fakeroot"));
    Files.createDirectory(root.resolve("usr"));
    Files.createDirectory(root.resolve("etc"));
    List<String> binds = CommandSandbox.linuxSystemBinds(root, List.of("usr", "lib64", "etc"));
    Assertions.assertThat(binds)
        .containsExactly("--ro-bind", "/usr", "/usr", "--ro-bind", "/etc", "/etc");
  }

  @Test
  void linuxSystemBindsRecreateMergedUsrSymlinks() throws Exception {
    Path root = Files.createDirectory(this.temporaryDirectory.resolve("mergedusr"));
    Files.createDirectory(root.resolve("usr"));
    try {
      // Symlink creation needs privileges on Windows; skip there rather than fail.
      Files.createSymbolicLink(root.resolve("bin"), Path.of("usr/bin"));
    } catch (IOException | UnsupportedOperationException | SecurityException exception) {
      Assumptions.abort("symbolic links are not creatable in this environment");
    }
    List<String> binds = CommandSandbox.linuxSystemBinds(root, List.of("usr", "bin"));
    Assertions.assertThat(binds)
        .containsExactly(
            "--ro-bind", "/usr", "/usr", "--symlink", Path.of("usr/bin").toString(), "/bin");
  }

  @Test
  void seatbeltProfileDeniesWritesOutsideTheWorkspace() {
    Path workspace = this.temporaryDirectory.toAbsolutePath().normalize();
    CommandSandbox sandbox =
        new CommandSandbox(Policy.AUTO, Backend.SANDBOX_EXEC, workspace, List.of());
    List<String> argv = sandbox.wrap(SHELL, workspace);
    Assertions.assertThat(argv.get(0)).isEqualTo("/usr/bin/sandbox-exec");
    Assertions.assertThat(argv.get(1)).isEqualTo("-p");
    String profile = argv.get(2);
    Assertions.assertThat(profile)
        .contains("(deny file-write*)")
        .contains("(allow default)")
        .contains("(subpath \"" + CommandSandbox.sbplEscape(workspace.toString()) + "\")")
        .contains("/private/tmp");
    Assertions.assertThat(argv.subList(3, argv.size())).isEqualTo(SHELL);
  }

  @Test
  void seatbeltPathEscapingNeutralizesQuotesAndBackslashes() {
    // A quote in the workspace path could otherwise break out of the SBPL string literal and
    // rewrite the policy. (Tested on the raw string: Windows paths cannot even contain quotes.)
    Assertions.assertThat(CommandSandbox.sbplEscape("my \"quoted\" C:\\path"))
        .isEqualTo("my \\\"quoted\\\" C:\\\\path");
  }

  @Test
  void policyParsingAcceptsTheThreeDocumentedValuesOnly() {
    Assertions.assertThat(Policy.parse("auto")).isEqualTo(Policy.AUTO);
    Assertions.assertThat(Policy.parse("REQUIRED")).isEqualTo(Policy.REQUIRED);
    Assertions.assertThat(Policy.parse(" off ")).isEqualTo(Policy.OFF);
    Assertions.assertThat(Policy.parse(null)).isEqualTo(Policy.AUTO);
    Assertions.assertThatThrownBy(() -> Policy.parse("strict"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("strict");
  }
}
