package dev.miniclaudecode.planning;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record StepEvidence(
    List<String> toolResults,
    List<String> verificationResults,
    List<String> changedFiles,
    Optional<String> failureReason,
    Instant recordedAt)
    implements Serializable {
  public StepEvidence {
    toolResults = List.copyOf(Objects.requireNonNull(toolResults, "toolResults must not be null"));
    verificationResults =
        List.copyOf(
            Objects.requireNonNull(verificationResults, "verificationResults must not be null"));
    changedFiles =
        List.copyOf(Objects.requireNonNull(changedFiles, "changedFiles must not be null"));
    failureReason = Objects.requireNonNull(failureReason, "failureReason must not be null");
    Objects.requireNonNull(recordedAt, "recordedAt must not be null");
  }

  public static StepEvidence empty(Instant now) {
    return new StepEvidence(List.of(), List.of(), List.of(), Optional.empty(), now);
  }

  private Object writeReplace() {
    return new SerializedForm(
        toolResults, verificationResults, changedFiles, failureReason.orElse(null), recordedAt);
  }

  private record SerializedForm(
      List<String> toolResults,
      List<String> verificationResults,
      List<String> changedFiles,
      String failureReason,
      Instant recordedAt)
      implements Serializable {
    private Object readResolve() {
      return new StepEvidence(
          toolResults,
          verificationResults,
          changedFiles,
          Optional.ofNullable(failureReason),
          recordedAt);
    }
  }
}
