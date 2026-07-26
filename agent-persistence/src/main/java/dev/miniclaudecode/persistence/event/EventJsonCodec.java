package dev.miniclaudecode.persistence.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.persistence.config.SecretRedactor;
import java.time.Instant;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class EventJsonCodec {
  private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE =
      new TypeReference<Map<String, Object>>() {};
  private static final Pattern SENSITIVE_FIELD =
      Pattern.compile("(?i)(authorization|(?:x[-_])?api[-_]?key|access[-_]?token|token|secret)");
  private final ObjectMapper mapper;
  private final SecretRedactor redactor;
  private final Set<String> knownSecrets;

  public EventJsonCodec(SecretRedactor redactor, Set<String> knownSecrets) {
    this(new ObjectMapper(), redactor, knownSecrets);
  }

  EventJsonCodec(ObjectMapper mapper, SecretRedactor redactor, Set<String> knownSecrets) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    this.redactor = Objects.requireNonNull(redactor, "redactor must not be null");
    this.knownSecrets =
        Set.copyOf(Objects.requireNonNull(knownSecrets, "knownSecrets must not be null"));
  }

  public String encode(AgentEvent event) {
    Objects.requireNonNull(event, "event must not be null");
    ObjectNode root = this.mapper.createObjectNode();
    root.put("eventId", event.eventId().toString());
    root.put("version", event.version());
    root.put("sessionId", event.sessionId().value());
    root.put("turnId", event.turnId().value());
    root.put("occurredAt", event.occurredAt().toString());
    root.put("type", event.type().name());
    JsonNode payload = this.mapper.valueToTree(event.payload());
    this.redactTree(payload, null);
    root.set("payload", payload);

    try {
      return this.mapper.writeValueAsString(root);
    } catch (JsonProcessingException var5) {
      throw new IllegalArgumentException("event payload cannot be encoded as JSON", var5);
    }
  }

  public EventJsonCodec.DecodeResult decode(String json) {
    Objects.requireNonNull(json, "json must not be null");

    try {
      JsonNode root = this.mapper.readTree(json);
      int version = required(root, "version").asInt();
      if (version > 1) {
        return EventJsonCodec.DecodeResult.skipped("unsupported event version " + version);
      } else {
        AgentEvent event =
            new AgentEvent(
                UUID.fromString(required(root, "eventId").asText()),
                version,
                SessionId.of(required(root, "sessionId").asText()),
                TurnId.of(required(root, "turnId").asLong()),
                Instant.parse(required(root, "occurredAt").asText()),
                AgentEventType.valueOf(required(root, "type").asText()),
                (Map) this.mapper.convertValue(required(root, "payload"), PAYLOAD_TYPE));
        return EventJsonCodec.DecodeResult.decoded(event);
      }
    } catch (JsonProcessingException | RuntimeException var5) {
      throw new IllegalArgumentException("invalid event JSON", var5);
    }
  }

  private void redactTree(JsonNode node, String fieldName) {
    if (node instanceof ObjectNode objectNode) {
      for (Entry<String, JsonNode> entry : objectNode.properties()) {
        JsonNode child = entry.getValue();
        if (child.isTextual()) {
          objectNode.set(entry.getKey(), this.redactText(child.asText(), entry.getKey()));
        } else {
          this.redactTree(child, entry.getKey());
        }
      }
    } else if (node instanceof ArrayNode arrayNode) {
      for (int index = 0; index < arrayNode.size(); index++) {
        JsonNode child = arrayNode.get(index);
        if (child.isTextual()) {
          arrayNode.set(index, this.redactText(child.asText(), fieldName));
        } else {
          this.redactTree(child, fieldName);
        }
      }
    }
  }

  private TextNode redactText(String value, String fieldName) {
    return fieldName != null && SENSITIVE_FIELD.matcher(fieldName).matches()
        ? TextNode.valueOf("***")
        : TextNode.valueOf(this.redactor.redact(value, this.knownSecrets));
  }

  private static JsonNode required(JsonNode root, String field) {
    if (root != null && root.isObject()) {
      JsonNode value = root.get(field);
      if (value != null && !value.isNull()) {
        return value;
      } else {
        throw new IllegalArgumentException("missing event field: " + field);
      }
    } else {
      throw new IllegalArgumentException("event root must be an object");
    }
  }

  public static record DecodeResult(Optional<AgentEvent> event, Optional<String> warning) {
    public DecodeResult(Optional<AgentEvent> event, Optional<String> warning) {
      event = Objects.requireNonNull(event, "event must not be null");
      warning = Objects.requireNonNull(warning, "warning must not be null");
      if (event.isPresent() == warning.isPresent()) {
        throw new IllegalArgumentException("decode result must contain either an event or warning");
      } else {
        this.event = event;
        this.warning = warning;
      }
    }

    static EventJsonCodec.DecodeResult decoded(AgentEvent event) {
      return new EventJsonCodec.DecodeResult(Optional.of(event), Optional.empty());
    }

    static EventJsonCodec.DecodeResult skipped(String warning) {
      return new EventJsonCodec.DecodeResult(Optional.empty(), Optional.of(warning));
    }
  }
}
