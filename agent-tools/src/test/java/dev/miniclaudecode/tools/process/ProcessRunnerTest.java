package dev.miniclaudecode.tools.process;

import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.tools.process.ProcessRunner.ProcessRequest;
import dev.miniclaudecode.tools.process.ProcessRunner.ProcessResult;
import dev.miniclaudecode.tools.process.ShellSelector.Platform;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessRunnerTest {
  @TempDir Path temporaryDirectory;

  @Test
  void capturesUtf8OutputInTheRequestedWorkingDirectory() {
    ShellSelector selector = ShellSelector.system();
    ProcessRunner runner = new ProcessRunner(selector);
    String command =
        selector.platform() == Platform.WINDOWS
            ? "Write-Output '你好'; Write-Output (Get-Location).Path"
            : "printf '你好\\n'; pwd";
    ProcessResult result =
        runner.run(this.request(command, 4096, Duration.ofSeconds(5L)), new CancellationToken());
    Assertions.assertThat(result.exitCode()).isZero();
    Assertions.assertThat(result.stdout())
        .contains(new CharSequence[] {"你好", this.temporaryDirectory.toString()});
    Assertions.assertThat(result.timedOut()).isFalse();
  }

  @Test
  void appliesACombinedOutputLimitWithoutDeadlocking() {
    ShellSelector selector = ShellSelector.system();
    ProcessRunner runner = new ProcessRunner(selector);
    String command =
        selector.platform() == Platform.WINDOWS
            ? "Write-Output ('x' * 10000)"
            : "yes x | head -c 10000";
    ProcessResult result =
        runner.run(this.request(command, 128, Duration.ofSeconds(5L)), new CancellationToken());
    Assertions.assertThat(result.truncated()).isTrue();
    Assertions.assertThat(result.stdout().getBytes(StandardCharsets.UTF_8)).hasSize(128);
  }

  @Test
  void terminatesACommandAtItsDeadline() {
    ShellSelector selector = ShellSelector.system();
    ProcessRunner runner = new ProcessRunner(selector);
    String command =
        selector.platform() == Platform.WINDOWS ? "Start-Sleep -Seconds 20" : "sleep 20";
    ProcessResult result =
        runner.run(this.request(command, 128, Duration.ofMillis(150L)), new CancellationToken());
    Assertions.assertThat(result.timedOut()).isTrue();
    Assertions.assertThat(result.cancelled()).isFalse();
  }

  private ProcessRequest request(String command, int maximumBytes, Duration timeout) {
    return new ProcessRequest(command, this.temporaryDirectory, timeout, maximumBytes, true);
  }
}
