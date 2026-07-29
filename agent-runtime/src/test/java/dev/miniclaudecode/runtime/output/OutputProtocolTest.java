package dev.miniclaudecode.runtime.output;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class OutputProtocolTest {
  @Test
  void jsonProtocolRecoversFencedOutputAndNormalizesFinalText() {
    OutputProtocol.Evaluation result =
        new JsonOutputProtocol()
            .evaluate(
                """
                ```json
                {"status":"completed","final":"Tests passed"}
                ```
                """);

    Assertions.assertThat(result.valid()).isTrue();
    Assertions.assertThat(result.finalText()).isEqualTo("Tests passed");
  }

  @Test
  void malformedJsonProducesAConcreteRepairInstruction() {
    OutputProtocol.Evaluation result = new JsonOutputProtocol().evaluate("{\"status\":\"done\"}");

    Assertions.assertThat(result.valid()).isFalse();
    Assertions.assertThat(result.repairInstruction())
        .contains("\"status\":\"completed\"")
        .contains("\"final\"");
  }
}
