package dev.miniclaudecode.persistence.ledger;

import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.ToolExecutionRecord;
import dev.miniclaudecode.domain.tool.ToolExecutionRecord.Status;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolExecutionLedgerTest {
  @TempDir Path temporaryDirectory;

  @Test
  void replacesPendingWithCompletedAndSurvivesRestart() {
    Path ledgerFile = this.temporaryDirectory.resolve("tool-ledger.json");
    JsonToolExecutionLedger ledger = new JsonToolExecutionLedger(ledgerFile);
    ledger.save(record(Status.PENDING, Optional.empty()));
    ledger.save(record(Status.COMPLETED, Optional.of("results/call-1.txt")));
    JsonToolExecutionLedger restored = new JsonToolExecutionLedger(ledgerFile);
    Assertions.assertThat(restored.list()).hasSize(1);
    Assertions.assertThat(restored.find("call-1"))
        .get()
        .extracting(ToolExecutionRecord::status, ToolExecutionRecord::resultReference)
        .containsExactly(Status.COMPLETED, Optional.of("results/call-1.txt"));
  }

  private static ToolExecutionRecord record(Status status, Optional<String> resultReference) {
    return new ToolExecutionRecord(
        "call-1",
        "workspace.edit",
        status,
        RiskLevel.MEDIUM,
        Optional.of("before-sha256"),
        status == Status.COMPLETED ? Optional.of("after-sha256") : Optional.empty(),
        resultReference,
        Instant.parse("2026-07-21T00:00:00Z"));
  }
}
