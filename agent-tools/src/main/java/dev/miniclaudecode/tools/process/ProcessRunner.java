package dev.miniclaudecode.tools.process;

import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.domain.runtime.CancellationToken.Registration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

public final class ProcessRunner {
  private static final Duration TERMINATION_GRACE = Duration.ofMillis(300L);
  private static final Duration STREAM_DRAIN_TIMEOUT = Duration.ofSeconds(2L);
  private final ShellSelector shellSelector;
  private final CommandSandbox sandbox;
  private final Consumer<Process> startedHook;

  public ProcessRunner(ShellSelector shellSelector) {
    this(shellSelector, CommandSandbox.none(), process -> {});
  }

  public ProcessRunner(ShellSelector shellSelector, CommandSandbox sandbox) {
    this(shellSelector, sandbox, process -> {});
  }

  ProcessRunner(ShellSelector shellSelector, Consumer<Process> startedHook) {
    this(shellSelector, CommandSandbox.none(), startedHook);
  }

  ProcessRunner(
      ShellSelector shellSelector, CommandSandbox sandbox, Consumer<Process> startedHook) {
    this.shellSelector = Objects.requireNonNull(shellSelector, "shellSelector must not be null");
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox must not be null");
    this.startedHook = Objects.requireNonNull(startedHook, "startedHook must not be null");
  }

  public String sandboxDescription() {
    return this.sandbox.describe();
  }

  public ProcessRunner.ProcessResult run(
      ProcessRunner.ProcessRequest request, CancellationToken cancellationToken) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(cancellationToken, "cancellationToken must not be null");
    if (cancellationToken.isCancellationRequested()) {
      return ProcessRunner.ProcessResult.cancelledBeforeStart();
    } else {
      Instant startedAt = Instant.now();

      Process process;
      try {
        ProcessBuilder builder =
            new ProcessBuilder(
                this.sandbox.wrap(
                    this.shellSelector.command(request.command()), request.workingDirectory()));
        builder.directory(request.workingDirectory().toFile());
        builder.redirectErrorStream(request.mergeErrorStream());
        process = builder.start();
        process.getOutputStream().close();
        this.startedHook.accept(process);
      } catch (IOException var20) {
        throw new IllegalStateException("failed to start command", var20);
      }

      ProcessRunner.OutputBudget budget = new ProcessRunner.OutputBudget(request.maxOutputBytes());
      ProcessRunner.StreamCapture standardOutput = new ProcessRunner.StreamCapture(budget);
      ProcessRunner.StreamCapture standardError = new ProcessRunner.StreamCapture(budget);
      AtomicBoolean terminationStarted = new AtomicBoolean();

      ProcessRunner.ProcessResult var16;
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Registration ignored =
            cancellationToken.onCancel(() -> terminateTreeOnce(process, terminationStarted));

        try {
          Future<?> stdout = executor.submit(() -> standardOutput.read(process.getInputStream()));
          Future<?> stderr =
              request.mergeErrorStream()
                  ? null
                  : executor.submit(() -> standardError.read(process.getErrorStream()));

          boolean finished;
          try {
            finished = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
          } catch (InterruptedException var19) {
            Thread.currentThread().interrupt();
            cancellationToken.cancel();
            finished = false;
          }

          boolean timedOut = !finished && !cancellationToken.isCancellationRequested();
          if (!finished) {
            terminateTreeOnce(process, terminationStarted);
            waitForTermination(process);
          }

          drain(stdout);
          if (stderr != null) {
            drain(stderr);
          }

          int exitCode = process.isAlive() ? -1 : process.exitValue();
          var16 =
              new ProcessRunner.ProcessResult(
                  exitCode,
                  standardOutput.text(),
                  standardError.text(),
                  timedOut,
                  cancellationToken.isCancellationRequested(),
                  budget.truncated(),
                  Duration.between(startedAt, Instant.now()));
        } catch (Throwable var21) {
          if (ignored != null) {
            try {
              ignored.close();
            } catch (Throwable var18) {
              var21.addSuppressed(var18);
            }
          }

          throw var21;
        }

        if (ignored != null) {
          ignored.close();
        }
      }

