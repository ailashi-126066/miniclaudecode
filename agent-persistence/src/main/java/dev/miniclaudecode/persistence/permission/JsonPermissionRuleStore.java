package dev.miniclaudecode.persistence.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.miniclaudecode.domain.approval.PermissionRule;
import dev.miniclaudecode.domain.approval.PermissionRuleStore;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class JsonPermissionRuleStore implements PermissionRuleStore {
  private final Path file;
  private final ObjectMapper mapper = new ObjectMapper();

  public JsonPermissionRuleStore(Path file) {
    this.file = Objects.requireNonNull(file, "file must not be null").toAbsolutePath().normalize();
  }

  public synchronized List<PermissionRule> list() {
    if (!Files.exists(this.file)) {
      return List.of();
    } else {
      try {
        JsonNode root = this.mapper.readTree(this.file.toFile());
        if (root != null && root.isArray()) {
          List<PermissionRule> rules = new ArrayList<>();

          for (JsonNode node : root) {
            rules.add(
                new PermissionRule(
                    UUID.fromString(required(node, "ruleId")),
                    required(node, "workspace"),
                    required(node, "qualifiedToolName"),
                    required(node, "normalizedTarget"),
                    Instant.parse(required(node, "createdAt"))));
          }

          return List.copyOf(rules);
        } else {
          throw new IllegalArgumentException("permissions file must contain a JSON array");
        }
      } catch (IOException var5) {
        throw new IllegalStateException("failed to read permission rules", var5);
      }
    }
  }

  public synchronized void save(PermissionRule rule) {
    Objects.requireNonNull(rule, "rule must not be null");
    List<PermissionRule> rules = new ArrayList<>(this.list());
    boolean duplicate =
        rules.stream()
            .anyMatch(
                existing ->
                    existing.matches(
                        rule.workspace(), rule.qualifiedToolName(), rule.normalizedTarget()));
    if (!duplicate) {
      rules.add(rule);
      this.write(rules);
    }
  }

  private void write(List<PermissionRule> rules) {
    Path parent = this.file.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("permissions file must have a parent directory");
    } else {
      Path temporary = null;

      try {
        Files.createDirectories(parent);
        ArrayNode root = this.mapper.createArrayNode();

        for (PermissionRule rule : rules) {
          ObjectNode node = root.addObject();
          node.put("ruleId", rule.ruleId().toString());
          node.put("workspace", rule.workspace());
          node.put("qualifiedToolName", rule.qualifiedToolName());
          node.put("normalizedTarget", rule.normalizedTarget());
          node.put("createdAt", rule.createdAt().toString());
        }

        temporary = Files.createTempFile(parent, ".permissions-", ".tmp");
        this.mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);

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
        throw new IllegalStateException("failed to write permission rules", var17);
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
      throw new IllegalArgumentException("invalid permission rule field: " + field);
    }
  }
}
