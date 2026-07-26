package dev.miniclaudecode.domain.tool;

import java.util.List;
import java.util.Optional;

public interface ToolExecutionLedger {
  Optional<ToolExecutionRecord> find(String toolCallId);

  List<ToolExecutionRecord> list();

  void save(ToolExecutionRecord record);
}