      return var16;
    }
  }

  private static void drain(Future<?> capture) {
    try {
      capture.get(STREAM_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException var2) {
      Thread.currentThread().interrupt();
      capture.cancel(true);
    } catch (ExecutionException var3) {
      throw new IllegalStateException("failed to capture command output", var3.getCause());
    } catch (TimeoutException var4) {
      capture.cancel(true);
    }
  }

  private static void terminateTreeOnce(Process process, AtomicBoolean terminationStarted) {
    if (terminationStarted.compareAndSet(false, true)) {
      List<ProcessHandle> descendants = process.descendants().toList().reversed();
      descendants.forEach(ProcessHandle::destroy);
      process.destroy();
      long deadline = System.nanoTime() + TERMINATION_GRACE.toNanos();

      while (isAnyAlive(process, descendants) && System.nanoTime() < deadline) {
        LockSupport.parkNanos(Duration.ofMillis(10L).toNanos());
      }

      descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  private static boolean isAnyAlive(Process process, List<ProcessHandle> descendants) {
    return process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive);
  }

  private static void waitForTermination(Process process) {
    try {
      process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException var2) {
      Thread.currentThread().interrupt();
    }
  }

  private static final class OutputBudget {
    private final AtomicInteger remaining;
    private final AtomicBoolean truncated = new AtomicBoolean();

    private OutputBudget(int maximumBytes) {
      this.remaining = new AtomicInteger(maximumBytes);
    }

    private int claim(int requested) {
      int available;
      int accepted;
      do {
        available = this.remaining.get();
        if (available <= 0) {
          this.truncated.set(true);
          return 0;
        }

        accepted = Math.min(requested, available);
      } while (!this.remaining.compareAndSet(available, available - accepted));

      if (accepted < requested) {
        this.truncated.set(true);
      }

      return accepted;
    }

    private boolean truncated() {
      return this.truncated.get();
    }
  }

  public static record ProcessRequest(
      String command,
      Path workingDirectory,
      Duration timeout,
      int maxOutputBytes,
      boolean mergeErrorStream) {
    public ProcessRequest(
        String command,
        Path workingDirectory,
        Duration timeout,
        int maxOutputBytes,
        boolean mergeErrorStream) {
      if (command != null && !command.isBlank()) {
        workingDirectory =
            Objects.requireNonNull(workingDirectory, "workingDirectory must not be null")
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(workingDirectory)) {
          throw new IllegalArgumentException("workingDirectory must be an existing directory");
        } else {
          Objects.requireNonNull(timeout, "timeout must not be null");
          if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
          } else if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
          } else {
            this.command = command;
            this.workingDirectory = workingDirectory;
            this.timeout = timeout;
            this.maxOutputBytes = maxOutputBytes;
            this.mergeErrorStream = mergeErrorStream;
          }
        }
      } else {
        throw new IllegalArgumentException("command must not be blank");
      }
    }
  }

  public static record ProcessResult(
      int exitCode,
      String stdout,
      String stderr,
      boolean timedOut,
      boolean cancelled,
      boolean truncated,
      Duration duration) {
    public ProcessResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        boolean cancelled,
        boolean truncated,
        Duration duration) {
      Objects.requireNonNull(stdout, "stdout must not be null");
      Objects.requireNonNull(stderr, "stderr must not be null");
      Objects.requireNonNull(duration, "duration must not be null");
      this.exitCode = exitCode;
      this.stdout = stdout;
      this.stderr = stderr;
      this.timedOut = timedOut;
      this.cancelled = cancelled;
      this.truncated = truncated;
      this.duration = duration;
    }

    private static ProcessRunner.ProcessResult cancelledBeforeStart() {
      return new ProcessRunner.ProcessResult(-1, "", "", false, true, false, Duration.ZERO);
    }
  }

  private static final class StreamCapture {
    private final ProcessRunner.OutputBudget budget;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private StreamCapture(ProcessRunner.OutputBudget budget) {
      this.budget = budget;
    }

    private void read(InputStream input) {
      byte[] buffer = new byte[8192];

      try (InputStream stream = input) {
        int count;
        while ((count = stream.read(buffer)) >= 0) {
          int accepted = this.budget.claim(count);
          if (accepted > 0) {
            this.output.write(buffer, 0, accepted);
          }
        }
      } catch (IOException var8) {
        throw new IllegalStateException("failed to read process stream", var8);
      }
    }

    private String text() {
      return this.output.toString(StandardCharsets.UTF_8);
    }
  }
}
