package dev.miniclaudecode.runtime.recovery;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.planning.PlanStep;
import dev.miniclaudecode.runtime.recovery.RecoveryAttachment.ReadFile;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Captures non-conversational execution state before old messages are replaced by a summary. */
public final class RecoveryAttachmentService {
  public static final int DEFAULT_PROMPT_BUDGET = 16_384;
  private static final Pattern RESULT_REFERENCE = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final int MAX_ITEMS = 32;

  private final Clock clock;

  public RecoveryAttachmentService() {
    this(Clock.systemUTC());
  }

  public RecoveryAttachmentService(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  public RecoveryAttachment capture(MiniClaudeState state) {
    Objects.requireNonNull(state, "state must not be null");
    RecoveryAttachment previous = state.recoveryAttachment().orElse(null);
    String boundary = "compact-" + (state.compactionCount() + 1) + "-" + UUID.randomUUID();

    LinkedHashSet<String> modified = merge(previous == null ? List.of() : previous.modifiedFiles());
    LinkedHashSet<String> verification =
        merge(previous == null ? List.of() : previous.verifications());
    LinkedHashSet<String> skills = merge(previous == null ? List.of() : previous.loadedSkills());
    LinkedHashSet<String> references =
        merge(previous == null ? List.of() : previous.toolResultReferences());
    LinkedHashSet<String> background =
        merge(previous == null ? List.of() : previous.backgroundAgents());
    background.addAll(listAttribute(state, "backgroundAgents"));
    LinkedHashSet<String> teams = merge(previous == null ? List.of() : previous.teamTasks());
    teams.addAll(listAttribute(state, "teamTasks"));
    LinkedHashMap<String, ReadFile> reads = new LinkedHashMap<>();
    if (previous != null)
      previous.readFiles().forEach(file -> reads.put(file.path(), refresh(file, state)));

    Map<String, String> toolNames = toolNames(state.messages());
    for (AgentMessage message : state.messages()) {
      if (!(message instanceof ToolMessage tool)) continue;
      collectReferences(tool.text(), references);
      switch (tool.qualifiedToolName()) {
        case "workspace:write", "workspace:edit", "workspace:apply_patch" ->
            modified.add(abbreviate(tool.text(), 240));
        case "shell:run" -> verification.add(abbreviate(tool.text(), 320));
        default -> {
          if (tool.qualifiedToolName().startsWith("skill:")
              || tool.qualifiedToolName().startsWith("skills:")) {
            skills.add(abbreviate(tool.text(), 160));
          }
        }
      }
    }
    for (ToolResult result : state.toolResults()) {
      result.resultReference().ifPresent(references::add);
      addMetadataList(result, "backgroundAgents", background);
      addMetadataList(result, "teamTasks", teams);
      if ("workspace:read".equals(toolNames.get(result.toolCallId()))) {
        readFile(result, state).ifPresent(file -> reads.put(file.path(), file));
      }
    }

    Map<String, List<String>> evidence = new LinkedHashMap<>();
    state
        .plan()
        .ifPresent(
            plan ->
                plan.steps().stream()
                    .filter(step -> step.evidence().isPresent())
                    .forEach(
                        step ->
                            evidence.put(
                                step.id(),
                                List.copyOf(step.evidence().orElseThrow().verificationResults()))));
    Map<String, Long> usage = new LinkedHashMap<>();
    state
        .providerMetadata()
        .forEach(
            (key, value) -> {
              if (value instanceof Number number && key.toLowerCase().contains("token")) {
                usage.put(key, number.longValue());
              }
            });

    return new RecoveryAttachment(
        boundary,
        objective(state, previous),
        state.plan().isPresent()
            ? state.plan()
            : previous == null ? Optional.empty() : previous.plan(),
        state.plan().flatMap(plan -> plan.currentStep().map(PlanStep::id)),
        Map.copyOf(evidence),
        tail(reads.values().stream().toList()),
        tail(modified.stream().toList()),
        stringAttribute(state, "workspaceStatus").orElse("unknown"),
        tail(verification.stream().toList()),
        tail(skills.stream().toList()),
        tail(state.discoveredTools()),
        state.pendingApproval(),
        stringAttribute(state, "memoryReview"),
        tail(background.stream().toList()),
        tail(teams.stream().toList()),
        tail(references.stream().toList()),
        Map.copyOf(usage),
        Instant.now(clock));
  }

  private static String objective(MiniClaudeState state, RecoveryAttachment previous) {
    for (int index = state.messages().size() - 1; index >= 0; index--) {
      if (state.messages().get(index) instanceof UserMessage user)
        return abbreviate(user.text(), 800);
    }
    return previous == null ? "" : previous.objective();
  }

  private static Map<String, String> toolNames(List<AgentMessage> messages) {
    Map<String, String> names = new LinkedHashMap<>();
    for (AgentMessage message : messages) {
      if (message instanceof ToolMessage tool)
        names.put(tool.toolCallId(), tool.qualifiedToolName());
    }
    return names;
  }

  private static Optional<ReadFile> readFile(ToolResult result, MiniClaudeState state) {
    Object rawPath = result.metadata().get("path");
    Object rawHash = result.metadata().get("contentHash");
    if (!(rawPath instanceof String path) || !(rawHash instanceof String hash))
      return Optional.empty();
    int start = number(result.metadata().get("startLine"), 1);
    int end = number(result.metadata().get("endLine"), start);
    int hashedBytes = number(result.metadata().get("hashedBytes"), Integer.MAX_VALUE);
    boolean stale = isStale(state, path, hash, hashedBytes);
    return Optional.of(
        new ReadFile(
            path, hash, start, Math.max(start, end), abbreviate(result.summary(), 640), stale));
  }

  private static ReadFile refresh(ReadFile file, MiniClaudeState state) {
    return new ReadFile(
        file.path(),
        file.contentHash(),
        file.startLine(),
        file.endLine(),
        file.snippet(),
        isStale(state, file.path(), file.contentHash(), Integer.MAX_VALUE));
  }

  private static boolean isStale(
      MiniClaudeState state, String relative, String expectedHash, int maximumBytes) {
    Object workspace = state.request().attributes().get("workspace");
    if (!(workspace instanceof String root)) return true;
    try {
      Path path = Path.of(root).resolve(relative).normalize();
      byte[] bytes = Files.readAllBytes(path);
      if (maximumBytes < bytes.length) bytes = java.util.Arrays.copyOf(bytes, maximumBytes);
      return !expectedHash.equals("sha256:" + sha256(bytes));
    } catch (IOException | RuntimeException error) {
      return true;
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static void collectReferences(String text, Set<String> references) {
    Matcher matcher = RESULT_REFERENCE.matcher(text);
    while (matcher.find()) references.add(matcher.group());
  }

  private static Optional<String> stringAttribute(MiniClaudeState state, String key) {
    Object value = state.request().attributes().get(key);
    return value instanceof String text && !text.isBlank()
        ? Optional.of(abbreviate(text, 512))
        : Optional.empty();
  }

  private static List<String> listAttribute(MiniClaudeState state, String key) {
    Object value = state.request().attributes().get(key);
    if (!(value instanceof List<?> values)) return List.of();
    return tail(values.stream().map(String::valueOf).map(item -> abbreviate(item, 320)).toList());
  }

  private static void addMetadataList(ToolResult result, String key, LinkedHashSet<String> target) {
    Object value = result.metadata().get(key);
    if (value instanceof List<?> values) {
      values.stream().map(String::valueOf).map(item -> abbreviate(item, 320)).forEach(target::add);
    }
  }

  private static int number(Object value, int fallback) {
    return value instanceof Number number ? number.intValue() : fallback;
  }

  private static LinkedHashSet<String> merge(List<String> values) {
    return new LinkedHashSet<>(values);
  }

  private static <T> List<T> tail(List<T> values) {
    return List.copyOf(values.subList(Math.max(0, values.size() - MAX_ITEMS), values.size()));
  }

  private static String abbreviate(String value, int maximum) {
    String normalized = Objects.requireNonNullElse(value, "").replaceAll("\\s+", " ").strip();
    return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum) + "...";
  }
}
