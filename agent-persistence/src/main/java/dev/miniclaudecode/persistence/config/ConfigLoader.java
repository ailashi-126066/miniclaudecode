package dev.miniclaudecode.persistence.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.miniclaudecode.domain.model.OutputProtocolType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;

public final class ConfigLoader {
  private static final String DEFAULT_CONFIG_RESOURCE = "/default-config.yaml";
  private final ObjectMapper mapper;

  public ConfigLoader() {
    this(new ObjectMapper(new YAMLFactory()));
  }

  ConfigLoader(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  public AppConfig load(Path userConfig, Optional<Path> projectConfig) {
    Objects.requireNonNull(userConfig, "userConfig must not be null");
    Objects.requireNonNull(projectConfig, "projectConfig must not be null");
    ObjectNode merged = this.readDefaultConfig();
    if (Files.isRegularFile(userConfig)) {
      deepMerge(merged, this.readObject(userConfig));
    }

    projectConfig
        .filter(x$0 -> Files.isRegularFile(x$0))
        .ifPresent(path -> this.mergeProjectConfig(merged, path));
    return parseConfig(merged);
  }

  private ObjectNode readDefaultConfig() {
    try {
      ObjectNode var2;
      try (InputStream input = ConfigLoader.class.getResourceAsStream("/default-config.yaml")) {
        if (input == null) {
          throw new IllegalStateException("missing classpath resource: /default-config.yaml");
        }

        var2 = requireObject(this.mapper.readTree(input), "/default-config.yaml");
      }

      return var2;
    } catch (IOException var6) {
      throw new IllegalStateException("cannot read default config", var6);
    }
  }

  private ObjectNode readObject(Path path) {
    try {
      return requireObject(this.mapper.readTree(path.toFile()), path.toString());
    } catch (IOException var3) {
      throw new IllegalArgumentException("cannot read config file: " + path, var3);
    }
  }

  private static ObjectNode requireObject(JsonNode node, String source) {
    if (node == null || node.isNull()) {
      return JsonNodeFactory.instance.objectNode();
    } else if (node instanceof ObjectNode) {
      return (ObjectNode) node;
    } else {
      throw new IllegalArgumentException("config root must be an object: " + source);
    }
  }

  private void mergeProjectConfig(ObjectNode merged, Path projectPath) {
    ObjectNode project = this.readObject(projectPath);
    rejectProjectSecurityOverrides(project);
    rejectPlaintextProjectKeys(project, "$");
    deepMerge(merged, project);
  }

  private static void rejectProjectSecurityOverrides(ObjectNode project) {
    if (project.has("security")) {
      throw new SecurityException(
          "project config must not override user-controlled security policies");
    }
  }

  private static void rejectPlaintextProjectKeys(JsonNode node, String path) {
    if (node.isObject()) {
      node.properties()
          .forEach(
              entry -> {
                String childPath = path + "." + (String) entry.getKey();
                if (((String) entry.getKey()).equalsIgnoreCase("api-key")
                    && !((JsonNode) entry.getValue()).isNull()
                    && !((JsonNode) entry.getValue()).asText().isBlank()) {
                  throw new SecurityException(
                      "project config must not contain plaintext api-key at " + childPath);
                } else {
                  rejectPlaintextProjectKeys((JsonNode) entry.getValue(), childPath);
                }
              });
    } else if (node.isArray()) {
      for (int index = 0; index < node.size(); index++) {
        rejectPlaintextProjectKeys(node.get(index), path + "[" + index + "]");
      }
    }
  }

  private static void deepMerge(ObjectNode target, ObjectNode overlay) {
    overlay
        .properties()
        .forEach(
            entry -> {
              JsonNode existing = target.get((String) entry.getKey());
              JsonNode value = (JsonNode) entry.getValue();
              if (existing instanceof ObjectNode existingObject
                  && value instanceof ObjectNode valueObject) {
                deepMerge(existingObject, valueObject);
                return;
              }

              target.set((String) entry.getKey(), value.deepCopy());
            });
  }

  private static AppConfig parseConfig(ObjectNode root) {
    String activeProvider = requiredText(root, "active-provider");
    JsonNode providersNode = root.get("providers");
    if (providersNode != null && providersNode.isObject()) {
      Map<String, ProviderProfile> providers = new HashMap<>();

      for (Entry<String, JsonNode> entry : providersNode.properties()) {
        if (!(entry.getValue() instanceof ObjectNode profileNode)) {
          throw new IllegalArgumentException(
              "provider profile must be an object: " + entry.getKey());
        }

        providers.put(entry.getKey(), parseProfile(profileNode));
      }

      return new AppConfig(
          providers,
          activeProvider,
          parseEmbedding(root),
          parseCommandPolicy(root),
          parsePlanning(root),
          parseMemory(root));
    } else {
      throw new IllegalArgumentException("providers must be an object");
    }
  }

  private static PlanningConfig parsePlanning(ObjectNode root) {
    JsonNode node = root.path("planning");
    if (!(node instanceof ObjectNode planning)) {
      return PlanningConfig.defaults();
    }
    return new PlanningConfig(
        planning.path("enabled").asBoolean(true),
        planning.path("max-steps").asInt(12),
        planning.path("max-attempts-per-step").asInt(2),
        planning.path("max-revisions").asInt(3));
  }

  private static MemoryConfig parseMemory(ObjectNode root) {
    JsonNode node = root.path("memory");
    if (!(node instanceof ObjectNode memory)) {
      return MemoryConfig.defaults();
    }
    return new MemoryConfig(
        memory.path("enabled").asBoolean(true),
        optionalText(memory, "backend").orElse("sqlite"),
        memory.path("approval-required").asBoolean(true));
  }

  private static EmbeddingConfig parseEmbedding(ObjectNode root) {
    JsonNode node = root.path("rag").path("embedding");
    if (!(node instanceof ObjectNode embedding)) {
      return EmbeddingConfig.fastDefault();
    }
    return new EmbeddingConfig(
        EmbeddingConfig.Provider.parse(optionalText(embedding, "provider").orElse("auto")),
        optionalText(embedding, "base-url").map(ConfigLoader::parseUri),
        optionalText(embedding, "api-key"),
        optionalText(embedding, "api-key-env"),
        optionalText(embedding, "model").orElse(""),
        embedding.path("dimensions").asInt(384),
        Duration.ofSeconds(embedding.path("timeout-seconds").asLong(30L)));
  }

  private static CommandPolicyConfig parseCommandPolicy(ObjectNode root) {
    JsonNode node = root.path("security").path("shell");
    if (!(node instanceof ObjectNode shell)) {
      return CommandPolicyConfig.defaults();
    }
    return new CommandPolicyConfig(
        textList(shell.path("allow-prefixes")),
        textList(shell.path("deny-fragments")),
        shell.path("allowlist-only").asBoolean(false));
  }

  private static java.util.List<String> textList(JsonNode node) {
    if (!node.isArray()) {
      return java.util.List.of();
    }
    java.util.List<String> values = new java.util.ArrayList<>();
    node.forEach(
        value ->
            Optional.of(value.asText())
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .ifPresent(values::add));
    return java.util.List.copyOf(values);
  }

  private static ProviderProfile parseProfile(ObjectNode node) {
    return new ProviderProfile(
        ProviderProfile.Type.parse(requiredText(node, "type")),
        optionalText(node, "base-url").map(ConfigLoader::parseUri),
        optionalText(node, "api-key"),
        optionalText(node, "api-key-env"),
        requiredText(node, "model"),
        node.path("temperature").asDouble(0.2),
        node.path("max-output-tokens").asInt(8192),
        node.path("thinking").asBoolean(false),
        Duration.ofSeconds(node.path("timeout-seconds").asLong(120L)),
        node.path("max-retries").asInt(3),
        OutputProtocolType.parse(optionalText(node, "output-protocol").orElse("natural-language")),
        node.path("max-output-repairs").asInt(1));
  }

  private static URI parseUri(String value) {
    try {
      return URI.create(value);
    } catch (IllegalArgumentException var2) {
      throw new IllegalArgumentException("invalid base-url: " + value, var2);
    }
  }

  private static String requiredText(ObjectNode node, String field) {
    return optionalText(node, field)
        .orElseThrow(() -> new IllegalArgumentException(field + " must not be blank"));
  }

  private static Optional<String> optionalText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value != null && !value.isNull()) {
      String text = value.asText().trim();
      return text.isEmpty() ? Optional.empty() : Optional.of(text);
    } else {
      return Optional.empty();
    }
  }
}
