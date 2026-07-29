package dev.miniclaudecode.persistence.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class UserConfigWriter {
  private static final Set<PosixFilePermission> OWNER_READ_WRITE =
      Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
  private final ObjectMapper mapper;

  public UserConfigWriter() {
    this(new ObjectMapper(new YAMLFactory()));
  }

  UserConfigWriter(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  public void upsertProvider(
      Path configFile, String profileName, ProviderProfile profile, boolean makeActive) {
    Objects.requireNonNull(configFile, "configFile must not be null");
    String normalizedName = requireProfileName(profileName);
    Objects.requireNonNull(profile, "profile must not be null");
    Path target = configFile.toAbsolutePath().normalize();

    try {
      Path parent = target.getParent();
      if (parent == null) {
        throw new IllegalArgumentException("configFile must have a parent directory");
      } else {
        Files.createDirectories(parent);
        ObjectNode root = this.readRoot(target);
        ObjectNode providers = objectChild(root, "providers");
        providers.set(normalizedName, profileNode(profile));
        if (makeActive) {
          root.put("active-provider", normalizedName);
        }

        this.writeAtomically(parent, target, root);
      }
    } catch (IOException var10) {
      throw new IllegalStateException("cannot write user config: " + target, var10);
    }
  }

  private ObjectNode readRoot(Path target) throws IOException {
    if (!Files.isRegularFile(target)) {
      return JsonNodeFactory.instance.objectNode();
    } else {
      JsonNode value = this.mapper.readTree(target.toFile());
      if (value == null || value.isNull()) {
        return JsonNodeFactory.instance.objectNode();
      } else if (value instanceof ObjectNode) {
        return (ObjectNode) value;
      } else {
        throw new IllegalArgumentException("user config root must be an object: " + target);
      }
    }
  }

  private static ObjectNode objectChild(ObjectNode parent, String field) {
    JsonNode existing = parent.get(field);
    if (existing == null || existing.isNull()) {
      ObjectNode created = parent.objectNode();
      parent.set(field, created);
      return created;
    } else if (existing instanceof ObjectNode) {
      return (ObjectNode) existing;
    } else {
      throw new IllegalArgumentException(field + " must be an object");
    }
  }

  private static ObjectNode profileNode(ProviderProfile profile) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("type", profile.type().name().toLowerCase(Locale.ROOT).replace('_', '-'));
    profile.baseUrl().ifPresent(uri -> node.put("base-url", uri.toString()));
    profile.apiKey().ifPresent(value -> node.put("api-key", value));
    profile.apiKeyEnv().ifPresent(value -> node.put("api-key-env", value));
    node.put("model", profile.model());
    node.put("temperature", profile.temperature());
    node.put("max-output-tokens", profile.maxOutputTokens());
    node.put("thinking", profile.thinking());
    node.put("timeout-seconds", profile.timeout().toSeconds());
    node.put("max-retries", profile.maxRetries());
    node.put(
        "output-protocol",
        profile.outputProtocol().name().toLowerCase(Locale.ROOT).replace('_', '-'));
    node.put("max-output-repairs", profile.maxOutputRepairs());
    return node;
  }

  private void writeAtomically(Path parent, Path target, ObjectNode root) throws IOException {
    Path temporary = Files.createTempFile(parent, ".config-", ".yaml.tmp");

    try {
      this.mapper.writeValue(temporary.toFile(), root);
      restrictPermissions(temporary);

      try {
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException var9) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }

      restrictPermissions(target);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void restrictPermissions(Path path) throws IOException {
    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
      Files.setPosixFilePermissions(path, OWNER_READ_WRITE);
    }
  }

  private static String requireProfileName(String value) {
    if (value != null && value.trim().matches("[A-Za-z0-9._-]+")) {
      return value.trim();
    } else {
      throw new IllegalArgumentException(
          "profile name may contain only letters, digits, '.', '_' and '-'");
    }
  }
}
