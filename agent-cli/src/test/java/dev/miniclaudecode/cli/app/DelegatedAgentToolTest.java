package dev.miniclaudecode.cli.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class DelegatedAgentToolTest {

  @Test
  void rejectsSubagentOutputThatDoesNotMeetTheEvidenceContract() throws Exception {
    Method contract =
        DelegatedAgentTool.class.getDeclaredMethod("hasEvidenceContract", String.class);
    contract.setAccessible(true);

    assertThat(
            (boolean)
                contract.invoke(null, "Evidence\nFindings\nCommands/Test Results\nUncertainties"))
        .isTrue();
    assertThat((boolean) contract.invoke(null, "Evidence\nFindings\nUncertainties")).isFalse();
  }
}
