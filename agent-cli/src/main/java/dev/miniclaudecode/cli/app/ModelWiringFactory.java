package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.persistence.config.AppConfig;
import dev.miniclaudecode.persistence.config.ConfigLoader;
import dev.miniclaudecode.persistence.path.UserDataLayout;
import dev.miniclaudecode.providers.ProviderFactory;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Builds configuration, model routing, and the secret set shared by audit redaction. */
final class ModelWiringFactory {
  private ModelWiringFactory() {}

  static Wiring create(
      Path workspace,
      UserDataLayout layout,
      Map<String, String> environment,
      Optional<String> fakeResponse) {
    Optional<Path> projectConfig = Optional.of(workspace.resolve(".mini-claude-code/config.yaml"));
    AppConfig config = new ConfigLoader().load(layout.configFile(), projectConfig);
    ModelClient modelClient =
        fakeResponse
            .<ModelClient>map(StaticResponseModelClient::new)
            .orElseGet(
                () ->
                    new RoutingModelClient(config.providers(), environment, new ProviderFactory()));
    Set<String> secrets =
        Stream.concat(
                config.providers().values().stream()
                    .map(profile -> profile.resolvedApiKey(environment)),
                Stream.of(config.embedding().resolvedApiKey(environment)))
            .flatMap(Optional::stream)
            .collect(Collectors.toUnmodifiableSet());
    return new Wiring(config, modelClient, secrets);
  }

  record Wiring(AppConfig config, ModelClient modelClient, Set<String> secrets) {}
}
