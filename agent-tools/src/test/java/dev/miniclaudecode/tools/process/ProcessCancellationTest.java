package dev.miniclaudecode.tools.process;

import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.tools.process.ProcessRunner.ProcessRequest;
import dev.miniclaudecode.tools.process.ProcessRunner.ProcessResult;
import dev.miniclaudecode.tools.process.ShellSelector.Platform;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessCancellationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void cancellationTerminatesTheRunningShell() throws Exception {
    ShellSelector selector = ShellSelector.system();
    CountDownLatch started = new CountDownLatch(1);
    AtomicReference<Process> process = new AtomicReference<>();
    ProcessRunner runner =
        new ProcessRunner(
            selector,
            running -> {
              process.set(running);
              started.countDown();
            });
    CancellationToken token = new CancellationToken();
    ProcessRequest request =
        new ProcessRequest(
            slowCommand(selector), this.temporaryDirectory, Duration.ofSeconds(20L), 1024, true);
    CompletableFuture<ProcessResult> result =
        CompletableFuture.supplyAsync(() -> runner.run(request, token));
    Assertions.assertThat(started.await(5L, TimeUnit.SECONDS)).isTrue();
    token.cancel();
    Assertions.assertThat(result.get(5L, TimeUnit.SECONDS).cancelled()).isTrue();
    Assertions.assertThat(process.get().isAlive()).isFalse();
  }

  private static String slowCommand(ShellSelector selector) {
    return selector.platform() == Platform.WINDOWS ? "Start-Sleep -Seconds 20" : "sleep 20";
  }
}
