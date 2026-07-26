package dev.miniclaudecode.tools.process;

import dev.miniclaudecode.tools.process.ShellSelector.Platform;
import java.util.List;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ShellSelectorTest {
  @Test
  void selectsPowerShellWithExplicitUtf8OnWindows() {
    List<String> command = ShellSelector.forOsName("Windows 11").command("Write-Output 'ok'");
    Assertions.assertThat(command)
        .startsWith(
            new String[] {
              "powershell.exe",
              "-NoLogo",
              "-NoProfile",
              "-NonInteractive",
              "-ExecutionPolicy",
              "Bypass",
              "-Command"
            });
    ((AbstractStringAssert)
            Assertions.assertThat(command.getLast()).contains(new CharSequence[] {"UTF8Encoding"}))
        .endsWith("Write-Output 'ok'");
  }

  @Test
  void selectsLoginCompatiblePosixShellElsewhere() {
    Assertions.assertThat(ShellSelector.forOsName("Linux").command("pwd"))
        .containsExactly(new String[] {"/bin/sh", "-lc", "pwd"});
    Assertions.assertThat(ShellSelector.forOsName("Mac OS X").platform()).isEqualTo(Platform.POSIX);
  }
}
