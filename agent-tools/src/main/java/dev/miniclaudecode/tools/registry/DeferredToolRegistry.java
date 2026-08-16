package dev.miniclaudecode.tools.registry;

import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolExposure;
import dev.miniclaudecode.tools.search.ToolSearchTool;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Immutable complete registry with a separate, session-scoped set of visible deferred schemas. */
public final class DeferredToolRegistry implements AgentToolRegistry {
  public static final int DEFAULT_SCHEMA_BUDGET = 12_000;

  private final DefaultToolRegistry all;
  private final Set<String> eagerNames;
  private final Map<String, ToolDescriptor> deferredDescriptors;
  private final Map<String, Set<String>> discoveredBySession = new ConcurrentHashMap<>();
  private final int schemaBudget;

  public DeferredToolRegistry(
      Collection<? extends AgentTool> eagerTools, Collection<? extends AgentTool> deferredTools) {
    this(eagerTools, deferredTools, DEFAULT_SCHEMA_BUDGET);
  }

  public DeferredToolRegistry(
      Collection<? extends AgentTool> eagerTools,
      Collection<? extends AgentTool> deferredTools,
      int schemaBudget) {
    Objects.requireNonNull(eagerTools, "eagerTools must not be null");
    Objects.requireNonNull(deferredTools, "deferredTools must not be null");
    if (schemaBudget < 512) {
      throw new IllegalArgumentException("schemaBudget must be at least 512 characters");
    }
    this.schemaBudget = schemaBudget;

    List<AgentTool> configured = new ArrayList<>();
    LinkedHashSet<String> eager = new LinkedHashSet<>();
    LinkedHashMap<String, ToolDescriptor> deferred = new LinkedHashMap<>();
    for (AgentTool tool : eagerTools) {
      AgentTool wrapped = configured(tool, ToolExposure.EAGER);
      configured.add(wrapped);
      eager.add(wrapped.descriptor().qualifiedName());
    }
    for (AgentTool tool : deferredTools) {
      AgentTool wrapped = configured(tool, ToolExposure.DEFERRED);
      configured.add(wrapped);
      deferred.put(wrapped.descriptor().qualifiedName(), wrapped.descriptor());
    }
    this.deferredDescriptors = Map.copyOf(deferred);
    AgentTool search = new ToolSearchTool(this);
    configured.add(search);
    LinkedHashSet<String> eagerWithSearch = new LinkedHashSet<>(eager);
    eagerWithSearch.add(search.descriptor().qualifiedName());
    this.eagerNames = Set.copyOf(eagerWithSearch);
    this.all = new DefaultToolRegistry(configured);
  }

  @Override
  public AgentTool require(String name) {
    return all.require(name);
  }

  @Override
  public AgentTool require(SessionId sessionId, String name) {
    AgentTool tool = all.require(name);
    String qualifiedName = tool.descriptor().qualifiedName();
    if (!eagerNames.contains(qualifiedName) && !discovered(sessionId).contains(qualifiedName)) {
      throw new IllegalArgumentException(
          "tool '" + qualifiedName + "' is deferred; call system:tool_search and select it first");
    }
    return tool;
  }

  @Override
  public Optional<AgentTool> find(String qualifiedName) {
    return all.find(qualifiedName);
  }

  @Override
  public List<ToolDescriptor> descriptors() {
    return all.descriptors().stream()
        .filter(descriptor -> eagerNames.contains(descriptor.qualifiedName()))
        .toList();
  }

  @Override
  public List<ToolDescriptor> descriptors(SessionId sessionId) {
    Set<String> visible = new LinkedHashSet<>(eagerNames);
    visible.addAll(discovered(sessionId));
    List<ToolDescriptor> result =
        all.descriptors().stream()
            .filter(descriptor -> visible.contains(descriptor.qualifiedName()))
            .toList();
    int characters = result.stream().mapToInt(value -> value.inputSchemaJson().length()).sum();
    if (characters > schemaBudget) {
      throw new IllegalStateException(
          "visible tool schemas exceed session budget: " + characters + "/" + schemaBudget);
    }
    return result;
  }

  @Override
  public List<ToolDescriptor> allDescriptors() {
    return all.descriptors();
  }

  public Set<String> discovered(SessionId sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    return Set.copyOf(discoveredBySession.getOrDefault(sessionId.value(), Set.of()));
  }

  public List<ToolDescriptor> discover(SessionId sessionId, Collection<String> names) {
    Objects.requireNonNull(names, "names must not be null");
    LinkedHashSet<String> selected = new LinkedHashSet<>();
    for (String rawName : names) {
      AgentTool tool = all.require(rawName);
      String name = tool.descriptor().qualifiedName();
      if (!deferredDescriptors.containsKey(name)) {
        throw new IllegalArgumentException("tool is already eager: " + name);
      }
      selected.add(name);
    }
    Set<String> sessionSet =
        discoveredBySession.computeIfAbsent(
            sessionId.value(), ignored -> ConcurrentHashMap.newKeySet());
    sessionSet.addAll(selected);
    return selected.stream().map(deferredDescriptors::get).toList();
  }

  public void restoreDiscovered(SessionId sessionId, Collection<String> names) {
    for (String name : names) {
      if (deferredDescriptors.containsKey(name)) {
        discoveredBySession
            .computeIfAbsent(sessionId.value(), ignored -> ConcurrentHashMap.newKeySet())
            .add(name);
      }
    }
  }

  public List<SearchHit> search(String query, int limit) {
    String normalized = Objects.requireNonNullElse(query, "").strip().toLowerCase(Locale.ROOT);
    int bounded = Math.max(1, Math.min(limit, 20));
    List<String> terms = List.of(normalized.split("\\s+"));
    return deferredDescriptors.values().stream()
        .map(descriptor -> new SearchHit(descriptor, score(descriptor, terms)))
        .filter(hit -> normalized.isEmpty() || hit.score() > 0)
        .sorted(
            Comparator.comparingInt(SearchHit::score)
                .reversed()
                .thenComparing(hit -> hit.descriptor().qualifiedName()))
        .limit(bounded)
        .toList();
  }

  private static int score(ToolDescriptor descriptor, List<String> terms) {
    String name = descriptor.qualifiedName().toLowerCase(Locale.ROOT);
    String tags = String.join(" ", descriptor.tags()).toLowerCase(Locale.ROOT);
    String text = (descriptor.summary() + " " + descriptor.description()).toLowerCase(Locale.ROOT);
    int score = 0;
    for (String term : terms) {
      if (term.isBlank()) continue;
      if (name.equals(term)) score += 100;
      else if (name.contains(term)) score += 20;
      if (tags.contains(term)) score += 10;
      if (text.contains(term)) score += 3;
    }
    return score;
  }

  private static AgentTool configured(AgentTool delegate, ToolExposure exposure) {
    Objects.requireNonNull(delegate, "tool must not be null");
    ToolDescriptor descriptor = delegate.descriptor().withExposure(exposure);
    return new AgentTool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public java.util.concurrent.CompletionStage<dev.miniclaudecode.domain.tool.ToolResult>
          execute(dev.miniclaudecode.domain.tool.ToolCall call, ToolContext context) {
        return delegate.execute(call, context);
      }
    };
  }

  public record SearchHit(ToolDescriptor descriptor, int score) {}
}
