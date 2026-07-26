package dev.miniclaudecode.persistence.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.ToolExecutionLedger;
import dev.miniclaudecode.domain.tool.ToolExecutionRecord;
import dev.miniclaudecode.domain.tool.ToolExecutionRecord.Status;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class JsonToolExecutionLedger implements ToolExecutionLedger {
  private final Path file;
  private final ObjectMapper mapper = new ObjectMapper();

  public JsonToolExecutionLedger(Path file) {
    this.file = Objects.requireNonNull(file, "file must not be null").toAbsolutePath().normalize();
  }

  public synchronized Optional<ToolExecutionRecord> find(String toolCallId) {
    return this.list().stream()
        .filter(record -> record.toolCallId().equals(toolCallId))
        .findFirst();
  }

  public synchronized List<ToolExecutionRecord> list() {
    if (!Files.exists(this.file)) {
      return List.of();
    } else {
      try {
        JsonNode root = this.mapper.readTree(this.file.toFile());
        if (root == null || !root.isArray()) {
          // A crash can still leave a zero-length or truncated ledger behind (the rename is
          // ordered, the data blocks are not). The ledger only carries de-duplication information
          // for crash recovery, so an unreadable file must degrade to "nothing was recorded"
          // instead of throwing on every remaining tool call of the turn.
          return List.of();
        } else {
          List<ToolExecutionRecord> records = new ArrayList<>();

          for (JsonNode node : root) {
            this.readOrSkip(node).ifPresent(records::add);
          }

          return List.copyOf(records);
        }
      } catch (IOException | RuntimeException var7) {
        return List.of();
      }
    }
  }

  public synchronized void save(ToolExecutionRecord record) {
    Objects.requireNonNull(record, "record must not be null");
    Map<String, ToolExecutionRecord> records = new LinkedHashMap<>();
    this.list().forEach(existing -> records.put(existing.toolCallId(), existing));
    records.put(record.toolCallId(), record);
    this.write(List.copyOf(records.values()));
  }

  private Optional<ToolExecutionRecord> readOrSkip(JsonNode node) {
    // Same reasoning as list(): one entry the parser cannot make sense of must cost us that entry's
    // de-duplication information, not the whole turn.
    try {
      return Optional.of(this.read(node));
    } catch (RuntimeException var3) {
      return Optional.empty();
    }
  }

  private ToolExecutionRecord read(JsonNode node) {
    return new ToolExecutionRecord(
        required(node, "toolCallId"),
        required(node, "qualifiedToolName"),
        Status.valueOf(required(node, "status")),
        RiskLevel.valueOf(required(node, "riskLevel")),
        optional(node, "beforeHash"),
        optional(node, "afterHash"),
        optional(node, "resultReference"),
        Instant.parse(required(node, "updatedAt")));
  }

  private void write(List<ToolExecutionRecord> records) {
    Path parent = this.file.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("ledger file must have a parent directory");
    } else {
      Path temporary = null;

      try {
        Files.createDirectories(parent);
        ArrayNode root = this.mapper.createArrayNode();

        for (ToolExecutionRecord record : records) {
          ObjectNode node = root.addObject();
          node.put("toolCallId", record.toolCallId());
          node.put("qualifiedToolName", record.qualifiedToolName());
          node.put("status", record.status().name());
          node.put("riskLevel", record.riskLevel().name());
          putOptional(node, "beforeHash", record.beforeHash());
          putOptional(node, "afterHash", record.afterHash());
          putOptional(node, "resultReference", record.resultReference());
          node.put("updatedAt", record.updatedAt().toString());
        }

        temporary = Files.createTempFile(parent, ".tool-ledger-", ".tmp");
        byte[] bytes = this.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);

        try (FileChannel channel =
            FileChannel.open(
                temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
          ByteBuffer buffer = ByteBuffer.wrap(bytes);

          while (buffer.hasRemaining()) {
            channel.write(buffer);
          }

          // ATOMIC_MOVE only makes the rename atomic, it does not flush the bytes behind it: after
          // a power loss the new name can already be visible while the contents are still empty or
          // truncated. JsonlEventStore forces every append for the same reason; the ledger must
          // force before the rename or the crash-recovery guarantee it exists for is lost.
          channel.force(true);
        }

        try {
          Files.move(
              temporary,
              this.file,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException var16) {
          Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (IOException var17) {
        throw new IllegalStateException("failed to write tool execution ledger", var17);
      } finally {
        if (temporary != null) {
          try {
            Files.deleteIfExists(temporary);
          } catch (IOException var15) {
          }
        }
      }
    }
  }

  private static String required(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value != null && value.isTextual() && !value.textValue().isBlank()) {
      return value.textValue();
    } else {
      throw new IllegalArgumentException("invalid ledger field: " + field);
    }
  }

  private static Optional<String> optional(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && !value.isNull() ? Optional.of(value.textValue()) : Optional.empty();
  }

  private static void putOptional(ObjectNode node, String field, Optional<String> value) {
    value.ifPresentOrElse(text -> node.put(field, text), () -> node.putNull(field));
  }
}
